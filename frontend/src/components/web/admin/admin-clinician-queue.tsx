import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, Button, Card, colors, fontSize, FormActions, FormError, InfoRow, Modal, PagedList, Screen, space,
  StatusBadge, type TabItem, Tabs, TextField,
} from '@/components/web/ui';
import { useAdminDoctors, useDoctorVerificationActions, usePendingDoctorCount } from '@/hooks/use-admin-doctors';
import {
  ADMIN_ERROR, type AdminDoctor, fullName, LICENCE_FIELDS, type LicenceForm, type LicenceRequest, licenceSchema,
  registryLabel, registryTone, toLicenceRequest, VERIFICATION_STATUSES, type VerificationStatus, verificationLabel,
  verificationTone,
} from '@/lib/admin/types';
import type { ApiError } from '@/lib/api/errors';
import { applyFieldErrors } from '@/lib/api/form-errors';
import type { SectionProps } from '@/lib/nav/sections';
import { formatClinicDateTime } from '@/lib/time/clinic';
import {
  type ActionNotice, ActionNoticeBanner, ConfirmDialog, LinkText, ReasonDialog, useAdminNav,
} from './admin-users';

// Administrator screen "Clinician verification": clinicians by verification status with their licence details
// and the answer of the licence registry. An administrator approves, rejects with a reason, corrects the licence
// details, and can approve a rejected or suspended clinician again, so a rejection is never a dead end.

// ─── Shared with the user detail screen ───────────────────────────────────────

/** "Zimbabwe (ZW)" for a country code the browser knows, otherwise the code itself. */
export function countryLabel(code: string | null | undefined): string {
  const raw = (code ?? '').trim().toUpperCase();
  if (!/^[A-Z]{2}$/.test(raw)) return raw;
  try {
    const name = new Intl.DisplayNames(['en'], { type: 'region' }).of(raw);
    return name && name !== raw ? `${name} (${raw})` : raw;
  } catch {
    return raw;
  }
}

export function VerificationBadge({ status }: { status: VerificationStatus }) {
  return <StatusBadge label={verificationLabel(status)} tone={verificationTone(status)} />;
}

/** The licence registry's answer. It informs the decision and never makes it. */
export function RegistryBadge({ status }: { status: string | null | undefined }) {
  return <StatusBadge label={registryLabel(status)} tone={registryTone(status)} />;
}

/** The licence and decision fields of a clinician record, as label and value rows. */
export function ClinicianFacts({ doctor }: { doctor: AdminDoctor }) {
  const decided = doctor.verifiedBySystemId
    ? `${doctor.verifiedBySystemId}${doctor.verifiedAt ? `, ${formatClinicDateTime(doctor.verifiedAt)}` : ''}`
    : null;
  return (
    <View style={styles.facts}>
      <InfoRow label="Specialization" value={doctor.specialization} />
      <InfoRow label="Licence number" value={doctor.licenseNo} emptyText="No licence number on file" />
      <InfoRow label="Licensing body" value={doctor.licensingBody} />
      <InfoRow label="Licence country" value={countryLabel(doctor.licenseCountry)} />
      <InfoRow label="Registry check"><RegistryBadge status={doctor.registryStatus} /></InfoRow>
      {doctor.rejectionReason ? (
        <InfoRow
          label={doctor.verificationStatus === 'SUSPENDED' ? 'Suspension reason' : 'Rejection reason'}
          value={doctor.rejectionReason}
        />
      ) : null}
      <InfoRow label="Last decision by" value={decided} emptyText="No decision yet" />
    </View>
  );
}

/** How a licence submit ended. `fromSave` tells a failed save from a failed approval that followed a good save. */
export interface LicenceSubmitResult {
  ok:        boolean;
  error?:    ApiError | null;
  fromSave?: boolean;
}

/**
 * Enter or correct a clinician's licence details (PUT .../licence replaces all four fields). With `thenApprove`
 * the clinician is approved right after the save: the way out of "a licence number is required".
 */
export function LicenceDialog({ doctor, thenApprove = false, pending, onSubmit, onClose }: {
  doctor:  AdminDoctor;
  thenApprove?: boolean;
  pending: boolean;
  /** Saves (and approves when asked). The dialog stays open and shows the error unless the result is ok. */
  onSubmit: (request: LicenceRequest) => Promise<LicenceSubmitResult>;
  onClose: () => void;
}) {
  const { control, handleSubmit, setError } = useForm<LicenceForm>({
    resolver: zodResolver(licenceSchema),
    defaultValues: {
      licenseNo:      doctor.licenseNo ?? '',
      licensingBody:  doctor.licensingBody ?? '',
      licenseCountry: doctor.licenseCountry ?? '',
      specialization: doctor.specialization ?? '',
    },
  });
  const [formError, setFormError] = useState<string | null>(null);
  const name = fullName(doctor, doctor.doctorSystemId);

  const submit = handleSubmit(async values => {
    setFormError(null);
    const result = await onSubmit(toLicenceRequest(values));
    if (result.ok || !result.error) return;
    const failure = result.error;
    if (!result.fromSave) {
      // The licence was saved; the approval that followed was refused.
      setFormError(`The licence details were saved, but the clinician was not approved. ${failure.message}`);
      return;
    }
    if (failure.status === 409) {
      // The only conflict of this endpoint: another doctor already has the number.
      setError('licenseNo', { type: 'server', message: failure.message }, { shouldFocus: true });
      return;
    }
    setFormError(applyFieldErrors(failure, setError, LICENCE_FIELDS));
  });

  return (
    <Modal
      open
      title={thenApprove ? 'Licence details needed' : 'Edit licence'}
      onClose={() => onClose()}
      dismissOnBackdrop={false}
      busy={pending}
      footer={(
        <FormActions
          submitLabel={thenApprove ? 'Save and approve' : 'Save licence'}
          onSubmit={() => { void submit(); }} onCancel={onClose} submitting={pending}
        />
      )}
    >
      {thenApprove ? (
        <Banner tone="info" text={`A licence number is required before ${name} can be approved. Enter it to save the licence and approve the clinician.`} />
      ) : (
        <Text style={styles.dialogText}>
          Licence details of {name} ({doctor.doctorSystemId}). The clinician cannot edit them. Saving does not change
          the verification status.
        </Text>
      )}
      <TextField label="Licence number" control={control} name="licenseNo" required maxLength={50} autoCapitalize="characters" autoComplete="off" />
      <TextField label="Licensing body" control={control} name="licensingBody" maxLength={150} autoComplete="off" />
      <TextField
        label="Licence country" control={control} name="licenseCountry" maxLength={2} autoCapitalize="characters" autoComplete="off"
        hint="Two-letter country code, for example ZW."
      />
      <TextField label="Specialization" control={control} name="specialization" maxLength={150} autoComplete="off" />
      <FormError message={formError} />
    </Modal>
  );
}

// ─── The screen ───────────────────────────────────────────────────────────────

const TAB_LABEL: Record<VerificationStatus, string> = {
  PENDING: 'Awaiting verification', REJECTED: 'Rejected', SUSPENDED: 'Suspended', APPROVED: 'Approved',
};

const EMPTY: Record<VerificationStatus, { title: string; sub?: string }> = {
  PENDING:   { title: 'No clinicians are waiting for verification', sub: 'New clinician registrations appear here.' },
  REJECTED:  { title: 'No rejected registrations' },
  SUSPENDED: { title: 'No suspended clinicians', sub: 'A clinician is suspended when an administrator deactivates the account.' },
  APPROVED:  { title: 'No approved clinicians yet' },
};

function parseTab(value: string | undefined): VerificationStatus {
  const key = (value ?? '').trim().toUpperCase();
  return (VERIFICATION_STATUSES as readonly string[]).includes(key) ? (key as VerificationStatus) : 'PENDING';
}

type Dialog =
  | { kind: 'approve'; doctor: AdminDoctor }
  | { kind: 'reject'; doctor: AdminDoctor }
  | { kind: 'licence'; doctor: AdminDoctor; thenApprove: boolean };

/**
 * The clinician verification queue. The status tab lives in the URL (`tab`), so the notification link opens the
 * waiting registrations and a refresh keeps the tab.
 */
export default function AdminClinicianQueue(props: Partial<SectionProps>) {
  const { params, theme, setParams, openUser } = useAdminNav(props);
  const status = parseTab(params.tab);

  const doctors = useAdminDoctors(status);
  const pending = usePendingDoctorCount();
  const actions = useDoctorVerificationActions();
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [notice, setNotice] = useState<ActionNotice | null>(null);

  const tabs: TabItem<VerificationStatus>[] = VERIFICATION_STATUSES.map(key => ({
    key, label: TAB_LABEL[key], badge: key === 'PENDING' ? pending.count ?? undefined : undefined,
  }));

  const replaceRow = (updated: AdminDoctor) => {
    doctors.setItems(prev => prev.map(row => (row.doctorSystemId === updated.doctorSystemId ? updated : row)));
  };

  /** After a decision the clinician usually belongs to another tab: the row leaves and the counts are read again. */
  const afterDecision = (updated: AdminDoctor, text: string, detail?: string | null) => {
    setDialog(null);
    if (updated.verificationStatus === status) {
      replaceRow(updated);
    } else {
      doctors.setItems(prev => prev.filter(row => row.doctorSystemId !== updated.doctorSystemId));
      doctors.refetch();
    }
    pending.refetch();
    setNotice({
      tone: 'success', text, detail,
      action: openUser ? { label: 'View account', onPress: () => openUser(updated.doctorSystemId) } : undefined,
    });
  };

  const approvedNotice = (updated: AdminDoctor): [string, string | null] => {
    const who = `${fullName(updated, updated.doctorSystemId)} (${updated.doctorSystemId})`;
    const text = updated.isActive
      ? `${who} is approved and can sign in as a clinician.`
      : `${who} is approved.`;
    const detail = updated.email && !updated.emailVerified
      ? 'Their email address is not confirmed yet. They may be asked to confirm it before they can sign in.'
      : null;
    return [text, detail];
  };

  const startApprove = (doctor: AdminDoctor) => {
    actions.approve.reset();
    actions.saveLicence.reset();
    // Without a licence number the server refuses the approval (LICENCE_REQUIRED): ask for the licence first.
    setDialog(doctor.licenseNo?.trim() ? { kind: 'approve', doctor } : { kind: 'licence', doctor, thenApprove: true });
  };

  const confirmApprove = async (doctor: AdminDoctor) => {
    const result = await actions.approve.run(doctor.doctorSystemId);
    if (result.ok) {
      afterDecision(result.data, ...approvedNotice(result.data));
      return;
    }
    if (result.error?.code === ADMIN_ERROR.LICENCE_REQUIRED) {
      actions.approve.reset();
      setDialog({ kind: 'licence', doctor, thenApprove: true });
    }
  };

  const submitLicence = async (doctor: AdminDoctor, thenApprove: boolean, request: LicenceRequest) => {
    const saved = await actions.saveLicence.run(doctor.doctorSystemId, request);
    if (!saved.ok) return { ok: false, error: saved.error, fromSave: true };
    replaceRow(saved.data);
    const who = `${fullName(saved.data, saved.data.doctorSystemId)} (${saved.data.doctorSystemId})`;
    if (!thenApprove) {
      setDialog(null);
      setNotice({ tone: 'success', text: `The licence details of ${who} were saved.` });
      return { ok: true };
    }
    const approved = await actions.approve.run(doctor.doctorSystemId);
    if (!approved.ok) {
      // The dialog stays open on the saved values; it now holds the row as the server returned it.
      setDialog({ kind: 'licence', doctor: saved.data, thenApprove: true });
      return { ok: false, error: approved.error, fromSave: false };
    }
    afterDecision(approved.data, ...approvedNotice(approved.data));
    return { ok: true };
  };

  const submitReject = async (doctor: AdminDoctor, reason: string) => {
    const result = await actions.reject.run(doctor.doctorSystemId, reason);
    if (!result.ok) return;
    const who = `${fullName(result.data, result.data.doctorSystemId)} (${result.data.doctorSystemId})`;
    afterDecision(
      result.data,
      `The registration of ${who} was rejected.`,
      result.data.email ? 'The clinician is told by email, with your reason. You can still approve this clinician later.' : null,
    );
  };

  const renderDoctor = (doctor: AdminDoctor) => {
    const name = fullName(doctor, doctor.doctorSystemId);
    const reopen = doctor.verificationStatus === 'REJECTED' || doctor.verificationStatus === 'SUSPENDED';
    return (
      <Card style={styles.card}>
        <View style={styles.cardHead}>
          <View style={styles.cardTitles}>
            {openUser ? (
              <LinkText
                label={name} strong
                accessibilityLabel={`${name}, ${doctor.doctorSystemId}, open account`}
                onPress={() => openUser(doctor.doctorSystemId)}
              />
            ) : <Text style={styles.name}>{name}</Text>}
            <Text style={styles.muted} selectable>
              {doctor.doctorSystemId} · Registered {formatClinicDateTime(doctor.doctorCreatedAt, 'on an unknown date')}
            </Text>
          </View>
          <VerificationBadge status={doctor.verificationStatus} />
        </View>

        <View style={styles.facts}>
          <InfoRow label="Email" emptyText="No email address">
            {doctor.email ? (
              <View style={styles.inline}>
                <Text style={styles.value} selectable>{doctor.email}</Text>
                <StatusBadge
                  label={doctor.emailVerified ? 'Confirmed' : 'Not confirmed'}
                  tone={doctor.emailVerified ? 'success' : 'warning'}
                />
              </View>
            ) : undefined}
          </InfoRow>
          <InfoRow label="Phone" value={doctor.phone} />
        </View>
        <ClinicianFacts doctor={doctor} />

        {doctor.verificationStatus === 'APPROVED' && !doctor.isActive ? (
          <Banner tone="info" text="This clinician is approved, but the account is not active. Reactivate it from the account screen." />
        ) : null}

        <View style={styles.actions}>
          {doctor.verificationStatus === 'PENDING' ? (
            <>
              <Button
                label="Approve" icon="checkmark-circle-outline" accessibilityLabel={`Approve ${name}`}
                onPress={() => startApprove(doctor)}
              />
              <Button
                label="Reject" variant="danger" icon="close-circle-outline" accessibilityLabel={`Reject ${name}`}
                onPress={() => { actions.reject.reset(); setDialog({ kind: 'reject', doctor }); }}
              />
            </>
          ) : null}
          {reopen ? (
            <Button
              label="Re-open and approve" icon="checkmark-circle-outline" accessibilityLabel={`Re-open and approve ${name}`}
              onPress={() => startApprove(doctor)}
            />
          ) : null}
          <Button
            label="Edit licence" variant="secondary" icon="create-outline" accessibilityLabel={`Edit licence of ${name}`}
            onPress={() => { actions.saveLicence.reset(); setDialog({ kind: 'licence', doctor, thenApprove: false }); }}
          />
        </View>
      </Card>
    );
  };

  const approveDialog = dialog?.kind === 'approve' ? dialog.doctor : null;
  const approveName = approveDialog ? fullName(approveDialog, approveDialog.doctorSystemId) : '';
  const approveLead = approveDialog?.verificationStatus === 'REJECTED'
    ? 'This re-opens a registration that was rejected. '
    : approveDialog?.verificationStatus === 'SUSPENDED'
      ? 'This reactivates a suspended clinician account. '
      : '';

  return (
    <Screen
      title="Clinician verification"
      subtitle="Check each clinician's licence before they can sign in. The registry check is information; the decision is yours."
      theme={theme}
    >
      <ActionNoticeBanner notice={notice} onDismiss={() => setNotice(null)} />

      <Tabs<VerificationStatus>
        tabs={tabs}
        active={status}
        label="Verification status"
        onChange={key => setParams({ tab: key === 'PENDING' ? undefined : key })}
      />

      <PagedList<AdminDoctor>
        state={doctors}
        keyOf={doctor => doctor.doctorSystemId}
        renderItem={renderDoctor}
        label={`Clinicians: ${TAB_LABEL[status]}`}
        empty={{ icon: 'shield-checkmark-outline', ...EMPTY[status] }}
      />

      {approveDialog ? (
        <ConfirmDialog
          title={approveLead ? 'Re-open and approve' : 'Approve clinician'}
          message={`${approveLead}${approveName} (${approveDialog.doctorSystemId}) will be able to sign in as a clinician`
            + `${approveDialog.email ? ' and is told by email' : ''}.`}
          note={`Licence ${approveDialog.licenseNo ?? 'not on file'}`
            + `${approveDialog.licensingBody ? `, ${approveDialog.licensingBody}` : ''}. ${registryLabel(approveDialog.registryStatus)}.`}
          confirmLabel={approveLead ? 'Re-open and approve' : 'Approve'}
          pending={actions.approve.pending}
          error={actions.approve.error}
          onConfirm={() => { void confirmApprove(approveDialog); }}
          onClose={() => setDialog(null)}
        />
      ) : null}

      {dialog?.kind === 'reject' ? (
        <ReasonDialog
          title="Reject registration"
          intro={`${fullName(dialog.doctor, dialog.doctor.doctorSystemId)} (${dialog.doctor.doctorSystemId}) will not be able to sign in. `
            + 'A rejection is not final: you can correct the licence details and approve this clinician later.'}
          hint="This reason is included in the email to the clinician, so write it for them."
          submitLabel="Reject registration"
          destructive
          pending={actions.reject.pending}
          error={actions.reject.failure?.fieldErrors?.reason ? null : actions.reject.error}
          fieldError={actions.reject.failure?.fieldErrors?.reason ?? null}
          onSubmit={reason => { void submitReject(dialog.doctor, reason); }}
          onClose={() => setDialog(null)}
        />
      ) : null}

      {dialog?.kind === 'licence' ? (
        <LicenceDialog
          // A new form for each clinician and each purpose, prefilled from the row.
          key={`${dialog.doctor.doctorSystemId}:${dialog.thenApprove}`}
          doctor={dialog.doctor}
          thenApprove={dialog.thenApprove}
          pending={actions.saveLicence.pending || actions.approve.pending}
          onSubmit={request => submitLicence(dialog.doctor, dialog.thenApprove, request)}
          onClose={() => setDialog(null)}
        />
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  dialogText: { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },

  card:       { gap: space.md },
  cardHead:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.sm },
  cardTitles: { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, gap: 2 },
  name:       { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  muted:      { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  value:      { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  facts:      { gap: 0, minWidth: 0 },
  inline:     { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', columnGap: space.sm, rowGap: 2 },
  actions:    { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
});
