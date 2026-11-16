import { useState } from 'react';
import { View } from 'react-native';
import {
  Banner, type BannerTone, Button, EmptyView, ErrorView, FormActions, FormError, LoadingView, Modal, SectionCard,
  StatusBadge,
} from '@/components/web/ui';
import type { ActionState } from '@/hooks/use-action';
import { useLegalDocument, useLegalStatus } from '@/hooks/use-account-legal';
import { formatInstant, formatInstantDate, LEGAL_DOC_LABEL, legalDocLabel } from '@/lib/account/format';
import type { AcceptLegalRequest, LegalDocType, LegalStatus } from '@/lib/account/types';
import { accountStyles, ButtonRow, CardPart, ListRow, ListRowText, Para } from './account-ui';
import { LegalDocumentText } from './legal-document-text';

interface Notice { tone: BannerTone; text: string }

const DOC_TYPES: readonly LegalDocType[] = ['TERMS', 'PRIVACY'];

/**
 * Shows the current text of one document. When the account still has to accept it, the dialog has an accept
 * button that records the version of the text on screen (the version that was actually read).
 */
function LegalDocumentDialog({ docType, needsAcceptance, accept, onAccepted, onStale, onClose }: {
  docType: LegalDocType;
  needsAcceptance: boolean;
  accept:  ActionState<[AcceptLegalRequest], LegalStatus>;
  onAccepted: (next: LegalStatus) => void;
  /** The version on screen stopped being current while it was read; the caller reloads the status. */
  onStale: () => void;
  onClose: () => void;
}) {
  const doc = useLegalDocument(docType);
  const [formError, setFormError] = useState<string | null>(null);
  const document = doc.document;

  const onAccept = async () => {
    if (!document) return;
    setFormError(null);
    const result = await accept.run({ docType: document.docType, version: document.version });
    if (result.ok) { onAccepted(result.data); return; }
    const error = result.error;
    if (!error) return;
    if (error.code === 'LEGAL_ACCEPTANCE_REQUIRED') {
      // A newer version was published meanwhile: show it and ask again.
      setFormError(`${error.message} The latest version is shown below.`);
      doc.refetch();
      onStale();
    } else {
      setFormError(error.message);
    }
  };

  const title = document?.title ?? LEGAL_DOC_LABEL[docType];

  return (
    <Modal
      open title={title} size="lg" onClose={onClose} busy={accept.pending}
      dismissOnBackdrop={!needsAcceptance}
      footer={needsAcceptance && document ? (
        <FormActions
          submitLabel="I have read and accept this version" onSubmit={onAccept} submitting={accept.pending}
          onCancel={onClose} cancelLabel="Not now"
        />
      ) : (
        <Button label="Close" variant="secondary" onPress={onClose} />
      )}
    >
      {doc.loading ? (
        <LoadingView compact label={`Loading the ${LEGAL_DOC_LABEL[docType]}`} />
      ) : !document ? (
        <ErrorView compact message={doc.error ?? 'The document could not be loaded.'} onRetry={doc.refetch} />
      ) : (
        <View style={accountStyles.stack}>
          <FormError message={formError} />
          <Para muted>{`Version ${document.version}, effective ${formatInstantDate(document.effectiveAt)}`}</Para>
          {document.pendingLegalReview ? (
            <Banner tone="warning" text="This text is a placeholder that is still pending legal review." />
          ) : null}
          <LegalDocumentText contentMd={document.contentMd} baseLevel={3} />
        </View>
      )}
    </Modal>
  );
}

/**
 * Terms of Service and Privacy Policy: which versions the account accepted and when, with the current version
 * to review and accept when a newer one was published.
 */
export function LegalAcceptancesCard() {
  const legal = useLegalStatus();
  const [open, setOpen] = useState<LegalDocType | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);

  const status = legal.status;
  const pendingTypes = new Set((status?.pending ?? []).map(p => p.docType));

  let body;
  if (legal.loading) {
    body = <LoadingView compact label="Loading your legal acceptances" />;
  } else if (!status) {
    body = <ErrorView compact message={legal.error ?? 'Your legal acceptances could not be loaded.'} onRetry={legal.refetch} />;
  } else {
    body = (
      <>
        {legal.error ? <Banner tone="danger" text={legal.error} action={{ label: 'Retry', onPress: legal.refetch }} /> : null}

        {status.pending.length > 0 ? (
          <CardPart title="Waiting for your acceptance" first>
            <Banner
              tone="warning"
              text={status.pending.length === 1
                ? 'A new version of a document is in effect. Review and accept it to keep using CareNexus.'
                : 'New versions of these documents are in effect. Review and accept them to keep using CareNexus.'}
            />
            <View style={accountStyles.list} accessibilityRole="list" aria-label="Documents waiting for your acceptance">
              {status.pending.map(doc => (
                <View key={`${doc.docType}:${doc.version}`} role="listitem">
                  <ListRow>
                    <ListRowText
                      title={doc.title || legalDocLabel(doc.docType)}
                      lines={[`Version ${doc.version}, effective ${formatInstantDate(doc.effectiveAt)}`]}
                    />
                    <StatusBadge label="Not accepted" tone="warning" />
                    <Button
                      label="Review and accept" size="sm"
                      accessibilityLabel={`Review and accept the ${doc.title || legalDocLabel(doc.docType)}, version ${doc.version}`}
                      onPress={() => { setNotice(null); legal.accept.reset(); setOpen(doc.docType); }}
                    />
                  </ListRow>
                </View>
              ))}
            </View>
          </CardPart>
        ) : null}

        <CardPart title="What you have accepted" first={status.pending.length === 0}>
          {status.accepted.length === 0 ? (
            <EmptyView
              compact icon="document-text-outline" title="No acceptances recorded"
              sub="Nothing has been accepted with this account yet."
            />
          ) : (
            <View style={accountStyles.list} accessibilityRole="list" aria-label="Accepted document versions">
              {status.accepted.map(row => (
                <View key={row.acceptanceId} role="listitem">
                  <ListRow>
                    <ListRowText
                      title={legalDocLabel(row.docType)}
                      lines={[`Version ${row.version}`, `Accepted ${formatInstant(row.acceptedAt)}`]}
                    />
                    {row.current
                      ? <StatusBadge label="Current version" tone="success" />
                      : <StatusBadge label="Earlier version" tone="neutral" />}
                  </ListRow>
                </View>
              ))}
            </View>
          )}
        </CardPart>

        <CardPart title="Read the current documents">
          <ButtonRow>
            {DOC_TYPES.map(docType => (
              <Button
                key={docType}
                label={LEGAL_DOC_LABEL[docType]} variant="secondary" icon="reader-outline"
                accessibilityLabel={`Read the ${LEGAL_DOC_LABEL[docType]}`}
                onPress={() => { setNotice(null); legal.accept.reset(); setOpen(docType); }}
              />
            ))}
          </ButtonRow>
        </CardPart>
      </>
    );
  }

  return (
    <SectionCard title="Terms and privacy" subtitle="The versions of the Terms of Service and Privacy Policy you have accepted.">
      {notice ? <Banner tone={notice.tone} text={notice.text} onDismiss={() => setNotice(null)} /> : null}
      {body}
      {open ? (
        <LegalDocumentDialog
          docType={open}
          needsAcceptance={pendingTypes.has(open)}
          accept={legal.accept}
          onClose={() => setOpen(null)}
          onStale={legal.refetch}
          onAccepted={next => {
            const label = LEGAL_DOC_LABEL[open];
            setOpen(null);
            setNotice({
              tone: 'success',
              text: next.actionRequired
                ? `You accepted the ${label}. Another document is still waiting for your acceptance.`
                : `You accepted the ${label}. Nothing else is waiting for your acceptance.`,
            });
          }}
        />
      ) : null}
    </SectionCard>
  );
}
