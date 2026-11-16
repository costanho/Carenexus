import { zodResolver } from '@hookform/resolvers/zod';
import { type ChangeEvent, useMemo, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, Button, type ButtonVariant, colors, type DialogCloseReason, fontSize, FormActions, FormError, FormField,
  InfoRow, IS_WEB, Modal, radius, SelectField, type SelectOption, space, TextField, useUiTheme, webOnly,
} from '@/components/web/ui';
import { useUploadDocument } from '@/hooks/use-documents';
import type { ApiError } from '@/lib/api/errors';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { newIdempotencyKey } from '@/lib/api/idempotency';
import {
  DOCUMENT_ACCEPT, DOCUMENT_DESCRIPTION_MAX, DOCUMENT_FILE_HINT, DOCUMENT_LINK_LABELS, DOCUMENT_TITLE_MAX,
  DOCUMENT_TYPE_LABELS, DOCUMENT_TYPES, type DocumentDto, type DocumentLinkType, type DocumentType,
  FILE_TOO_LARGE_MESSAGE, fileProblem, formatFileSize, isDocumentType, REQUIRED_TYPE_FOR_LINK,
  SUGGESTED_TYPE_FOR_LINK, titleFromFileName,
} from '@/lib/documents/types';
import { type UploadDocumentFormValues, uploadDocumentSchema } from '@/lib/documents/validation';
import { confirmAction } from '@/lib/ui/dialogs';

// The upload control: a button that opens a dialog to choose one file, name it and send it into a patient's
// record (POST /api/documents, multipart, with progress). The checks here are hints; the server decides from
// the file's bytes and its FILE_REJECTED sentence is shown as sent.

export interface UploadDocumentButtonProps {
  /** The patient whose record receives the file (PAT-...). */
  patientSystemId: string;
  /** What the file is attached to. A lab or imaging result fixes the document type. */
  linkType?:   DocumentLinkType | null;
  /** The item's id; leave out while the item does not exist yet and pass the returned `documentId` to its endpoint. */
  linkId?:     number | null;
  /** Called with the stored document once the upload has finished. */
  onUploaded?: (doc: DocumentDto) => void;
  /** Text of the button. Default "Upload document". */
  label?:      string;
  variant?:    ButtonVariant;
  size?:       'sm' | 'md';
  /** The type to preselect when the link does not fix or suggest one (a list filtered by type). */
  defaultType?: DocumentType | null;
  /** Shown in the dialog so a doctor or caregiver can see whose record the file goes into. */
  patientName?: string | null;
  disabled?:   boolean;
  testID?:     string;
}

const NO_FILE_MESSAGE = 'Choose a file to upload';

const TYPE_OPTIONS: SelectOption<DocumentType>[] =
  DOCUMENT_TYPES.map(type => ({ value: type, label: DOCUMENT_TYPE_LABELS[type] }));

/**
 * The service-side validation rows carry `<field>: <text>` in `message` and may omit `errors`
 * (api/be-documents.md section 2.1); read the field from the prefix so the text lands on its control.
 */
function withFieldFromMessage(error: ApiError): ApiError {
  if (error.fieldErrors || error.code !== 'VALIDATION_FAILED') return error;
  const match = /^([A-Za-z][A-Za-z0-9]*): (.+)$/.exec(error.message);
  return match ? { ...error, fieldErrors: { [match[1]]: match[2] } } : error;
}

export function UploadDocumentButton({
  patientSystemId, linkType = null, linkId = null, onUploaded, label = 'Upload document', variant = 'primary',
  size, defaultType = null, patientName, disabled = false, testID,
}: UploadDocumentButtonProps) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button
        label={label}
        icon="cloud-upload-outline"
        variant={variant}
        size={size}
        onPress={() => setOpen(true)}
        disabled={disabled || !patientSystemId}
        testID={testID}
      />
      {/* Mounted per opening, so every opening starts with an empty form and its own idempotency key. */}
      {open ? (
        <UploadDialog
          patientSystemId={patientSystemId}
          linkType={linkType}
          linkId={linkId}
          defaultType={defaultType}
          patientName={patientName}
          onClose={() => setOpen(false)}
          onUploaded={doc => { setOpen(false); onUploaded?.(doc); }}
        />
      ) : null}
    </>
  );
}

interface UploadDialogProps {
  patientSystemId: string;
  linkType:    DocumentLinkType | null;
  linkId:      number | null;
  defaultType: DocumentType | null;
  patientName?: string | null;
  onClose:     () => void;
  onUploaded:  (doc: DocumentDto) => void;
}

function UploadDialog({
  patientSystemId, linkType, linkId, defaultType, patientName, onClose, onUploaded,
}: UploadDialogProps) {
  const { accent } = useUiTheme();
  const uploader = useUploadDocument();

  // A lab or imaging attachment must carry the matching type, so there is nothing to choose.
  const fixedType = linkType ? REQUIRED_TYPE_FOR_LINK[linkType] ?? null : null;
  const initialType: DocumentType =
    fixedType ?? defaultType ?? (linkType ? SUGGESTED_TYPE_FOR_LINK[linkType] : undefined) ?? 'OTHER';

  const { control, handleSubmit, setError, setValue, getValues, formState } = useForm<UploadDocumentFormValues>({
    resolver: zodResolver(uploadDocumentSchema),
    defaultValues: { title: '', documentType: initialType, description: '' },
  });

  const [file, setFile]           = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [cancelled, setCancelled] = useState(false);
  const inputRef = useRef<HTMLInputElement | null>(null);
  // One key per chosen file: kept for retries of that file, replaced when another file is chosen.
  const keyRef = useRef<string | null>(null);

  const formFields = useMemo(
    () => (fixedType ? (['title', 'description'] as const) : (['title', 'documentType', 'description'] as const)),
    [fixedType],
  );

  const onPick = (event: ChangeEvent<HTMLInputElement>) => {
    const chosen = event.target.files?.[0] ?? null;
    event.target.value = ''; // so choosing the same file again is still a change
    if (!chosen) return;
    setFormError(null);
    setCancelled(false);
    uploader.reset();
    const problem = fileProblem(chosen);
    if (problem) {
      setFile(null);
      keyRef.current = null;
      setFileError(problem);
      return;
    }
    setFile(chosen);
    setFileError(null);
    keyRef.current = newIdempotencyKey();
    if (!getValues('title').trim()) {
      const suggestion = titleFromFileName(chosen.name);
      if (suggestion) setValue('title', suggestion, { shouldDirty: true, shouldValidate: true });
    }
  };

  const showFailure = (raw: ApiError) => {
    const error = withFieldFromMessage(raw);
    // Everything the server says about the file itself belongs under the file control, in its own words.
    if (error.code === 'FILE_REJECTED' || error.status === 413 || error.message === FILE_TOO_LARGE_MESSAGE) {
      setFileError(error.message);
      return;
    }
    let remaining = error;
    if (error.fieldErrors && 'file' in error.fieldErrors) {
      const { file: fileMessage, ...others } = error.fieldErrors;
      setFileError(fileMessage === 'is required' ? NO_FILE_MESSAGE : fileMessage);
      if (Object.keys(others).length === 0) return;
      remaining = { ...error, fieldErrors: others };
    }
    setFormError(applyFieldErrors(remaining, setError, formFields));
  };

  const submit = handleSubmit(async values => {
    const key = keyRef.current;
    if (!file || !key) return;
    setFormError(null);
    setCancelled(false);
    const documentType = fixedType ?? (isDocumentType(values.documentType) ? values.documentType : 'OTHER');
    const result = await uploader.upload({
      patientSystemId,
      documentType,
      title: values.title,
      description: values.description,
      linkType,
      linkId: linkType ? linkId : null,
      file,
      fileName: file.name,
    }, key);
    if (result.ok) {
      keyRef.current = null;
      onUploaded(result.data);
      return;
    }
    if (result.cancelled) { setCancelled(true); return; }
    if (result.error) showFailure(result.error);
  });

  const onSubmit = () => {
    // Reported together with the text fields' messages, so one press shows everything that is missing.
    if (!file) setFileError(prev => prev ?? NO_FILE_MESSAGE);
    void submit();
  };

  const busy = uploader.pending;
  const dirty = !!file || formState.isDirty;
  const close = async (reason: DialogCloseReason) => {
    // Escape also arrives when it was pressed to dismiss the browser's type list; ask before discarding input.
    if (reason === 'escape' && dirty) {
      const discard = await confirmAction('Discard upload', 'Close without uploading this document?', 'Discard', true);
      if (!discard) return;
    }
    onClose();
  };

  const percent = Math.round(uploader.progress * 100);
  const attachedTo = linkType ? DOCUMENT_LINK_LABELS[linkType]?.toLowerCase() : null;

  return (
    <Modal
      open
      title="Upload document"
      onClose={reason => { void close(reason); }}
      dismissOnBackdrop={false}
      busy={busy}
      footer={(
        <FormActions
          submitLabel="Upload"
          submitIcon="cloud-upload-outline"
          onSubmit={onSubmit}
          onCancel={onClose}
          submitting={busy}
        />
      )}
    >
      {patientName?.trim() || attachedTo ? (
        <Text style={styles.context}>
          {patientName?.trim() ? `This file will be added to ${patientName.trim()}'s record` : 'This file will be added to the record'}
          {attachedTo ? ` and attached to the ${attachedTo}.` : '.'}
        </Text>
      ) : null}

      <FormError message={formError} />
      {cancelled ? <Banner tone="info" text="The upload was cancelled. Nothing was saved." onDismiss={() => setCancelled(false)} /> : null}

      <FormField label="File" required hint={DOCUMENT_FILE_HINT} error={fileError}>
        {a11y => (
          <View
            style={styles.fileRow}
            role="group"
            {...webOnly({ 'aria-labelledby': a11y.labelId, 'aria-describedby': a11y.describedBy })}
          >
            {IS_WEB ? (
              <input
                ref={inputRef}
                type="file"
                accept={DOCUMENT_ACCEPT}
                onChange={onPick}
                disabled={busy}
                // The button beside it is the control; the browser's own input stays out of the tab order.
                tabIndex={-1}
                aria-hidden
                style={{ display: 'none' }}
                data-testid="document-file-input"
              />
            ) : null}
            <Button
              label={file ? 'Change file' : 'Choose file'}
              icon="attach-outline"
              variant="secondary"
              onPress={() => inputRef.current?.click()}
              disabled={busy}
            />
            <Text style={[styles.fileName, !file && styles.fileNone]}>
              {file ? `${file.name} · ${formatFileSize(file.size)}` : 'No file chosen'}
            </Text>
          </View>
        )}
      </FormField>

      <TextField<UploadDocumentFormValues>
        control={control}
        name="title"
        label="Title"
        required
        maxLength={DOCUMENT_TITLE_MAX}
        placeholder="For example: Full blood count, November 2026"
        disabled={busy}
      />

      {fixedType ? (
        <InfoRow label="Document type" value={DOCUMENT_TYPE_LABELS[fixedType]} />
      ) : (
        <SelectField<DocumentType, UploadDocumentFormValues>
          control={control}
          name="documentType"
          label="Document type"
          required
          options={TYPE_OPTIONS}
          disabled={busy}
        />
      )}

      <TextField<UploadDocumentFormValues>
        control={control}
        name="description"
        label="Description"
        hint="Optional. A short note about what this file is."
        multiline
        rows={3}
        maxLength={DOCUMENT_DESCRIPTION_MAX}
        showCount
        disabled={busy}
      />

      {busy ? (
        <View style={styles.progress}>
          <View
            style={styles.track}
            role="progressbar"
            aria-label="Upload progress"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={percent}
          >
            <View style={[styles.bar, { width: `${percent}%`, backgroundColor: accent }]} />
          </View>
          <View style={styles.progressRow}>
            <Text style={styles.progressText}>
              {percent < 100 ? `Uploading… ${percent}%` : 'Checking and saving the file…'}
            </Text>
            <Button label="Cancel upload" variant="ghost" size="sm" onPress={uploader.cancel} />
          </View>
        </View>
      ) : null}
    </Modal>
  );
}

const styles = StyleSheet.create({
  context:  { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  fileRow:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, minWidth: 0 },
  fileName: { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0, fontSize: fontSize.md, color: colors.text },
  fileNone: { color: colors.textMuted },

  progress:     { gap: space.xs },
  track:        { height: 8, borderRadius: radius.pill, backgroundColor: colors.bgSubtle, overflow: 'hidden' },
  bar:          { height: 8, borderRadius: radius.pill },
  progressRow:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  progressText: { fontSize: fontSize.sm, color: colors.textMuted },
});
