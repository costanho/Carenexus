import { useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  Button, Card, colors, DateField, ErrorView, fontSize, InfoRow, LoadingView, Screen, SelectField, type SelectOption,
  SidePanel, space, StatusBadge, TextField, webOnly,
} from '@/components/web/ui';
import { useAuditEntry, useAuditLogs } from '@/hooks/use-audit-logs';
import {
  AUDIT_STATUSES, type AuditEntry, type AuditFilters, auditRangeError, type AuditStatus, roleLabel,
} from '@/lib/admin/types';
import type { SectionProps } from '@/lib/nav/sections';
import { clinicToday, formatClinicDateTime } from '@/lib/time/clinic';
import { type DataColumn, DataTable, LinkText, useAdminNav } from './admin-users';

// Administrator screen "Audit log": who did what and when, with filters, paging and a detail panel per row.
// Every request to the audit endpoints is itself written to the log, so filters are applied with a button
// (not while typing) and a row's details are shown from the loaded page without another request.

// ─── Shared with the user detail screen ───────────────────────────────────────

/** "Rudo Moyo" / the account ID / "Not signed in" (for example a failed sign-in). */
export function auditActorName(entry: AuditEntry): string {
  return entry.actorName?.trim() || entry.systemId || 'Not signed in';
}

/** "LAB_RESULT #311", "ACCOUNT", or an empty string when the row names no resource. */
export function auditResourceText(entry: AuditEntry): string {
  const type = entry.resourceType?.trim() ?? '';
  if (entry.resourceId === null || entry.resourceId === undefined) return type;
  return type ? `${type} #${entry.resourceId}` : `#${entry.resourceId}`;
}

/** Whom the row is about: the patient whose data was touched, or the account of an account event. */
export function auditSubjectText(entry: AuditEntry): string {
  const parts: string[] = [];
  if (entry.patientSystemId) parts.push(`Patient ${entry.patientSystemId}`);
  if (entry.targetSystemId) parts.push(`Account ${entry.targetSystemId}`);
  return parts.join(', ');
}

export function AuditStatusBadge({ status }: { status: string }) {
  return status === 'FAILURE'
    ? <StatusBadge label="Failure" tone="danger" />
    : <StatusBadge label="Success" tone="success" />;
}

/** One sentence that names a row for assistive technology: "USER_DEACTIVATE by Rudo Moyo, Tue 1 Dec 2026, 08:15 CAT". */
export function auditRowName(entry: AuditEntry): string {
  return `${entry.action} by ${auditActorName(entry)}, ${formatClinicDateTime(entry.timestamp, 'time unknown')}`;
}

function AccountValue({ systemId, onOpenUser }: { systemId: string; onOpenUser?: (systemId: string) => void }) {
  return (
    <View style={styles.accountValue}>
      <Text style={styles.value} selectable>{systemId}</Text>
      {onOpenUser ? (
        <LinkText label="Open account" accessibilityLabel={`Open account ${systemId}`} onPress={() => onOpenUser(systemId)} />
      ) : null}
    </View>
  );
}

/**
 * Every field of one audit row, in a side panel. `entry` is the row when it is already known; `loading` and
 * `error` describe a row that is being fetched by id.
 */
export function AuditDetailPanel({ open, entry, loading = false, error = null, onRetry, onClose, onOpenUser }: {
  open:     boolean;
  entry:    AuditEntry | null;
  loading?: boolean;
  error?:   string | null;
  onRetry?: () => void;
  onClose:  () => void;
  /** Opens the account screen of an actor, patient or target; the links are left out without it. */
  onOpenUser?: (systemId: string) => void;
}) {
  const title = entry ? `Audit entry ${entry.auditId}` : 'Audit entry';
  return (
    <SidePanel open={open} title={title} onClose={() => onClose()}>
      {entry ? (
        <View style={styles.detail}>
          <InfoRow label="When" value={formatClinicDateTime(entry.timestamp, 'Unknown')} />
          <InfoRow label="Outcome"><AuditStatusBadge status={entry.status} /></InfoRow>
          <InfoRow label="Action"><Text style={styles.code} selectable>{entry.action}</Text></InfoRow>
          <InfoRow label="Resource" value={auditResourceText(entry)} emptyText="None" />
          <InfoRow label="Actor" value={entry.actorName} emptyText={entry.systemId ? 'Name not available' : 'Not signed in'} />
          <InfoRow label="Actor account" emptyText="None">
            {entry.systemId ? <AccountValue systemId={entry.systemId} onOpenUser={onOpenUser} /> : undefined}
          </InfoRow>
          <InfoRow label="Actor role" value={entry.actorRole ? roleLabel(entry.actorRole) : null} emptyText="Not recorded" />
          <InfoRow label="Patient" emptyText="None">
            {entry.patientSystemId ? <AccountValue systemId={entry.patientSystemId} onOpenUser={onOpenUser} /> : undefined}
          </InfoRow>
          <InfoRow label="Target account" emptyText="None">
            {entry.targetSystemId ? <AccountValue systemId={entry.targetSystemId} onOpenUser={onOpenUser} /> : undefined}
          </InfoRow>
          <InfoRow label="Request" emptyText="Not recorded">
            {entry.httpMethod || entry.requestPath ? (
              <Text style={styles.code} selectable>{`${entry.httpMethod ?? ''} ${entry.requestPath ?? ''}`.trim()}</Text>
            ) : undefined}
          </InfoRow>
          <InfoRow label="IP address" value={entry.ipAddress} emptyText="Not recorded" />
          <InfoRow label="Browser or app" value={entry.userAgent} emptyText="Not recorded" />
          {/* SUCCESS rows (ACCOUNT_DEACTIVATION_EFFECTS) carry their clean-up counts here, so only a failure calls it an error. */}
          <InfoRow label={entry.status === 'FAILURE' ? 'Error message' : 'Details'} value={entry.errorMessage} emptyText="None" />
        </View>
      ) : loading ? (
        <LoadingView label="Loading audit entry…" />
      ) : (
        <ErrorView message={error ?? 'This audit entry could not be found.'} onRetry={error ? onRetry : undefined} />
      )}
    </SidePanel>
  );
}

// ─── The screen ───────────────────────────────────────────────────────────────

const STATUS_OPTIONS: SelectOption<AuditStatus>[] = [
  { value: 'SUCCESS', label: 'Success' },
  { value: 'FAILURE', label: 'Failure' },
];

const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

interface FilterValues {
  actor: string; patient: string; action: string; resourceType: string; status: AuditStatus | ''; from: string; to: string;
}

const EMPTY_FILTERS: FilterValues = { actor: '', patient: '', action: '', resourceType: '', status: '', from: '', to: '' };

function upper(value: string | undefined): string {
  return (value ?? '').trim().toUpperCase();
}

function parseStatus(value: string | undefined): AuditStatus | '' {
  const key = upper(value);
  return (AUDIT_STATUSES as readonly string[]).includes(key) ? (key as AuditStatus) : '';
}

function parseDate(value: string | undefined): string {
  const raw = (value ?? '').trim();
  return DATE_ONLY.test(raw) ? raw : '';
}

/** The filters as the server will match them: ids, action and resource type trimmed and upper-cased. */
function normalise(values: FilterValues): FilterValues {
  return {
    actor: upper(values.actor), patient: upper(values.patient), action: upper(values.action),
    resourceType: upper(values.resourceType), status: values.status, from: values.from, to: values.to,
  };
}

function sameFilters(a: FilterValues, b: FilterValues): boolean {
  return (Object.keys(EMPTY_FILTERS) as (keyof FilterValues)[]).every(key => a[key] === b[key]);
}

function parseAuditId(value: string | undefined): number | null {
  if (!value || !/^\d{1,15}$/.test(value)) return null;
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

/**
 * The audit search. The applied filters and the opened row live in the URL (`actor`, `patient`, `action`,
 * `resourceType`, `status`, `from`, `to`, `id`), so a refresh or a link from an account shows the same result.
 */
export default function AdminAuditLog(props: Partial<SectionProps>) {
  const { params, theme, setParams, openUser } = useAdminNav(props);

  const applied: FilterValues = {
    actor:        upper(params.actor),
    patient:      upper(params.patient),
    action:       upper(params.action),
    resourceType: upper(params.resourceType),
    status:       parseStatus(params.status),
    from:         parseDate(params.from),
    to:           parseDate(params.to),
  };
  const appliedKey = JSON.stringify(applied);

  // What is typed in the form; it reaches the list only through "Apply filters".
  const [draft, setDraft] = useState<FilterValues>(applied);
  const [rangeError, setRangeError] = useState<string | null>(null);
  useEffect(() => {
    // The URL changed (Apply, Clear, or a link from another screen): the form shows what is applied.
    setDraft(JSON.parse(appliedKey) as FilterValues);
    setRangeError(null);
  }, [appliedKey]);

  // A hand-edited link with the dates the wrong way round would only produce a 400: nothing is requested for it.
  const appliedRangeError = auditRangeError(applied.from || undefined, applied.to || undefined);
  const filters: AuditFilters = applied;
  const logs = useAuditLogs(filters, { enabled: !appliedRangeError });

  const hasApplied = !sameFilters(applied, EMPTY_FILTERS);
  const dirty = !sameFilters(normalise(draft), applied);

  const set = <K extends keyof FilterValues>(key: K) => (value: FilterValues[K]) => {
    setDraft(prev => ({ ...prev, [key]: value }));
    if (key === 'from' || key === 'to') setRangeError(null);
  };

  const apply = () => {
    const next = normalise(draft);
    const problem = auditRangeError(next.from || undefined, next.to || undefined);
    if (problem) { setRangeError(problem); return; }
    setRangeError(null);
    if (sameFilters(next, applied)) { setDraft(next); return; }
    setParams({
      actor: next.actor || undefined, patient: next.patient || undefined, action: next.action || undefined,
      resourceType: next.resourceType || undefined, status: next.status || undefined,
      from: next.from || undefined, to: next.to || undefined, id: undefined,
    });
  };

  const clear = () => {
    setDraft(EMPTY_FILTERS);
    setRangeError(null);
    setParams({
      actor: undefined, patient: undefined, action: undefined, resourceType: undefined, status: undefined,
      from: undefined, to: undefined, id: undefined,
    });
  };

  // The opened row. It is normally among the loaded rows; a link to an older row fetches that one row.
  const openId = parseAuditId(params.id);
  const loaded = openId === null ? null : logs.items.find(row => row.auditId === openId) ?? null;
  const single = useAuditEntry(openId, { enabled: openId !== null && !loaded && !logs.loading });
  const entry = loaded ?? single.data;

  const openRow = (row: AuditEntry) => setParams({ id: row.auditId });
  const closeRow = () => setParams({ id: undefined });

  const detailsButton = (row: AuditEntry) => (
    <Button
      label="Details" variant="ghost" size="sm" icon="open-outline"
      accessibilityLabel={`Details, ${auditRowName(row)}`}
      onPress={() => openRow(row)}
    />
  );

  const actorCell = (row: AuditEntry) => (
    <View style={styles.stack}>
      <Text style={styles.value}>{auditActorName(row)}</Text>
      {row.systemId || row.actorRole ? (
        <Text style={styles.muted} selectable>
          {[row.actorName ? row.systemId : null, row.actorRole ? roleLabel(row.actorRole) : null].filter(Boolean).join(' · ')}
        </Text>
      ) : null}
    </View>
  );

  const columns: DataColumn<AuditEntry>[] = [
    { key: 'time', header: 'When', flex: 1.9, render: row => <Text style={styles.value}>{formatClinicDateTime(row.timestamp, 'Unknown')}</Text> },
    { key: 'actor', header: 'Actor', flex: 2, render: actorCell },
    { key: 'action', header: 'Action', flex: 2, render: row => <Text style={styles.code} selectable>{row.action}</Text> },
    {
      key: 'resource', header: 'Resource', flex: 1.7,
      render: row => <Text style={[styles.value, !auditResourceText(row) && styles.missing]}>{auditResourceText(row) || 'None'}</Text>,
    },
    {
      key: 'subject', header: 'About', flex: 1.8,
      render: row => <Text style={[styles.value, !auditSubjectText(row) && styles.missing]} selectable>{auditSubjectText(row) || 'None'}</Text>,
    },
    { key: 'status', header: 'Outcome', flex: 1.2, render: row => <AuditStatusBadge status={row.status} /> },
    { key: 'details', header: 'Details', flex: 1.2, render: detailsButton },
  ];

  const renderCard = (row: AuditEntry) => (
    <Card style={styles.card}>
      <View style={styles.cardTop}>
        <Text style={styles.code} selectable>{row.action}</Text>
        <AuditStatusBadge status={row.status} />
      </View>
      <View style={styles.stack}>
        <Text style={styles.value}>{formatClinicDateTime(row.timestamp, 'Unknown')}</Text>
        <Text style={styles.muted} selectable>
          {[auditActorName(row), row.actorRole ? roleLabel(row.actorRole) : null].filter(Boolean).join(' · ')}
        </Text>
        {auditResourceText(row) ? <Text style={styles.muted}>{auditResourceText(row)}</Text> : null}
        {auditSubjectText(row) ? <Text style={styles.muted} selectable>{auditSubjectText(row)}</Text> : null}
      </View>
      {detailsButton(row)}
    </Card>
  );

  const today = clinicToday();

  return (
    <Screen
      title="Audit log"
      subtitle="Who did what, and when. Times are clinic time. Reading this log is itself recorded."
      theme={theme}
    >
      <Card style={styles.filterCard}>
        <View style={styles.filters} {...webOnly({ role: 'search', 'aria-label': 'Filter the audit log' })}>
          <View style={styles.filter}>
            <TextField
              label="Actor account ID" value={draft.actor} onChange={set('actor')} onSubmit={apply}
              placeholder="ADM-00001" maxLength={20} autoCapitalize="characters" autoComplete="off"
            />
          </View>
          <View style={styles.filter}>
            <TextField
              label="Patient account ID" value={draft.patient} onChange={set('patient')} onSubmit={apply}
              placeholder="PAT-00001" maxLength={20} autoCapitalize="characters" autoComplete="off"
            />
          </View>
          <View style={styles.filter}>
            <TextField
              label="Action" value={draft.action} onChange={set('action')} onSubmit={apply}
              placeholder="USER_DEACTIVATE" maxLength={60} autoCapitalize="characters" autoComplete="off"
              hint="The exact action name."
            />
          </View>
          <View style={styles.filter}>
            <TextField
              label="Resource type" value={draft.resourceType} onChange={set('resourceType')} onSubmit={apply}
              placeholder="ACCOUNT" maxLength={60} autoCapitalize="characters" autoComplete="off"
              hint="The exact resource type."
            />
          </View>
          <View style={styles.filter}>
            <SelectField<AuditStatus>
              label="Outcome" value={draft.status} options={STATUS_OPTIONS} allowEmpty placeholder="Success and failure"
              onChange={set('status')}
            />
          </View>
          <View style={styles.filter}>
            <DateField label="From" value={draft.from} onChange={set('from')} max={draft.to || today} />
          </View>
          <View style={styles.filter}>
            <DateField
              label="To" value={draft.to} onChange={set('to')} min={draft.from || undefined} max={today}
              error={rangeError}
            />
          </View>
        </View>
        <View style={styles.filterActions}>
          {hasApplied || dirty ? (
            <Button label="Clear filters" variant="ghost" icon="close-circle-outline" onPress={clear} />
          ) : null}
          <Button label="Apply filters" icon="funnel-outline" onPress={apply} disabled={!dirty} />
        </View>
      </Card>

      {appliedRangeError ? (
        <ErrorView message={`${appliedRangeError} Change the dates and apply the filters again.`} compact />
      ) : (
        <DataTable<AuditEntry>
          state={logs}
          columns={columns}
          keyOf={row => row.auditId}
          label="Audit entries"
          renderCard={renderCard}
          stackBelow={980}
          empty={hasApplied
            ? { icon: 'search-outline', title: 'No audit entries match these filters', sub: 'Account IDs, the action and the resource type must match exactly.', action: { label: 'Clear filters', onPress: clear } }
            : { icon: 'document-text-outline', title: 'No audit entries yet' }}
        />
      )}

      <AuditDetailPanel
        open={openId !== null}
        entry={entry}
        loading={!entry && (logs.loading || single.loading)}
        error={entry ? null : single.error}
        onRetry={single.refetch}
        onClose={closeRow}
        onOpenUser={openUser}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  detail:       { gap: 2 },
  accountValue: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', columnGap: space.md, rowGap: 2 },

  filterCard:    { gap: space.md },
  filters:       { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.md },
  filter:        { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0 },
  filterActions: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'flex-end', gap: space.sm },

  card:    { gap: space.sm },
  cardTop: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  stack:   { gap: 2, minWidth: 0 },
  value:   { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  missing: { color: colors.textSubtle },
  muted:   { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  // Machine values (action names, request paths). Text wraps inside its cell, so a long value never widens the row.
  code:    { fontSize: fontSize.sm, fontWeight: '600', color: colors.text, lineHeight: 19 },
});
