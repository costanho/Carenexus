import { useMemo } from 'react';
import { ActivityIndicator, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import {
  Button, ErrorView, Icon, InfoRow, LoadingView, Screen, SectionCard,
  ariaLevel, colors, decorative, fontSize, radius, space, type IoniconsName,
} from '@/components/web/ui';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useResource } from '@/hooks/use-resource';
import { useSectionNav } from '@/hooks/use-section-nav';
import { listPendingDoctors } from '@/lib/admin/api';
import { countOpenClaims, listAdminInvoices } from '@/lib/admin/billing-api';
import { countUnassignedCorrections } from '@/lib/admin/corrections-api';
import { countPendingDeletionRequests } from '@/lib/admin/ops-api';
import { navGroups, ROLE_THEME, type RoleTheme, type SectionParamsInput, type SectionProps } from '@/lib/nav/sections';
import { fetchSupportThreads } from '@/lib/support/api';
import { adminNavRegistry } from '../sections/admin.sections';
import { useOutboxListed } from './admin-sidebar';

// ─── Overview counts ──────────────────────────────────────────────────────────

/** One number of the overview: the `totalElements` of a paged endpoint asked for a single row. */
interface CountState {
  count:      number | null;
  /** First load. */
  loading:    boolean;
  /** Reloading after Refresh or Retry. */
  refreshing: boolean;
  error:      string | null;
  refetch:    () => void;
}

function useCount(load: () => Promise<number>, errorMessage: string): CountState {
  const { data, loading, refreshing, error, refetch } = useResource<number>(load, [], { errorMessage });
  return { count: data, loading, refreshing, error, refetch };
}

/** Length of the clinician verification queue: GET /api/admin/doctors/pending?size=1 (as usePendingDoctorCount). */
async function countPendingClinicians(): Promise<number> {
  return (await listPendingDoctors(0, 1)).totalElements;
}

async function countOverdueInvoices(): Promise<number> {
  return (await listAdminInvoices({ status: 'OVERDUE' }, 0, 1)).totalElements;
}

async function countOpenSupportThreads(): Promise<number> {
  return (await fetchSupportThreads({ open: true, page: 0, size: 1 })).totalElements;
}

interface OverviewItem {
  key:      string;
  /** What is counted, shown under the number. */
  title:    string;
  icon:     IoniconsName;
  /** The section the tile opens, and its params (the list the number was counted from). */
  section:  string;
  params?:  SectionParamsInput;
  /** Text of the link, for assistive technology ("Open the verification queue"). */
  linkText: string;
  state:    CountState;
}

function countText(count: number): string {
  return count > 999 ? '999+' : String(count);
}

/** A count that opens the list it was counted from. Loading and failure are shown inside the tile. */
function OverviewTile({ item, theme, onOpen }: { item: OverviewItem; theme: RoleTheme; onOpen: () => void }) {
  const { title, icon, linkText, state } = item;
  const waiting = state.count !== null && state.count > 0;
  /** The last reload failed and the number on screen is the one loaded before it. */
  const stale = state.count !== null && state.error !== null && !state.loading && !state.refreshing;
  const showError = state.error !== null && !state.loading && !state.refreshing;
  let valueLabel: string;
  if (state.count !== null) {
    valueLabel = state.count === 0 ? 'none' : String(state.count);
    if (stale) valueLabel = `${valueLabel}, last known, could not be refreshed`;
  } else if (state.loading) valueLabel = 'loading';
  else valueLabel = 'not available';

  return (
    <View style={styles.tileCell} role="listitem">
      <View style={[styles.tile, waiting && { borderColor: theme.accent }]}>
        <TouchableOpacity
          style={styles.tileLink}
          onPress={onOpen}
          activeOpacity={0.8}
          accessibilityRole="button"
          accessibilityLabel={`${title}: ${valueLabel}. ${linkText}`}
        >
          <View style={styles.tileTop}>
            <View style={[styles.tileIcon, { backgroundColor: theme.accentBg }]} {...decorative}>
              <Icon name={icon} size={18} color={theme.accent} />
            </View>
            <View style={styles.tileValue} {...decorative}>
              {state.count !== null ? (
                <Text style={[styles.count, { color: stale ? colors.textSubtle : waiting ? theme.accent : colors.textMuted }]}>
                  {countText(state.count)}
                </Text>
              ) : state.loading ? (
                <ActivityIndicator size="small" color={theme.accent} />
              ) : (
                <Text style={styles.countMissing}>–</Text>
              )}
            </View>
          </View>
          <Text style={styles.tileTitle}>{title}</Text>
          <View style={styles.tileFoot} {...decorative}>
            <Text style={[styles.tileLinkText, { color: theme.accent }]} numberOfLines={1}>{linkText}</Text>
            <Icon name="chevron-forward" size={14} color={theme.accent} />
          </View>
        </TouchableOpacity>
        {showError && (
          <View style={styles.tileError}>
            <Text style={styles.tileErrorText}>
              {stale ? `Could not refresh, showing the last known number: ${state.error}` : state.error}
            </Text>
            <Button
              label="Retry"
              size="sm"
              variant="secondary"
              icon="refresh-outline"
              onPress={state.refetch}
              loading={state.refreshing}
              accessibilityLabel={`Retry ${title.toLowerCase()}`}
            />
          </View>
        )}
      </View>
    </View>
  );
}

// ─── Signed-in administrator ──────────────────────────────────────────────────

function AccountSummary({ onOpenSecurity }: { onOpenSecurity?: () => void }) {
  const { user, loading, error, refetch } = useCurrentUser();

  return (
    <SectionCard
      title="Your account"
      action={onOpenSecurity ? (
        <Button label="Account security" variant="secondary" size="sm" icon="lock-closed-outline" onPress={onOpenSecurity} />
      ) : undefined}
    >
      {loading ? (
        <LoadingView compact label="Loading your account" />
      ) : error || !user ? (
        <ErrorView compact message="Your account details could not be loaded." onRetry={refetch} />
      ) : (
        <View>
          <InfoRow label="Name" value={`${user.first_name} ${user.last_name}`.trim()} emptyText="Not set" />
          <InfoRow label="Email" value={user.email} />
          <InfoRow label="Account ID" value={user.system_id} />
          <InfoRow label="Role" value="Administrator" />
        </View>
      )}
    </SectionCard>
  );
}

// ─── Home ─────────────────────────────────────────────────────────────────────

/**
 * Administrator home: what is waiting for an administrator (each number is the `totalElements` of the list it
 * opens), every administration tool from the section registry, and the signed-in account.
 */
export default function AdminHome({ navigate, theme }: Partial<SectionProps>) {
  const nav = useSectionNav();
  const go = navigate ?? nav.navigate;
  const look = theme ?? ROLE_THEME.admin;
  const { hasSection, homeKey } = nav;

  const pendingClinicians = useCount(countPendingClinicians, 'The number of pending verifications could not be loaded.');
  const accountRequests = useCount(countPendingDeletionRequests, 'The number of open account requests could not be loaded.');
  const openClaims      = useCount(countOpenClaims, 'The number of open insurance claims could not be loaded.');
  const overdueInvoices = useCount(countOverdueInvoices, 'The number of overdue invoices could not be loaded.');
  const corrections     = useCount(countUnassignedCorrections, 'The number of unassigned correction requests could not be loaded.');
  const supportThreads  = useCount(countOpenSupportThreads, 'The number of open support conversations could not be loaded.');

  const overview: OverviewItem[] = [
    {
      key: 'clinicians', title: 'Pending clinician verifications', icon: 'shield-checkmark-outline',
      section: 'clinician-queue', linkText: 'Open the verification queue', state: pendingClinicians,
    },
    {
      key: 'account-requests', title: 'Open account requests', icon: 'person-remove-outline',
      section: 'account-requests', linkText: 'Review deletion requests', state: accountRequests,
    },
    {
      key: 'claims', title: 'Open insurance claims', icon: 'document-attach-outline',
      section: 'billing', params: { tab: 'claims' }, linkText: 'Review claims', state: openClaims,
    },
    {
      key: 'overdue', title: 'Overdue invoices', icon: 'alert-circle-outline',
      section: 'billing', params: { tab: 'invoices', status: 'OVERDUE' }, linkText: 'View overdue invoices', state: overdueInvoices,
    },
    {
      key: 'corrections', title: 'Unassigned correction requests', icon: 'create-outline',
      section: 'corrections', params: { unassigned: 'true' }, linkText: 'Assign correction requests', state: corrections,
    },
    {
      key: 'support', title: 'Open support conversations', icon: 'chatbubbles-outline',
      section: 'messages', linkText: 'Open the support inbox', state: supportThreads,
    },
  ].filter(item => hasSection(item.section)) as OverviewItem[];

  const refreshing = overview.some(item => item.state.loading || item.state.refreshing);
  const refreshAll = () => overview.forEach(item => item.state.refetch());

  const outboxListed = useOutboxListed();
  const groups = useMemo(() => navGroups(adminNavRegistry(outboxListed)).groups, [outboxListed]);

  return (
    <Screen
      title="Administration"
      subtitle="What is waiting for an administrator, and every administration tool."
      theme={look}
      actions={(
        <Button
          label="Refresh"
          variant="secondary"
          icon="refresh-outline"
          onPress={refreshAll}
          loading={refreshing}
          accessibilityLabel="Refresh the overview counts"
        />
      )}
    >
      <View style={styles.block}>
        <Text style={styles.heading} accessibilityRole="header" {...ariaLevel(2)}>Needs attention</Text>
        <View style={styles.tiles} role="list" aria-label="Needs attention">
          {overview.map(item => (
            <OverviewTile key={item.key} item={item} theme={look} onOpen={() => go(item.section, item.params)} />
          ))}
        </View>
      </View>

      <View style={styles.block}>
        <Text style={styles.heading} accessibilityRole="header" {...ariaLevel(2)}>Tools</Text>
        {groups.map(group => (
          <View key={group.title} style={styles.group}>
            <Text style={styles.groupTitle} accessibilityRole="header" {...ariaLevel(3)}>{groupLabel(group.title)}</Text>
            <View style={styles.tools} role="list" aria-label={groupLabel(group.title)}>
              {group.items.filter(tool => tool.key !== homeKey).map(tool => (
                <View key={tool.key} style={styles.toolCell} role="listitem">
                  <TouchableOpacity
                    style={styles.tool}
                    onPress={() => go(tool.key)}
                    activeOpacity={0.8}
                    accessibilityRole="button"
                    accessibilityLabel={tool.label}
                  >
                    <View style={[styles.toolIcon, { backgroundColor: look.accentBg }]} {...decorative}>
                      <Icon name={tool.icon} size={18} color={look.accent} />
                    </View>
                    <Text style={styles.toolLabel}>{tool.label}</Text>
                    <View {...decorative}>
                      <Icon name="chevron-forward" size={16} color={colors.textMuted} />
                    </View>
                  </TouchableOpacity>
                </View>
              ))}
            </View>
          </View>
        ))}
      </View>

      <AccountSummary onOpenSecurity={hasSection('account') ? () => go('account') : undefined} />
    </Screen>
  );
}

/** Sidebar group titles are upper case; the home shows them in sentence case. */
function groupLabel(title: string): string {
  const lower = title.toLowerCase();
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

const styles = StyleSheet.create({
  block:   { marginBottom: space.xl, minWidth: 0 },
  heading: { fontSize: fontSize.lg, fontWeight: '700', color: colors.text, marginBottom: space.md },

  tiles:    { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  tileCell: { flexGrow: 1, flexShrink: 1, flexBasis: 240, minWidth: 0 },
  tile: {
    backgroundColor: colors.surface, borderRadius: radius.lg, borderWidth: 1, borderColor: colors.border,
    minWidth: 0, overflow: 'hidden',
  },
  tileLink:     { padding: space.lg, gap: space.sm, minHeight: 132 },
  tileTop:      { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space.md },
  tileIcon:     { width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center' },
  tileValue:    { minHeight: 36, justifyContent: 'center' },
  count:        { fontSize: 28, fontWeight: '700' },
  countMissing: { fontSize: 28, fontWeight: '700', color: colors.textSubtle },
  tileTitle:    { fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  tileFoot:     { flexDirection: 'row', alignItems: 'center', gap: space.xs, marginTop: 'auto' as never },
  tileLinkText: { fontSize: fontSize.sm, fontWeight: '600', flexShrink: 1 },
  tileError: {
    borderTopWidth: 1, borderTopColor: colors.border, backgroundColor: colors.dangerBg,
    paddingHorizontal: space.lg, paddingVertical: space.md, gap: space.sm, alignItems: 'flex-start',
  },
  tileErrorText: { fontSize: fontSize.sm, color: colors.danger },

  group:      { marginBottom: space.lg },
  groupTitle: { fontSize: fontSize.sm, fontWeight: '700', color: colors.textMuted, marginBottom: space.sm },
  tools:      { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  toolCell:   { flexGrow: 1, flexShrink: 1, flexBasis: 240, minWidth: 0 },
  tool: {
    flexDirection: 'row', alignItems: 'center', gap: space.md,
    backgroundColor: colors.surface, borderRadius: radius.lg, borderWidth: 1, borderColor: colors.border,
    paddingVertical: space.md, paddingHorizontal: space.lg, minHeight: 56,
  },
  toolIcon:  { width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center' },
  toolLabel: { flex: 1, minWidth: 0, fontSize: fontSize.md, fontWeight: '600', color: colors.text },
});
