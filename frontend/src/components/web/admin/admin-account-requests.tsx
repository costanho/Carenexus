import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, Button, Card, colors, ErrorView, fontSize, FormActions, FormError, InfoRow, LoadingView,
  PagedList, Screen, SelectField, SidePanel, space, StatusBadge, Tabs, TextField, type SelectOption, type TabItem,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { useAdminAccountRequest, useAdminAccountRequests } from '@/hooks/use-admin-ops-account-requests';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useSectionNav } from '@/hooks/use-section-nav';
import { approveAccountRequest, rejectAccountRequest, type AccountRequestFilter } from '@/lib/admin/ops-api';
import {
  ACCOUNT_REQUEST_STATUS_LABEL, ACCOUNT_REQUEST_STATUSES, ACCOUNT_REQUEST_TYPE_LABEL, ACCOUNT_REQUEST_TYPES,
  accountRequestName, accountRequestState, accountRoleLabel, approveDeletionConfirmText, DELETION_STATEMENT,
  formatInstant, isAccountRequestStatus, isAccountRequestType, isDecidable, rejectAccountRequestSchema,
  type AccountRequestStatus, type AccountRequestType, type AdminAccountRequest, type RejectAccountRequestValues,
} from '@/lib/admin/ops-types';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { ROLE_THEME, type SectionProps } from '@/lib/nav/sections';
import { confirmAction } from '@/lib/ui/dialogs';

// Admin section `account-requests`: data exports, account closures and deletion requests of every user. Only a
// deletion request that is still waiting needs a decision; exports and closures are listed as history.
// `?id=<requestId>` (the link of the admin notification) opens that request's panel.

type RequestsTab = 'queue' | 'all';

const TABS: readonly TabItem<RequestsTab>[] = [
  { key: 'queue', label: 'Needs a decision' },
  { key: 'all',   label: 'All requests' },
];

type TypeChoice = AccountRequestType | 'ALL';
type StatusChoice = AccountRequestStatus | 'ALL';

const TYPE_OPTIONS: readonly SelectOption<TypeChoice>[] = [
  { value: 'ALL', label: 'All types' },
  ...ACCOUNT_REQUEST_TYPES.map(type => ({ value: type, label: `${ACCOUNT_REQUEST_TYPE_LABEL[type]}s` })),
];

const STATUS_OPTIONS: readonly SelectOption<StatusChoice>[] = [
  { value: 'ALL', label: 'All statuses' },
  ...ACCOUNT_REQUEST_STATUSES.map(status => ({ value: status, label: ACCOUNT_REQUEST_STATUS_LABEL[status] })),
];

const QUEUE_FILTER: AccountRequestFilter = { type: 'DELETION', status: 'REQUESTED' };

function parseId(value: string | undefined): number | null {
  if (!value || !/^\d+$/.test(value)) return null;
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

function RequestRow({ request, onOpen }: { request: AdminAccountRequest; onOpen: () => void }) {
  const state = accountRequestState(request);
  const name = accountRequestName(request);
  const decidable = isDecidable(request);
  const typeLabel = ACCOUNT_REQUEST_TYPE_LABEL[request.type] ?? request.type;
  return (
    <Card style={styles.row}>
      <View style={styles.rowHead}>
        <View style={styles.rowTitles}>
          <Text style={styles.rowTitle} accessibilityRole="header" {...ariaLevel(2)}>{`${typeLabel}: ${name}`}</Text>
          <Text style={styles.rowSub}>
            {[accountRoleLabel(request.role), request.systemId, request.email].filter(Boolean).join(' · ')}
          </Text>
        </View>
        <StatusBadge label={state.label} tone={state.tone} />
      </View>
      <Text style={styles.meta}>{`Requested ${formatInstant(request.requestedAt, 'at an unknown time')}`}</Text>
      {request.reason ? (
        <Text style={styles.reason} numberOfLines={2}>{`Reason given: ${request.reason}`}</Text>
      ) : null}
      <View style={styles.rowActions}>
        <Button
          label={decidable ? 'Review' : 'View details'}
          icon={decidable ? 'clipboard-outline' : 'eye-outline'}
          variant={decidable ? 'primary' : 'secondary'}
          size="sm"
          onPress={onOpen}
          accessibilityLabel={`${decidable ? 'Review' : 'View details of'} the ${typeLabel.toLowerCase()} of ${name}`}
        />
      </View>
    </Card>
  );
}

function RequestPanel({ requestId, ownSystemId, onClose, onChanged }: {
  requestId:   number;
  /** System id of the signed-in administrator, who may not decide their own request. */
  ownSystemId: string | null;
  onClose:     () => void;
  onChanged:   (next: AdminAccountRequest) => void;
}) {
  const detail = useAdminAccountRequest(requestId);
  const request = detail.data;

  const [notice, setNotice] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const approve = useAction(() => approveAccountRequest(requestId), { errorMessage: 'The request could not be approved.' });
  const reject = useAction((reason: string) => rejectAccountRequest(requestId, reason), {
    errorMessage: 'The request could not be rejected.',
  });
  const { control, handleSubmit, setError, reset } = useForm<RejectAccountRequestValues>({
    resolver: zodResolver(rejectAccountRequestSchema),
    defaultValues: { reason: '' },
  });
  const busy = approve.pending || reject.pending;

  const applied = (next: AdminAccountRequest, message: string) => {
    detail.setData(next);
    onChanged(next);
    setRejecting(false);
    setFormError(null);
    reset({ reason: '' });
    setNotice(message);
  };

  const onApprove = async () => {
    if (!request) return;
    const confirmed = await confirmAction(
      `Approve the deletion request of ${accountRequestName(request)}?`,
      approveDeletionConfirmText(request.role),
      'Approve and anonymise',
      true,
    );
    if (!confirmed) return;
    setNotice(null);
    const result = await approve.run();
    if (result.ok) {
      applied(result.data, 'The account was anonymised. Clinical records were kept.');
    } else if (result.error && (result.error.status === 409 || result.error.status === 404)) {
      // Someone else decided it, or the user withdrew it: show what is true now.
      detail.refetch();
    }
  };

  const onReject = handleSubmit(async ({ reason }) => {
    setFormError(null);
    setNotice(null);
    const result = await reject.run(reason.trim());
    if (result.ok) {
      applied(result.data, 'The request was rejected. The user was told, with your reason.');
    } else if (result.error) {
      setFormError(applyFieldErrors(result.error, setError, ['reason']));
      if (result.error.status === 409 || result.error.status === 404) {
        setRejecting(false);
        detail.refetch();
      }
    }
  });

  const cancelReject = () => {
    setRejecting(false);
    setFormError(null);
    reject.reset();
    reset({ reason: '' });
  };

  let body;
  if (detail.loading) {
    body = <LoadingView label="Loading the request…" />;
  } else if (!request) {
    body = <ErrorView message={detail.error ?? 'The account request could not be loaded.'} onRetry={detail.refetch} />;
  } else {
    const state = accountRequestState(request);
    const decidable = isDecidable(request);
    const own = !!ownSystemId && request.systemId === ownSystemId;
    const decided = request.type === 'DELETION';
    body = (
      <>
        {notice ? <Banner tone="success" text={notice} onDismiss={() => setNotice(null)} /> : null}
        {approve.error ? <Banner tone="danger" text={approve.error} onDismiss={approve.reset} /> : null}
        {!rejecting && formError ? <Banner tone="danger" text={formError} onDismiss={() => setFormError(null)} /> : null}
        {detail.error ? <Banner tone="danger" text={detail.error} action={{ label: 'Retry', onPress: detail.refetch }} /> : null}

        <StatusBadge label={state.label} tone={state.tone} />
        <View>
          <InfoRow label="Request" value={ACCOUNT_REQUEST_TYPE_LABEL[request.type] ?? request.type} />
          <InfoRow label="Account" value={accountRequestName(request)} />
          <InfoRow label="Account ID" value={request.systemId} />
          <InfoRow label="Role" value={accountRoleLabel(request.role)} />
          <InfoRow label="Email" value={request.email} emptyText="None on file" />
          <InfoRow label="Account status" value={request.isActive ? 'Active' : 'Inactive (cannot sign in)'} />
          <InfoRow label="Requested" value={formatInstant(request.requestedAt)} emptyText="Unknown" />
          {request.type !== 'EXPORT' ? (
            <InfoRow label="Reason given" value={request.reason} emptyText="None given" />
          ) : null}
          {request.processedAt ? (
            <InfoRow label={decided ? 'Decided' : 'Processed'} value={formatInstant(request.processedAt)} />
          ) : null}
          {request.processedBySystemId ? (
            <InfoRow label={decided ? 'Decided by' : 'Processed by'} value={request.processedBySystemId} />
          ) : null}
          {request.decisionReason ? <InfoRow label="Reason for rejection" value={request.decisionReason} /> : null}
          {request.type === 'EXPORT' ? (
            <>
              <InfoRow label="File" value={request.fileName} emptyText="Not available" />
              <InfoRow label="Download expires" value={formatInstant(request.expiresAt)} emptyText="Not set" />
            </>
          ) : null}
        </View>

        {request.type === 'EXPORT' ? (
          <Text style={styles.note}>Only the user can download their export. Administrators cannot open the file.</Text>
        ) : null}
        {request.type === 'CLOSURE' ? (
          <Text style={styles.note}>
            The user closed the account themselves. Clinical records were kept. Nothing needs a decision here.
          </Text>
        ) : null}

        {decidable && own ? (
          <Banner tone="warning" text="This is your own request. Another administrator has to decide it." />
        ) : null}

        {decidable && !own ? (
          <View style={styles.decision}>
            <Text style={styles.decisionTitle} accessibilityRole="header" {...ariaLevel(3)}>Decision</Text>
            <Banner tone="warning" text={DELETION_STATEMENT} />
            {rejecting ? (
              <View style={styles.form}>
                <FormError message={formError} />
                <TextField
                  control={control}
                  name="reason"
                  label="Reason for rejecting"
                  required
                  multiline
                  rows={4}
                  maxLength={500}
                  showCount
                  hint="The user sees this reason. The account is not changed."
                  disabled={reject.pending}
                />
                <FormActions
                  submitLabel="Reject request"
                  destructive
                  onSubmit={() => { void onReject(); }}
                  onCancel={cancelReject}
                  cancelLabel="Back"
                  submitting={reject.pending}
                />
              </View>
            ) : (
              <View style={styles.rowActions}>
                <Button
                  label="Approve and anonymise" icon="checkmark-circle-outline" variant="danger"
                  onPress={() => { void onApprove(); }} loading={approve.pending}
                />
                <Button
                  label="Reject" icon="close-circle-outline" variant="secondary"
                  onPress={() => { approve.reset(); setFormError(null); setRejecting(true); }} disabled={busy}
                />
              </View>
            )}
          </View>
        ) : null}
      </>
    );
  }

  return (
    <SidePanel
      open
      title={request ? `${ACCOUNT_REQUEST_TYPE_LABEL[request.type] ?? 'Account request'} #${request.requestId}` : `Account request #${requestId}`}
      onClose={onClose}
      dismissOnBackdrop={!rejecting}
      busy={busy}
    >
      {body}
    </SidePanel>
  );
}

export default function AdminAccountRequests({ theme, params }: Partial<SectionProps>) {
  const nav = useSectionNav();
  const look = theme ?? ROLE_THEME.admin;
  const urlParams = params ?? nav.params;
  const { user } = useCurrentUser();

  const tab: RequestsTab = urlParams.tab === 'all' ? 'all' : 'queue';
  const type = tab === 'all' && isAccountRequestType(urlParams.type) ? urlParams.type : undefined;
  const status = tab === 'all' && isAccountRequestStatus(urlParams.status) ? urlParams.status : undefined;
  const filter: AccountRequestFilter = tab === 'queue' ? QUEUE_FILTER : { type, status };
  const list = useAdminAccountRequests(filter);
  const openId = parseId(urlParams.id);

  const onChanged = (next: AdminAccountRequest) => {
    list.setItems(prev => prev.map(r => (r.requestId === next.requestId ? next : r)));
    // A decided request leaves the queue (and any status filter it no longer matches).
    if (filter.status && next.status !== filter.status) list.refetch();
  };

  const filtered = tab === 'all' && (!!type || !!status);

  return (
    <Screen
      title="Account requests"
      subtitle="Data exports, account closures and deletion requests. Only deletion requests need a decision from an administrator."
      theme={look}
    >
      <Banner tone="info" text={DELETION_STATEMENT} />

      <Tabs<RequestsTab>
        tabs={TABS}
        active={tab}
        label="Account request views"
        onChange={next => nav.setParams({ tab: next === 'all' ? 'all' : undefined, type: undefined, status: undefined })}
      />

      <PagedList
        state={list}
        label={tab === 'queue' ? 'Deletion requests awaiting a decision' : 'Account requests'}
        keyOf={r => r.requestId}
        header={tab === 'all' ? (
          <View style={styles.filters}>
            <View style={styles.filter}>
              <SelectField<TypeChoice>
                label="Type"
                value={type ?? 'ALL'}
                options={TYPE_OPTIONS}
                onChange={value => nav.setParams({ type: value === 'ALL' ? undefined : value })}
              />
            </View>
            <View style={styles.filter}>
              <SelectField<StatusChoice>
                label="Status"
                value={status ?? 'ALL'}
                options={STATUS_OPTIONS}
                onChange={value => nav.setParams({ status: value === 'ALL' ? undefined : value })}
              />
            </View>
          </View>
        ) : null}
        renderItem={request => (
          <RequestRow request={request} onOpen={() => nav.setParams({ id: request.requestId })} />
        )}
        empty={tab === 'queue'
          ? {
              icon: 'checkmark-done-outline',
              title: 'No deletion requests are waiting',
              sub: 'New requests appear here and in your notifications.',
            }
          : filtered
            ? { icon: 'search-outline', title: 'No requests match', sub: 'Change the type or the status filter.' }
            : { icon: 'file-tray-outline', title: 'No account requests yet', sub: 'Exports, closures and deletion requests are listed here once users make them.' }}
      />

      {openId != null ? (
        <RequestPanel
          key={openId}
          requestId={openId}
          ownSystemId={user?.system_id ?? null}
          onClose={() => nav.setParams({ id: undefined })}
          onChanged={onChanged}
        />
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  filters: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.md },
  filter:  { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, maxWidth: 320 },

  row:        { gap: space.sm },
  rowHead:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.sm },
  rowTitles:  { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, gap: 2 },
  rowTitle:   { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  rowSub:     { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  rowActions: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  meta:       { fontSize: fontSize.sm, color: colors.textMuted },
  reason:     { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },

  note:          { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  decision:      { gap: space.md, borderTopWidth: 1, borderTopColor: colors.border, paddingTop: space.md },
  decisionTitle: { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  form:          { gap: space.md },
});
