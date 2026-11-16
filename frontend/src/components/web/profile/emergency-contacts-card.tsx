import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, Button, colors, type DialogCloseReason, EmptyView, ErrorView, fontSize, FormActions, FormError,
  LoadingView, Modal, SectionCard, space, StatusBadge, SwitchField, TextField,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import {
  type CreateEmergencyContact, type EmergencyContact, type EmergencyContactsState, MAX_EMERGENCY_CONTACTS,
  type UpdateEmergencyContact,
} from '@/hooks/use-emergency-contacts';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import { confirmAction } from '@/lib/ui/dialogs';
import {
  emergencyContactBody, emergencyContactChanges, emergencyContactDefaults, type EmergencyContactForm,
  emergencyContactSchema, PROFILE_LIMITS,
} from '@/lib/validation/profile';
import { PhoneField } from './phone-field';
import { applyProfileErrors, profileCell } from './profile-shared';

// The patient's own emergency contacts: list with the primary contact marked, add and edit in a dialog, delete
// after confirmation. Backed by /api/profile/emergency-contacts (be-profile-settings contract, section 5).

const FIELDS = ['name', 'relationship', 'phone', 'email', 'isPrimary'] as const;

interface Notice { tone: 'success' | 'danger'; text: string }

// ─── Add / edit dialog ────────────────────────────────────────────────────────

function ContactDialog({ contact, onClose, onSaved, onMissing, create, update }: {
  /** The contact to edit; null adds a new one. */
  contact:   EmergencyContact | null;
  onClose:   () => void;
  onSaved:   (saved: EmergencyContact | null, created: boolean) => void;
  /** The contact being edited no longer exists on the server. */
  onMissing: () => void;
  create:    (body: CreateEmergencyContact, idempotencyKey: string) => Promise<EmergencyContact>;
  update:    (contactId: number, changes: UpdateEmergencyContact) => Promise<EmergencyContact>;
}) {
  const { control, handleSubmit, setError, formState: { isDirty } } = useForm<EmergencyContactForm>({
    resolver: zodResolver(emergencyContactSchema),
    defaultValues: emergencyContactDefaults(contact),
  });
  const [formError, setFormError] = useState<string | null>(null);
  // One key per open form: a retried submit repeats it, so the server answers with the contact it already
  // created instead of adding a second one.
  const idempotency = useIdempotencyKey();

  const save = useAction(async (values: EmergencyContactForm): Promise<EmergencyContact | null> => {
    if (!contact) return create(emergencyContactBody(values), idempotency.current());
    const changes = emergencyContactChanges(contact, values);
    return Object.keys(changes).length === 0 ? null : update(contact.contactId, changes);
  }, { errorMessage: 'The contact could not be saved. Please try again.' });

  const submit = handleSubmit(async values => {
    setFormError(null);
    const result = await save.run(values);
    if (result.ok) {
      idempotency.renew();
      onSaved(result.data, !contact);
      return;
    }
    const error = result.error;
    if (!error) return;
    if (contact && error.status === 404) { onMissing(); return; }
    // The server answered and refused the request, so nothing was created: the next attempt is a new request.
    // A dropped connection, a server fault or "still being processed" keep the key for the retry.
    if (error.status >= 400 && error.status < 500 && error.code !== 'IDEMPOTENCY_IN_PROGRESS') idempotency.renew();
    setFormError(applyProfileErrors(error, setError, FIELDS));
  });

  const close = async (_reason: DialogCloseReason) => {
    if (isDirty && !(await confirmAction('Discard changes?', 'What you entered for this contact has not been saved.', 'Discard', true))) return;
    onClose();
  };

  return (
    <Modal
      open
      title={contact ? 'Edit emergency contact' : 'Add emergency contact'}
      onClose={close}
      dismissOnBackdrop={false}
      busy={save.pending}
      footer={(
        <FormActions
          submitLabel={contact ? 'Save changes' : 'Add contact'}
          onSubmit={submit}
          onCancel={() => { void close('button'); }}
          submitting={save.pending}
        />
      )}
    >
      <FormError message={formError} />
      <TextField
        label="Full name" control={control} name="name" required
        maxLength={PROFILE_LIMITS.contactName} autoComplete="off" autoCapitalize="words"
      />
      <TextField
        label="Relationship" control={control} name="relationship"
        hint="How this person is related to you, for example daughter or neighbour."
        maxLength={PROFILE_LIMITS.relationship} autoComplete="off"
      />
      <PhoneField
        label="Phone number" control={control} name="phone" required
        hint="Choose the country code, then enter the number."
      />
      <TextField
        label="Email" control={control} name="email"
        maxLength={PROFILE_LIMITS.email} inputMode="email" autoComplete="off" autoCapitalize="none"
      />
      <SwitchField
        label="Primary contact" control={control} name="isPrimary"
        description="The first person to reach in an emergency. You can have one primary contact; choosing this one replaces the current one."
      />
    </Modal>
  );
}

// ─── One row ──────────────────────────────────────────────────────────────────

function ContactRow({ contact, busy, onEdit, onDelete }: {
  contact:  EmergencyContact;
  /** This contact is being deleted. */
  busy:     boolean;
  onEdit:   () => void;
  onDelete: () => void;
}) {
  return (
    <View style={styles.row} role="listitem">
      <View style={styles.rowMain}>
        <View style={styles.nameLine}>
          <Text style={styles.name}>{contact.name}</Text>
          {contact.isPrimary ? <StatusBadge label="Primary" tone="info" icon="star" /> : null}
        </View>
        {contact.relationship ? <Text style={styles.detail}>{contact.relationship}</Text> : null}
        <Text style={styles.detail}>{contact.phone}</Text>
        {contact.email ? <Text style={styles.detail}>{contact.email}</Text> : null}
      </View>
      <View style={styles.rowActions}>
        <Button
          label="Edit" accessibilityLabel={`Edit ${contact.name}`} icon="create-outline"
          variant="secondary" size="sm" onPress={onEdit} disabled={busy}
        />
        <Button
          label="Delete" accessibilityLabel={`Delete ${contact.name}`} icon="trash-outline"
          variant="ghost" size="sm" onPress={onDelete} loading={busy}
        />
      </View>
    </View>
  );
}

// ─── Card ─────────────────────────────────────────────────────────────────────

type DialogState = { contact: EmergencyContact | null } | null;

/**
 * The Emergency Contacts card. The screen owns the `useEmergencyContacts` state and passes it in, so the
 * screen's Refresh button reloads the contacts together with the rest of the profile.
 */
export function EmergencyContactsCard({ state }: { state: EmergencyContactsState }) {
  const { contacts, loading, error, refetch, create, update, remove } = state;
  const [dialog, setDialog] = useState<DialogState>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [deletingId, setDeletingId] = useState<number | null>(null);

  const remover = useAction((contactId: number) => remove(contactId), {
    errorMessage: 'The contact could not be deleted. Please try again.',
  });

  const atLimit = contacts.length >= MAX_EMERGENCY_CONTACTS;
  const loaded = !loading && !error;

  const openAdd = () => { setNotice(null); setDialog({ contact: null }); };
  const openEdit = (contact: EmergencyContact) => { setNotice(null); setDialog({ contact }); };

  const onSaved = (saved: EmergencyContact | null, created: boolean) => {
    setDialog(null);
    // Null: the edit form was saved without a change, so there is nothing to announce.
    if (saved) {
      setNotice({
        tone: 'success',
        text: created ? `${saved.name} was added to your emergency contacts.` : `${saved.name} was updated.`,
      });
    }
  };

  const onMissing = () => {
    setDialog(null);
    setNotice({ tone: 'danger', text: 'That contact no longer exists. The list has been refreshed.' });
    refetch();
  };

  const onDelete = async (contact: EmergencyContact) => {
    const confirmed = await confirmAction(
      'Delete emergency contact',
      `Remove ${contact.name} from your emergency contacts? This cannot be undone.`,
      'Delete',
      true,
    );
    if (!confirmed) return;
    setNotice(null);
    setDeletingId(contact.contactId);
    const result = await remover.run(contact.contactId);
    setDeletingId(null);
    if (result.ok) {
      setNotice({ tone: 'success', text: `${contact.name} was removed from your emergency contacts.` });
    } else if (result.error) {
      if (result.error.status === 404) {
        // Already deleted elsewhere: the list on screen is out of date.
        setNotice({ tone: 'danger', text: 'That contact no longer exists. The list has been refreshed.' });
        refetch();
      } else {
        setNotice({ tone: 'danger', text: result.error.message });
      }
    }
  };

  return (
    <SectionCard
      title="Emergency Contacts"
      subtitle="People your care team can reach if you need urgent help."
      style={profileCell}
      action={loaded && contacts.length > 0 && !atLimit
        ? <Button label="Add contact" icon="add" variant="secondary" size="sm" onPress={openAdd} />
        : undefined}
    >
      {notice ? <Banner tone={notice.tone} text={notice.text} onDismiss={() => setNotice(null)} /> : null}

      {loading ? (
        <LoadingView compact label="Loading emergency contacts" />
      ) : error ? (
        <ErrorView compact message={error} onRetry={refetch} />
      ) : contacts.length === 0 ? (
        <EmptyView
          compact
          icon="people-outline"
          title="No emergency contacts yet"
          sub="Add someone your care team can call if you need urgent help."
          action={{ label: 'Add contact', icon: 'add', onPress: openAdd }}
        />
      ) : (
        <>
          <View style={styles.list} role="list" aria-label="Emergency contacts">
            {contacts.map(contact => (
              <ContactRow
                key={contact.contactId}
                contact={contact}
                busy={deletingId === contact.contactId}
                onEdit={() => openEdit(contact)}
                onDelete={() => { void onDelete(contact); }}
              />
            ))}
          </View>
          {atLimit ? (
            <Text style={styles.limit}>
              You have saved the maximum of {MAX_EMERGENCY_CONTACTS} contacts. Delete one to add another.
            </Text>
          ) : null}
        </>
      )}

      {dialog ? (
        <ContactDialog
          // A fresh form (and a fresh idempotency key) for every time the dialog opens.
          key={dialog.contact ? `edit-${dialog.contact.contactId}` : 'add'}
          contact={dialog.contact}
          onClose={() => setDialog(null)}
          onSaved={onSaved}
          onMissing={onMissing}
          create={create}
          update={update}
        />
      ) : null}
    </SectionCard>
  );
}

const styles = StyleSheet.create({
  list: { minWidth: 0 },
  row: {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between',
    columnGap: space.md, rowGap: space.sm, paddingVertical: space.md,
    borderTopWidth: 1, borderTopColor: colors.border,
  },
  rowMain:    { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, gap: 2 },
  nameLine:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
  name:       { flexShrink: 1, fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  detail:     { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  rowActions: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
  limit:      { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
