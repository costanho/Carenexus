import { zodResolver } from '@hookform/resolvers/zod';
import { type ReactNode, useEffect, useMemo, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Pressable, StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import {
  accessibleName, Banner, Button, Card, colors, EmptyView, type EmptyAction, ErrorView, fontSize, FormActions, FormError,
  type IoniconsName, LoadingView, Modal, NoAccessView, type PagedState, radius, Screen, SelectField, type SelectOption,
  space, StatusBadge, TextField, useUiTheme, webOnly,
} from '@/components/web/ui';
import type { ActionState } from '@/hooks/use-action';
import { useAdminUserActions } from '@/hooks/use-admin-user';
import { useAdminUsers, useCreateAdmin } from '@/hooks/use-admin-users';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useSectionNav } from '@/hooks/use-section-nav';
import {
  accountActions, accountState, accountStateLabel, accountStateTone, ADMIN_ERROR, ADMIN_ROLES, type AdminRole,
  type AdminUser, type AdminUserActionResult, type AdminUserDetail, appointmentImpact, CREATE_ADMIN_FIELDS,
  type CreateAdminForm, createAdminSchema, fullName, REASON_MAX, type ReasonForm, reasonSchema, roleLabel, SEARCH_MAX,
  type VerificationStatus,
} from '@/lib/admin/types';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { ROLE_THEME, type RoleTheme, type SectionParams, type SectionParamsInput, type SectionProps } from '@/lib/nav/sections';
import { formatClinicDateTime } from '@/lib/time/clinic';

// Administrator screen "Users": the account directory with search and filters, creating another administrator,
// and deactivating or reactivating an account. The table, the dialogs and the badges are exported because the
// other administrator screens (user detail, clinician queue, audit log) use the same ones.

// ─── Small shared pieces ──────────────────────────────────────────────────────

/** What an administrator screen needs from the dashboard: its URL params, the theme and links to its sibling screens. */
export interface AdminNav {
  /** URL params of the screen other than `section`. */
  params: SectionParams;
  theme:  RoleTheme;
  /** Updates params of the current screen in place (filters, the selected tab, the opened row). */
  setParams: (params: SectionParamsInput) => void;
  /** Links to the sibling screens. Each is undefined while its section is not part of the dashboard. */
  openUsers?: () => void;
  openUser?:  (systemId: string) => void;
  /** Opens the clinician verification queue, on the tab of this status when given. */
  openQueue?: (status?: VerificationStatus | null) => void;
  /** Opens the audit log, filtered to one actor when given. */
  openAudit?: (filters?: { actor?: string }) => void;
}

/**
 * Screens take `SectionProps` from the section registry; every prop is optional, and what is missing comes from
 * the dashboard's own navigation. A link to a section that is not registered is left out, so no control leads
 * nowhere.
 */
export function useAdminNav(props: Partial<SectionProps>): AdminNav {
  const nav = useSectionNav();
  const params = props.params ?? nav.params;
  const theme = props.theme ?? ROLE_THEME.admin;
  const navigate = props.navigate ?? nav.navigate;
  const { hasSection, setParams } = nav;
  return useMemo<AdminNav>(() => ({
    params,
    theme,
    setParams,
    openUsers: hasSection('users') ? () => navigate('users') : undefined,
    openUser:  hasSection('user-detail') ? (systemId: string) => navigate('user-detail', { id: systemId }) : undefined,
    openQueue: hasSection('clinician-queue')
      ? (status?: VerificationStatus | null) => navigate('clinician-queue', { tab: status ?? undefined })
      : undefined,
    openAudit: hasSection('audit-log')
      ? (filters?: { actor?: string }) => navigate('audit-log', { actor: filters?.actor })
      : undefined,
  }), [params, theme, setParams, hasSection, navigate]);
}

/** The value, `delay` milliseconds after it last changed. */
export function useDebounced<T>(value: T, delay = 350): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);
  return settled;
}

/** Text that opens something (another screen, a panel). Reads as a link and works from the keyboard. */
export function LinkText({ label, onPress, accessibilityLabel, strong = false }: {
  label:   string;
  onPress: () => void;
  /** Extra context after the visible words, for example the person the link is about. */
  accessibilityLabel?: string;
  strong?: boolean;
}) {
  const { accent } = useUiTheme();
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="link"
      accessibilityLabel={accessibleName(label, accessibilityLabel)}
      style={styles.link}
    >
      <Text style={[styles.linkText, strong && styles.linkStrong, { color: accent }]}>{label}</Text>
    </Pressable>
  );
}

/** A message about the last action, shown above the list until it is dismissed. */
export interface ActionNotice {
  tone:    'success' | 'info' | 'warning';
  text:    string;
  /** A second line with a consequence worth reading (for example appointments that will be cancelled). */
  detail?: string | null;
  action?: { label: string; onPress: () => void };
}

export function ActionNoticeBanner({ notice, onDismiss }: { notice: ActionNotice | null; onDismiss: () => void }) {
  if (!notice) return null;
  return (
    <View style={styles.notices}>
      <Banner tone={notice.tone} text={notice.text} action={notice.action} onDismiss={onDismiss} />
      {notice.detail ? <Banner tone="warning" text={notice.detail} /> : null}
    </View>
  );
}

// ─── Table that stacks to cards ───────────────────────────────────────────────

export interface DataColumn<T> {
  key:    string;
  /** Column heading. */
  header: string;
  /** Relative width. Default 1. */
  flex?:  number;
  render: (row: T) => ReactNode;
}

export interface DataTableProps<T> {
  /** The result of `usePaged`. */
  state:   PagedState<T>;
  columns: DataColumn<T>[];
  keyOf:   (row: T) => string | number;
  /** Names the table for assistive technology, for example "Accounts". */
  label:   string;
  /** One row as a card, used when the table does not fit (phones, narrow windows). */
  renderCard: (row: T) => ReactNode;
  empty:   { icon: IoniconsName; title: string; sub?: string; action?: EmptyAction | ReactNode };
  /** Below this available width the rows are stacked as cards. Default 760. */
  stackBelow?: number;
  loadMoreLabel?: string;
  noAccessMessage?: string;
}

/**
 * A paged table with real table semantics (table, row, columnheader, cell) that turns into a list of cards when
 * the space is too narrow for columns, so nothing scrolls sideways at 375 px. Renders every state of the list:
 * loading, failed with Retry, no access, empty, and rows with a count and "Load more".
 */
export function DataTable<T>({
  state, columns, keyOf, label, renderCard, empty, stackBelow = 760, loadMoreLabel = 'Load more', noAccessMessage,
}: DataTableProps<T>) {
  const { width: windowWidth } = useWindowDimensions();
  // The space the table really has (the window minus the sidebar and padding), known after the first layout.
  const [measured, setMeasured] = useState<number | null>(null);
  const stacked = (measured ?? windowWidth) < stackBelow;
  const { items, loading, loadingMore, refreshing, error, errorStatus, hasMore, total, loadMore, refetch } = state;

  let body: ReactNode;
  if (loading) {
    body = <LoadingView compact />;
  } else if (error && items.length === 0) {
    body = errorStatus === 403
      ? <NoAccessView message={noAccessMessage} compact />
      : <ErrorView message={error} onRetry={refetch} compact />;
  } else if (items.length === 0) {
    body = <EmptyView icon={empty.icon} title={empty.title} sub={empty.sub} action={empty.action} />;
  } else {
    // A row can arrive on two pages when rows are added between requests; a key must appear once.
    const seen = new Set<string | number>();
    const rows = items.filter(item => {
      const key = keyOf(item);
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
    const shown = rows.length;
    const count = total > shown ? `Showing ${shown} of ${total}` : `${shown} ${shown === 1 ? 'item' : 'items'}`;
    body = (
      <>
        {error ? <Banner tone="danger" text={error} action={{ label: 'Retry', onPress: refetch }} /> : null}
        {stacked ? (
          <View style={styles.cards} role="list" aria-label={label} aria-busy={refreshing || loadingMore}>
            {rows.map(row => (
              <View key={keyOf(row)} role="listitem" style={styles.cardItem}>{renderCard(row)}</View>
            ))}
          </View>
        ) : (
          <View style={styles.table} role="table" aria-label={label} aria-busy={refreshing || loadingMore}>
            <View role="rowgroup">
              <View role="row" style={styles.headRow}>
                {columns.map(column => (
                  <View key={column.key} role="columnheader" style={[styles.cell, { flex: column.flex ?? 1 }]}>
                    <Text style={styles.headText}>{column.header}</Text>
                  </View>
                ))}
              </View>
            </View>
            <View role="rowgroup">
              {rows.map(row => (
                <View key={keyOf(row)} role="row" style={styles.bodyRow}>
                  {columns.map(column => (
                    <View key={column.key} role="cell" style={[styles.cell, { flex: column.flex ?? 1 }]}>
                      {column.render(row)}
                    </View>
                  ))}
                </View>
              ))}
            </View>
          </View>
        )}
        <View style={styles.footer}>
          <Text style={styles.count} role="status" aria-live="polite">{count}</Text>
          {hasMore ? (
            <Button label={loadMoreLabel} variant="secondary" icon="chevron-down" onPress={loadMore} loading={loadingMore} />
          ) : null}
        </View>
      </>
    );
  }

  return (
    <View style={styles.tableWrap} onLayout={e => setMeasured(Math.round(e.nativeEvent.layout.width))}>
      {body}
    </View>
  );
}

// ─── Dialogs ──────────────────────────────────────────────────────────────────

/** A yes-or-no question before an action that changes an account. Stays open, with the error, when it fails. */
export function ConfirmDialog({
  title, message, note, confirmLabel, destructive = false, pending, error, onConfirm, onClose, children,
}: {
  title:    string;
  message:  string;
  /** A second paragraph: what the action does not do, or what follows from it. */
  note?:    string | null;
  confirmLabel: string;
  destructive?: boolean;
  pending:  boolean;
  error?:   string | null;
  onConfirm: () => void;
  onClose:  () => void;
  /** Extra content under the error, for example a link to where the problem can be fixed. */
  children?: ReactNode;
}) {
  return (
    <Modal
      open
      title={title}
      size="sm"
      onClose={() => onClose()}
      busy={pending}
      footer={(
        <FormActions
          submitLabel={confirmLabel} onSubmit={onConfirm} onCancel={onClose} submitting={pending} destructive={destructive}
        />
      )}
    >
      <Text style={styles.dialogText}>{message}</Text>
      {note ? <Text style={styles.dialogNote}>{note}</Text> : null}
      <FormError message={error} />
      {children}
    </Modal>
  );
}

/** Asks for the reason of a decision (deactivate, reject): required, at most 500 characters. */
export function ReasonDialog({
  title, intro, label = 'Reason', hint, submitLabel, destructive = false, pending, error, fieldError, onSubmit, onClose,
}: {
  title:   string;
  /** What the decision does, above the field. */
  intro?:  string;
  label?:  string;
  hint?:   string;
  submitLabel:  string;
  destructive?: boolean;
  pending: boolean;
  /** A server message that is not about the reason itself. */
  error?:  string | null;
  /** The server's message about the reason. */
  fieldError?: string | null;
  onSubmit: (reason: string) => void;
  onClose:  () => void;
}) {
  const { control, handleSubmit } = useForm<ReasonForm>({
    resolver: zodResolver(reasonSchema),
    defaultValues: { reason: '' },
  });
  const submit = handleSubmit(values => onSubmit(values.reason.trim()));
  return (
    <Modal
      open
      title={title}
      onClose={() => onClose()}
      dismissOnBackdrop={false}
      busy={pending}
      footer={(
        <FormActions
          submitLabel={submitLabel} onSubmit={() => { void submit(); }} onCancel={onClose}
          submitting={pending} destructive={destructive}
        />
      )}
    >
      {intro ? <Text style={styles.dialogText}>{intro}</Text> : null}
      <TextField
        label={label} control={control} name="reason" required multiline rows={4}
        maxLength={REASON_MAX} showCount hint={hint} error={fieldError ?? undefined}
      />
      <FormError message={error} />
    </Modal>
  );
}

/** Deactivates an account after a reason was given. Both protections (own account, last administrator) show here. */
export function DeactivateAccountDialog({ user, action, onDone, onClose }: {
  user:    AdminUser;
  action:  ActionState<[string, string], AdminUserActionResult>;
  onDone:  (result: AdminUserActionResult) => void;
  onClose: () => void;
}) {
  const { reset } = action;
  useEffect(() => { reset(); }, [reset]);
  const name = fullName(user, user.systemId);
  const reasonError = action.failure?.fieldErrors?.reason ?? null;
  const intro = `${name} (${user.systemId}) will be signed out everywhere and cannot sign in until the account is reactivated.`
    + (user.role === 'DOCTOR'
      ? ' An approved clinician is suspended, and their upcoming appointments are cancelled and the patients informed.'
      : '');
  return (
    <ReasonDialog
      title="Deactivate account"
      intro={intro}
      hint="Shown to administrators only. It is never sent to the user."
      submitLabel="Deactivate account"
      destructive
      pending={action.pending}
      error={reasonError ? null : action.error}
      fieldError={reasonError}
      onSubmit={async reason => {
        const result = await action.run(user.systemId, reason);
        if (result.ok) onDone(result.data);
      }}
      onClose={onClose}
    />
  );
}

/** Confirms reactivating a deactivated account, or re-opening one that its owner closed. */
export function ActivateAccountDialog({ user, action, onDone, onClose, onOpenQueue }: {
  user:    AdminUser;
  action:  ActionState<[string], AdminUserActionResult>;
  onDone:  (result: AdminUserActionResult) => void;
  onClose: () => void;
  /** Opens the clinician verification queue; offered when the server answers that the clinician is not verified. */
  onOpenQueue?: () => void;
}) {
  const { reset } = action;
  useEffect(() => { reset(); }, [reset]);
  const name = fullName(user, user.systemId);
  const reopen = user.closedAt !== null;
  const message = reopen
    ? `${name} (${user.systemId}) closed this account on ${formatClinicDateTime(user.closedAt, 'an earlier date')}. `
      + 'Re-opening it lets them sign in again with their existing password.'
    : `${name} (${user.systemId}) will be able to sign in again.`;
  const note = 'Appointments that were cancelled and caregiver access that was revoked are not restored.'
    + (user.role === 'DOCTOR' && user.verificationStatus === 'SUSPENDED' ? ' The clinician returns to Approved.' : '');
  const notVerified = action.failure?.code === ADMIN_ERROR.DOCTOR_NOT_VERIFIED;
  return (
    <ConfirmDialog
      title={reopen ? 'Re-open account' : 'Reactivate account'}
      message={message}
      note={note}
      confirmLabel={reopen ? 'Re-open account' : 'Reactivate account'}
      pending={action.pending}
      error={action.error}
      onConfirm={async () => {
        const result = await action.run(user.systemId);
        if (result.ok) onDone(result.data);
      }}
      onClose={onClose}
    >
      {notVerified && onOpenQueue ? (
        <Button label="Open verification queue" variant="secondary" icon="shield-checkmark-outline" onPress={onOpenQueue} />
      ) : null}
    </ConfirmDialog>
  );
}

/** The notice to show after an account action: the server's sentence, plus the appointment impact of a deactivation. */
export function accountActionNotice(result: AdminUserActionResult, deactivation: boolean): ActionNotice {
  return {
    tone: 'success',
    text: `${fullName(result, result.systemId)} (${result.systemId}): ${result.message}`,
    detail: deactivation && !result.isActive ? appointmentImpact(result) : null,
  };
}

/** State of an account in words and colour, with the flags an administrator should notice. */
export function AccountBadges({ user, isSelf = false }: { user: AdminUser; isSelf?: boolean }) {
  const state = accountState(user);
  return (
    <View style={styles.badges}>
      <StatusBadge label={accountStateLabel(state)} tone={accountStateTone(state)} />
      {user.mustChangePassword ? <StatusBadge label="Temporary password" tone="info" icon="key-outline" /> : null}
      {user.email && !user.emailVerified ? <StatusBadge label="Email not verified" tone="warning" icon="mail-unread-outline" /> : null}
      {isSelf ? <StatusBadge label="You" tone="neutral" icon="person-outline" /> : null}
    </View>
  );
}

// ─── Create administrator ─────────────────────────────────────────────────────

function CreateAdminDialog({ onCreated, onClose }: {
  onCreated: (account: AdminUserDetail) => void;
  onClose:   () => void;
}) {
  // Mounted once per opening of the dialog, so the hook's Idempotency-Key lives exactly that long.
  const create = useCreateAdmin();
  const { control, handleSubmit, setError } = useForm<CreateAdminForm>({
    resolver: zodResolver(createAdminSchema),
    defaultValues: { firstName: '', lastName: '', email: '', phone: '', password: '' },
  });
  const [formError, setFormError] = useState<string | null>(null);
  const [showPassword, setShowPassword] = useState(false);

  const submit = handleSubmit(async values => {
    setFormError(null);
    const result = await create.run({
      firstName: values.firstName, lastName: values.lastName, email: values.email,
      password: values.password, phone: values.phone ? values.phone : null,
    });
    if (result.ok) {
      create.renewKey();
      onCreated(result.data);
      return;
    }
    const failure = result.error;
    if (!failure) return; // a second press while the first request is still running
    if (failure.status === 409 && !failure.code) {
      // "Email already registered" and "Phone number already registered" belong under their fields.
      if (/^email\b/i.test(failure.message)) {
        setError('email', { type: 'server', message: failure.message }, { shouldFocus: true });
        return;
      }
      if (/^phone\b/i.test(failure.message)) {
        setError('phone', { type: 'server', message: failure.message }, { shouldFocus: true });
        return;
      }
    }
    setFormError(applyFieldErrors(failure, setError, CREATE_ADMIN_FIELDS));
  });

  return (
    <Modal
      open
      title="Create administrator"
      onClose={() => onClose()}
      dismissOnBackdrop={false}
      busy={create.pending}
      footer={(
        <FormActions
          submitLabel="Create administrator" submitIcon="person-add-outline"
          onSubmit={() => { void submit(); }} onCancel={onClose} submitting={create.pending}
        />
      )}
    >
      <Text style={styles.dialogText}>
        The new administrator signs in with the temporary password you set here and must choose their own password
        at the first sign-in. No email is sent: pass the temporary password on over a safe channel.
      </Text>
      <View style={styles.formRow}>
        <View style={styles.formCol}>
          <TextField label="First name" control={control} name="firstName" required maxLength={100} autoComplete="off" />
        </View>
        <View style={styles.formCol}>
          <TextField label="Last name" control={control} name="lastName" required maxLength={100} autoComplete="off" />
        </View>
      </View>
      <TextField
        label="Email" control={control} name="email" required maxLength={150}
        inputMode="email" autoCapitalize="none" autoComplete="off"
      />
      <TextField
        label="Phone" control={control} name="phone" maxLength={20} inputMode="tel" autoComplete="off"
        hint="Optional. 7 to 15 digits; may start with + and contain spaces, dots, brackets or hyphens."
      />
      <TextField
        label="Temporary password" control={control} name="password" required secure={!showPassword}
        maxLength={72} autoCapitalize="none" autoComplete="new-password"
        hint="At least 8 characters with at least one letter and one number."
      />
      <Button
        label={showPassword ? 'Hide password' : 'Show password'}
        icon={showPassword ? 'eye-off-outline' : 'eye-outline'}
        variant="ghost" size="sm"
        onPress={() => setShowPassword(v => !v)}
      />
      <FormError message={formError} />
    </Modal>
  );
}

// ─── The screen ───────────────────────────────────────────────────────────────

type StatusFilter = 'active' | 'inactive';

const ROLE_OPTIONS: SelectOption<AdminRole>[] = ADMIN_ROLES.map(role => ({ value: role, label: roleLabel(role) }));
const STATUS_OPTIONS: SelectOption<StatusFilter>[] = [
  { value: 'active',   label: 'Active' },
  { value: 'inactive', label: 'Inactive' },
];

function parseRole(value: string | undefined): AdminRole | '' {
  const key = (value ?? '').trim().toUpperCase();
  return (ADMIN_ROLES as readonly string[]).includes(key) ? (key as AdminRole) : '';
}

function parseStatus(value: string | undefined): StatusFilter | '' {
  return value === 'active' || value === 'inactive' ? value : '';
}

type Dialog =
  | { kind: 'create' }
  | { kind: 'deactivate'; user: AdminUser }
  | { kind: 'activate'; user: AdminUser };

/**
 * Accounts of every role, newest first. The search text and the two filters live in the URL (`q`, `role`,
 * `status`), so a refresh or a shared link shows the same list.
 */
export default function AdminUsers(props: Partial<SectionProps>) {
  const { params, theme, setParams, openUser, openQueue } = useAdminNav(props);

  const { user: me } = useCurrentUser();
  const selfId = me?.system_id ?? null;

  const q = (params.q ?? '').slice(0, SEARCH_MAX);
  const role = parseRole(params.role);
  const status = parseStatus(params.status);
  const active = status === 'active' ? true : status === 'inactive' ? false : undefined;

  // The box shows what is typed at once; the list follows a moment after typing stops.
  const [search, setSearch] = useState(q);
  const settled = useDebounced(search, 350);
  // The last search text the box and the URL agreed on. It tells a change the box made (which must not reset the
  // box) from one that came from outside, such as a link that lands on this section with another `q`.
  const written = useRef(q);
  useEffect(() => {
    const next = settled.trim();
    if (next === written.current) return;
    written.current = next;
    setParams({ q: next || undefined });
  }, [settled, setParams]);
  useEffect(() => {
    if (q === written.current) return;
    written.current = q;
    setSearch(q);
  }, [q]);

  const users = useAdminUsers({ q, role, active });
  const actions = useAdminUserActions();
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [notice, setNotice] = useState<ActionNotice | null>(null);

  const filtered = q !== '' || role !== '' || status !== '';
  const clearFilters = () => {
    setSearch('');
    setParams({ q: undefined, role: undefined, status: undefined });
  };

  const viewAccount = (systemId: string) => (openUser ? { label: 'View account', onPress: () => openUser(systemId) } : undefined);

  const applyResult = (result: AdminUserActionResult, deactivation: boolean) => {
    setDialog(null);
    // The response is the account after the action. A row that no longer matches the Status filter leaves the list.
    if (active === undefined || result.isActive === active) {
      users.setItems(prev => prev.map(row => (row.userId === result.userId ? result : row)));
    } else {
      users.refetch();
    }
    setNotice({
      ...accountActionNotice(result, deactivation),
      action: viewAccount(result.systemId),
    });
  };

  const onCreated = (account: AdminUserDetail) => {
    setDialog(null);
    users.refetch();
    setNotice({
      tone: 'success',
      text: `Administrator account ${account.systemId} was created for ${fullName(account, account.email ?? account.systemId)}. `
        + 'Pass the temporary password on over a safe channel; it must be changed at the first sign-in.',
      action: viewAccount(account.systemId),
    });
  };

  const rowActions = (user: AdminUser) => {
    const can = accountActions(user, selfId);
    const name = fullName(user, user.systemId);
    const toQueue = can.needsVerification && openQueue ? () => openQueue(user.verificationStatus) : null;
    if (!can.canDeactivate && !can.canActivate && !toQueue) return null;
    return (
      <View style={styles.rowActions}>
        {can.canDeactivate ? (
          <Button
            label="Deactivate" variant="secondary" size="sm" icon="ban-outline"
            accessibilityLabel={`Deactivate ${name}`}
            onPress={() => setDialog({ kind: 'deactivate', user })}
          />
        ) : null}
        {can.canActivate ? (
          <Button
            label={can.isReopen ? 'Re-open account' : 'Reactivate'} variant="secondary" size="sm" icon="refresh-outline"
            accessibilityLabel={can.isReopen ? `Re-open account of ${name}` : `Reactivate ${name}`}
            onPress={() => setDialog({ kind: 'activate', user })}
          />
        ) : null}
        {toQueue ? (
          <Button
            label="Verification queue" variant="ghost" size="sm" icon="shield-checkmark-outline"
            accessibilityLabel={`Verification queue, ${name}`}
            onPress={toQueue}
          />
        ) : null}
      </View>
    );
  };

  const nameLink = (user: AdminUser) => {
    const name = fullName(user, user.systemId);
    if (!openUser) return <Text style={styles.name}>{name}</Text>;
    return (
      <LinkText
        label={name} strong
        accessibilityLabel={`${name}, ${user.systemId}, open account`}
        onPress={() => openUser(user.systemId)}
      />
    );
  };

  const columns: DataColumn<AdminUser>[] = [
    {
      key: 'account', header: 'Account', flex: 2.2,
      render: user => (
        <View style={styles.stack}>
          {nameLink(user)}
          <Text style={styles.muted} selectable>{user.systemId}</Text>
        </View>
      ),
    },
    {
      key: 'contact', header: 'Email', flex: 2.4,
      render: user => (
        <Text style={[styles.value, !user.email && styles.missing]} selectable>{user.email ?? 'No email address'}</Text>
      ),
    },
    { key: 'role', header: 'Role', flex: 1.2, render: user => <Text style={styles.value}>{roleLabel(user.role)}</Text> },
    {
      key: 'status', header: 'Status', flex: 2,
      render: user => <AccountBadges user={user} isSelf={user.systemId === selfId} />,
    },
    {
      key: 'created', header: 'Created', flex: 1.8,
      render: user => <Text style={styles.value}>{formatClinicDateTime(user.createdAt, 'Unknown')}</Text>,
    },
    { key: 'actions', header: 'Actions', flex: 2, render: user => rowActions(user) },
  ];

  const renderCard = (user: AdminUser) => (
    <Card style={styles.card}>
      <View style={styles.stack}>
        {nameLink(user)}
        <Text style={styles.muted} selectable>{user.systemId} · {roleLabel(user.role)}</Text>
        <Text style={[styles.value, !user.email && styles.missing]} selectable>{user.email ?? 'No email address'}</Text>
        <Text style={styles.muted}>Created {formatClinicDateTime(user.createdAt, 'on an unknown date')}</Text>
      </View>
      <AccountBadges user={user} isSelf={user.systemId === selfId} />
      {rowActions(user)}
    </Card>
  );

  return (
    <Screen
      title="Users"
      subtitle="Every CareNexus account. Search, review an account, or deactivate and reactivate it."
      theme={theme}
      actions={(
        <Button label="Create administrator" icon="person-add-outline" onPress={() => setDialog({ kind: 'create' })} />
      )}
    >
      <ActionNoticeBanner notice={notice} onDismiss={() => setNotice(null)} />

      <View style={styles.filters} {...webOnly({ role: 'search', 'aria-label': 'Filter accounts' })}>
        <View style={styles.filterWide}>
          <TextField
            label="Search" value={search} onChange={setSearch} maxLength={SEARCH_MAX}
            placeholder="Name, email or account ID" inputMode="search" autoCapitalize="none" autoComplete="off"
          />
        </View>
        <View style={styles.filter}>
          <SelectField<AdminRole>
            label="Role" value={role} options={ROLE_OPTIONS} allowEmpty placeholder="All roles"
            onChange={value => setParams({ role: value || undefined })}
          />
        </View>
        <View style={styles.filter}>
          <SelectField<StatusFilter>
            label="Status" value={status} options={STATUS_OPTIONS} allowEmpty placeholder="Active and inactive"
            onChange={value => setParams({ status: value || undefined })}
          />
        </View>
        {filtered ? (
          <Button label="Clear filters" variant="ghost" icon="close-circle-outline" onPress={clearFilters} style={styles.clear} />
        ) : null}
      </View>

      <DataTable<AdminUser>
        state={users}
        columns={columns}
        keyOf={user => user.userId}
        label="Accounts"
        renderCard={renderCard}
        stackBelow={900}
        empty={filtered
          ? { icon: 'search-outline', title: 'No accounts match these filters', sub: 'Change the search text or clear the filters.', action: { label: 'Clear filters', onPress: clearFilters } }
          : { icon: 'people-outline', title: 'No accounts yet' }}
      />

      {dialog?.kind === 'create' ? (
        <CreateAdminDialog onCreated={onCreated} onClose={() => setDialog(null)} />
      ) : null}
      {dialog?.kind === 'deactivate' ? (
        <DeactivateAccountDialog
          user={dialog.user} action={actions.deactivate}
          onDone={result => applyResult(result, true)} onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog?.kind === 'activate' ? (
        <ActivateAccountDialog
          user={dialog.user} action={actions.activate}
          onDone={result => applyResult(result, false)} onClose={() => setDialog(null)}
          onOpenQueue={openQueue ? () => { const status = dialog.user.verificationStatus; setDialog(null); openQueue(status); } : undefined}
        />
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  link:       { alignSelf: 'flex-start', maxWidth: '100%' },
  linkText:   { fontSize: fontSize.md, fontWeight: '500', textDecorationLine: 'underline' },
  linkStrong: { fontWeight: '700' },

  notices: { gap: space.sm },

  tableWrap: { gap: space.md, minWidth: 0 },
  table: {
    backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.border, borderRadius: radius.lg,
    overflow: 'hidden', minWidth: 0,
  },
  headRow: { flexDirection: 'row', backgroundColor: colors.bgSubtle, borderBottomWidth: 1, borderBottomColor: colors.border },
  bodyRow: { flexDirection: 'row', borderTopWidth: 1, borderTopColor: colors.border },
  cell:    { flexBasis: 0, minWidth: 0, paddingHorizontal: space.md, paddingVertical: space.md, justifyContent: 'center' },
  headText: { fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted, textTransform: 'uppercase', letterSpacing: 0.4 },
  cards:    { gap: space.sm, minWidth: 0 },
  cardItem: { minWidth: 0 },
  footer:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  count:    { fontSize: fontSize.sm, color: colors.textMuted },

  dialogText: { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },
  dialogNote: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  formRow:    { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  formCol:    { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0 },

  badges: { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs, minWidth: 0 },

  filters:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-end', gap: space.md },
  filterWide: { flexGrow: 2, flexShrink: 1, flexBasis: 260, minWidth: 0 },
  filter:     { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0 },
  clear:      { alignSelf: 'flex-end' },

  card:       { gap: space.md },
  stack:      { gap: 2, minWidth: 0 },
  name:       { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  value:      { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  missing:    { color: colors.textSubtle },
  muted:      { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  rowActions: { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs, minWidth: 0 },
});
