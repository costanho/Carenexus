import { useEffect, useRef, useState } from 'react';
import { type DialogCloseReason, Modal } from '@/components/web/ui';
import { confirmAction } from '@/lib/ui/dialogs';
import { AddCareTeamMemberForm, type AddCareTeamMemberFormProps } from './add-care-team-member-form';

export interface AddCareTeamMemberDialogProps
  extends Omit<AddCareTeamMemberFormProps, 'onCancel' | 'onSubmittingChange' | 'onDirtyChange'> {
  open: boolean;
  /** Called when the dialog is dismissed without adding anyone. After a successful add `onAdded` is called instead. */
  onClose: () => void;
}

/**
 * The add-member form in a dialog. It cannot be dismissed while the request is running or by a click outside
 * it, and Escape asks before throwing away what was entered (Escape also arrives when it closed a select popup).
 */
export function AddCareTeamMemberDialog({ open, onClose, ...form }: AddCareTeamMemberDialogProps) {
  const [submitting, setSubmitting] = useState(false);
  const dirty = useRef(false);
  const asking = useRef(false);

  useEffect(() => {
    if (!open) { dirty.current = false; setSubmitting(false); }
  }, [open]);

  const close = async (reason: DialogCloseReason) => {
    if (reason === 'escape' && dirty.current) {
      if (asking.current) return;
      asking.current = true;
      const discard = await confirmAction('Discard this member?', 'What you entered will not be saved.', 'Discard', true);
      asking.current = false;
      if (!discard) return;
    }
    onClose();
  };

  return (
    <Modal
      open={open}
      title={form.fixedProvider ? 'Add to care team' : 'Add care team member'}
      onClose={reason => { void close(reason); }}
      dismissOnBackdrop={false}
      busy={submitting}
    >
      <AddCareTeamMemberForm
        {...form}
        onCancel={onClose}
        onSubmittingChange={setSubmitting}
        onDirtyChange={value => { dirty.current = value; }}
      />
    </Modal>
  );
}
