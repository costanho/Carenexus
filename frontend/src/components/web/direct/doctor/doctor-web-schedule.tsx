import { type ReactNode, useCallback, useMemo } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { isActiveAppointment } from '@/hooks/use-doctor-appointment-actions';
import {
  addClinicDays, clinicDayStart, clinicMonthStart, clinicNextMonthStart, clinicWeekStart, useDoctorSchedule,
  type ApiAppointmentStatus, type ScheduleEntry,
} from '@/hooks/use-doctor-schedule';
import { useSectionNav } from '@/hooks/use-section-nav';
import {
  ariaLevel, Banner, Button, Card, colors, DateField, EmptyView, ErrorView, fontSize, IconButton, LoadingView,
  NoAccessView, Screen, space, Tabs,
} from '@/components/web/ui';
import type { SectionProps } from '@/lib/nav/sections';
import { clinicToday, isAfterClinicNow, toClinicLocal } from '@/lib/time/clinic';
import {
  AppointmentCard, type AppointmentChange, FilterPill, StatTile, useAppointmentController,
} from './doctor-web-appointment';

/** How much of the calendar is shown. Kept in the URL as `tab`, with the anchor day as `date`. */
type RangeView = 'day' | 'week' | 'month';
/** ACTIVE (the default) hides cancelled appointments, like the dashboard's Today count. */
type StatusFilter = 'ACTIVE' | 'ALL' | ApiAppointmentStatus | 'NEEDS_OUTCOME';

const RANGE_VIEWS: readonly RangeView[] = ['day', 'week', 'month'];
const RANGE_LABEL: Record<RangeView, string> = { day: 'Day', week: 'Week', month: 'Month' };
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

const STATUS_PILLS: readonly { key: StatusFilter; label: string }[] = [
  { key: 'ACTIVE',        label: 'Active'              },
  { key: 'ALL',           label: 'All incl. cancelled' },
  { key: 'SCHEDULED',     label: 'Scheduled'           },
  { key: 'RESCHEDULED',   label: 'Rescheduled'         },
  { key: 'NEEDS_OUTCOME', label: 'Awaiting outcome'    },
  { key: 'COMPLETED',     label: 'Completed'           },
  { key: 'CANCELLED',     label: 'Cancelled'           },
  { key: 'NO_SHOW',       label: 'No Show'             },
];

// ─── Calendar helpers (clinic dates as "YYYY-MM-DD"; formatted without shifting zones) ────────────

function isRealDate(value: string | undefined): value is string {
  if (!value || !DATE_ONLY.test(value)) return false;
  const [y, m, d] = value.split('-').map(Number);
  const at = new Date(Date.UTC(y, m - 1, d));
  return !Number.isNaN(at.getTime()) && at.toISOString().slice(0, 10) === value;
}

function formatDate(date: string, options: Intl.DateTimeFormatOptions): string {
  const [y, m, d] = date.split('-').map(Number);
  return new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', ...options }).format(new Date(Date.UTC(y, m - 1, d)));
}

const longDate  = (date: string) => formatDate(date, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
const shortDate = (date: string) => formatDate(date, { day: 'numeric', month: 'short' });

/** The window of a view: `from` is its first day, `to` the day after its last day. */
function rangeOf(view: RangeView, anchor: string): { from: string; to: string } {
  if (view === 'week') {
    const monday = clinicWeekStart(anchor);
    return { from: monday, to: addClinicDays(monday, 7) };
  }
  if (view === 'month') return { from: clinicMonthStart(anchor), to: clinicNextMonthStart(anchor) };
  return { from: anchor, to: addClinicDays(anchor, 1) };
}

/** The anchor of the previous (-1) or next (+1) day, week or month. */
function shiftAnchor(view: RangeView, anchor: string, direction: -1 | 1): string {
  if (view === 'day') return addClinicDays(anchor, direction);
  if (view === 'week') return addClinicDays(clinicWeekStart(anchor), direction * 7);
  return direction === 1 ? clinicNextMonthStart(anchor) : clinicMonthStart(addClinicDays(clinicMonthStart(anchor), -1));
}

function rangeTitle(view: RangeView, range: { from: string; to: string }, today: string): string {
  if (view === 'day') return range.from === today ? `Today · ${longDate(range.from)}` : longDate(range.from);
  if (view === 'month') return formatDate(range.from, { month: 'long', year: 'numeric' });
  const last = addClinicDays(range.to, -1);
  return `${shortDate(range.from)} to ${shortDate(last)} ${last.slice(0, 4)}`;
}

function groupByDate(entries: ScheduleEntry[], today: string): { key: string; label: string; items: ScheduleEntry[] }[] {
  const map = new Map<string, ScheduleEntry[]>();
  for (const e of entries) {
    const key = (toClinicLocal(e.scheduled_at) ?? String(e.scheduled_at)).slice(0, 10);
    const bucket = map.get(key);
    if (bucket) bucket.push(e);
    else map.set(key, [e]);
  }
  return Array.from(map.entries()).map(([key, items]) => ({
    key,
    label: !isRealDate(key) ? key : key === today
      ? `Today · ${formatDate(key, { day: 'numeric', month: 'long', year: 'numeric' })}`
      : longDate(key),
    items,
  }));
}

function matches(entry: ScheduleEntry, filter: StatusFilter): boolean {
  switch (filter) {
    case 'ALL':           return true;
    case 'ACTIVE':        return entry.status !== 'CANCELLED';
    case 'NEEDS_OUTCOME': return entry.needs_outcome;
    default:              return entry.status === filter;
  }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

/**
 * The doctor's calendar: every appointment of a day, a week or a month (GET /api/doctors/schedule/window), grouped
 * by day, with the same row actions as the Appointments screen.
 *
 * URL params: `tab` (day | week | month), `date` (a day inside the range, default today at the clinic) and
 * `status`. Rendered with `SectionProps`; the older `onOpenThread` prop keeps working.
 */
export default function DoctorWebSchedule(props: Partial<SectionProps> & {
  /** Switch to Messages with this thread open (legacy wiring; `openThread` of SectionProps wins). */
  onOpenThread?: (threadId: number) => void;
} = {}) {
  const nav = useSectionNav();
  const params = props.params ?? nav.params;
  const navigate = props.navigate ?? nav.navigate;
  const openThread = props.openThread ?? props.onOpenThread ?? (nav.hasSection('messages') ? nav.openThread : undefined);
  const { setParams } = nav;

  const today = clinicToday();
  const view: RangeView = (RANGE_VIEWS as readonly string[]).includes(params.tab ?? '') ? (params.tab as RangeView) : 'day';
  const anchor = isRealDate(params.date) ? params.date : today;
  const requestedStatus = (params.status ?? '').toUpperCase().replace(/-/g, '_');
  const statusFilter: StatusFilter = STATUS_PILLS.some(p => p.key === requestedStatus) ? (requestedStatus as StatusFilter) : 'ACTIVE';

  const range = rangeOf(view, anchor);
  const includesToday = range.from <= today && today < range.to;

  const list = useDoctorSchedule({
    source: 'window', from: clinicDayStart(range.from), to: clinicDayStart(range.to), order: 'asc',
    pageSize: 100, pagesPerLoad: 5,
  });
  const { updateEntry } = list;

  const onChanged = useCallback((updated: ScheduleEntry, change: AppointmentChange) => {
    const day = (toClinicLocal(updated.scheduled_at) ?? '').slice(0, 10);
    // An appointment moved out of the range on screen leaves it; everything else is updated in place.
    const outside = change === 'rescheduled' && !!day && (day < range.from || day >= range.to);
    updateEntry(updated.appointment_id, outside ? null : updated);
  }, [updateEntry, range.from, range.to]);

  const controller = useAppointmentController({ navigate, onOpenThread: openThread, onChanged });

  const stats = useMemo(() => ({
    // Same rule as the dashboard's "Today's Appointments": cancelled appointments do not count.
    active:       list.entries.filter(e => e.status !== 'CANCELLED').length,
    completed:    list.entries.filter(e => e.status === 'COMPLETED').length,
    upcoming:     list.entries.filter(e => isActiveAppointment(e) && isAfterClinicNow(e.scheduled_at)).length,
    needsOutcome: list.entries.filter(e => e.needs_outcome).length,
  }), [list.entries]);

  const filtered = useMemo(() => list.entries
    .filter(e => matches(e, statusFilter))
    .slice()
    .sort((a, b) => String(a.scheduled_at).localeCompare(String(b.scheduled_at)) || a.appointment_id - b.appointment_id),
  [list.entries, statusFilter]);
  const groups = useMemo(() => groupByDate(filtered, today), [filtered, today]);

  const goTo = (date: string) => setParams({ date: date === today ? undefined : date });
  const selectStatus = (key: StatusFilter) => setParams({ status: key === 'ACTIVE' ? undefined : key.toLowerCase().replace(/_/g, '-') });

  const firstStat = view === 'day'
    ? (includesToday ? 'Today' : 'This day')
    : view === 'week' ? (includesToday ? 'This week' : 'Week') : (includesToday ? 'This month' : 'Month');
  const loaded = !(list.loading && list.entries.length === 0) && !(list.error && list.entries.length === 0);
  const feedbackOnRow = !!controller.feedback && filtered.some(e => e.appointment_id === controller.feedback?.appointmentId);

  let body: ReactNode;
  if (list.loading && list.entries.length === 0) {
    body = <LoadingView label="Loading schedule…" compact />;
  } else if (list.error && list.entries.length === 0) {
    body = list.errorStatus === 403
      ? <NoAccessView compact />
      : <ErrorView message={list.error} onRetry={list.refetch} compact />;
  } else if (groups.length === 0) {
    body = list.entries.length === 0 ? (
      <EmptyView
        icon="calendar-clear-outline"
        title={view === 'day' ? 'No appointments on this day' : `No appointments in this ${view}`}
        sub="Choose another date, or check your availability if patients should be able to book you here."
      />
    ) : (
      <EmptyView icon="calendar-outline" title="No appointments found" sub="Try adjusting your date range or status filter" />
    );
  } else {
    body = (
      <View style={styles.list} aria-busy={list.refreshing || list.loadingMore}>
        {groups.map(g => (
          <View key={g.key} style={styles.group}>
            <Text style={styles.groupLabel} accessibilityRole="header" {...ariaLevel(2)}>{g.label}</Text>
            <View style={styles.groupItems} role="list" aria-label={`Appointments on ${g.label}`}>
              {g.items.map(e => (
                <View key={e.appointment_id} role="listitem" style={styles.listItem}>
                  {/* Level 3: each card sits under its day's level-2 heading. */}
                  <AppointmentCard entry={e} controller={controller} headingLevel={3} />
                </View>
              ))}
            </View>
          </View>
        ))}
      </View>
    );
  }

  return (
    <Screen
      title="My Schedule"
      subtitle="Manage your appointments and consultations"
      theme={props.theme}
      role="doctor"
      actions={(
        <>
          {nav.hasSection('availability') && (
            <Button label="Edit availability" icon="time-outline" variant="secondary" size="sm" onPress={() => navigate('availability')} />
          )}
          <Button label="Refresh" icon="refresh-outline" variant="secondary" size="sm" onPress={list.refetch} loading={list.refreshing} />
        </>
      )}
    >
      <Tabs
        label="Calendar range"
        tabs={RANGE_VIEWS.map(key => ({ key, label: RANGE_LABEL[key] }))}
        active={view}
        onChange={key => setParams({ tab: key === 'day' ? undefined : key })}
      />

      <Card style={styles.rangeCard}>
        <View style={styles.rangeRow}>
          <IconButton label={`Previous ${view}`} icon="chevron-back" variant="secondary" onPress={() => goTo(shiftAnchor(view, anchor, -1))} />
          <Text style={styles.rangeTitle} role="status" aria-live="polite">{rangeTitle(view, range, today)}</Text>
          <IconButton label={`Next ${view}`} icon="chevron-forward" variant="secondary" onPress={() => goTo(shiftAnchor(view, anchor, 1))} />
          {!includesToday && <Button label="Today" variant="ghost" size="sm" accessibilityLabel="Today, go back to today" onPress={() => goTo(today)} />}
        </View>
        <View style={styles.dateField}>
          <DateField label="Go to date" value={anchor} onChange={value => { if (isRealDate(value)) goTo(value); }} />
        </View>
        <Text style={styles.note}>Times are clinic time (CAT).</Text>
      </Card>

      <View style={styles.statsRow}>
        <StatTile icon="today-outline" label={firstStat} value={loaded ? stats.active : null} testID="schedule-stat-today" />
        <StatTile icon="checkmark-circle-outline" label="Completed" value={loaded ? stats.completed : null} />
        <StatTile icon="time-outline" label="Upcoming" value={loaded ? stats.upcoming : null} />
        <StatTile
          icon="hourglass-outline" label="Awaiting outcome" value={loaded ? stats.needsOutcome : null}
          onPress={() => selectStatus('NEEDS_OUTCOME')} hint="Review"
        />
      </View>

      <Card>
        <View style={styles.pillRow} role="group" aria-label="Status">
          {STATUS_PILLS.map(p => (
            <FilterPill key={p.key} label={p.label} selected={statusFilter === p.key} onPress={() => selectStatus(p.key)} />
          ))}
        </View>
      </Card>

      {!!controller.feedback && !feedbackOnRow && (
        <Banner tone={controller.feedback.tone} text={controller.feedback.text} onDismiss={controller.dismissFeedback} />
      )}
      {!!list.error && list.entries.length > 0 && (
        <Banner tone="danger" text={list.error} action={{ label: 'Retry', onPress: list.refetch }} />
      )}

      {loaded && (
        <View style={styles.countRow}>
          <Text style={styles.count} role="status" aria-live="polite">
            {filtered.length} appointment{filtered.length !== 1 ? 's' : ''}
          </Text>
          {list.hasMore && (
            <Text style={styles.note}>The first {list.entries.length} of {list.total} appointments in this range are loaded.</Text>
          )}
        </View>
      )}

      {body}

      {list.hasMore && list.entries.length > 0 && (
        <View style={styles.footer}>
          <Button label="Load more" icon="chevron-down" variant="secondary" onPress={list.loadMore} loading={list.loadingMore} />
        </View>
      )}
    </Screen>
  );
}

// ─── Styles ──────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  rangeCard:  { gap: space.md },
  rangeRow:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
  rangeTitle: { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0, fontSize: fontSize.lg, fontWeight: '700', color: colors.text, textAlign: 'center' },
  dateField:  { maxWidth: 240, width: '100%' },
  note:       { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  statsRow: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  pillRow:  { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },

  countRow: { gap: 2 },
  count:    { fontSize: fontSize.sm, color: colors.textMuted, fontWeight: '500' },

  list:       { gap: space.xl, minWidth: 0 },
  group:      { gap: space.sm, minWidth: 0 },
  groupLabel: { fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted, letterSpacing: 1, textTransform: 'uppercase' },
  groupItems: { gap: space.md, minWidth: 0 },
  listItem:   { minWidth: 0 },
  footer:     { alignItems: 'center' },
});
