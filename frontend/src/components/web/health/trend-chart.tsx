import { type CSSProperties, useId, useMemo, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import {
  ariaPressed, Banner, colors, CONTROL_HEIGHT, EmptyView, ErrorView, fontSize, Icon, IS_WEB, LoadingView,
  NoAccessView, radius, SelectField, space, STATUS_TONES, useUiTheme, webOnly,
} from '@/components/web/ui';
import { useHealthMetricTrend } from '@/hooks/use-health-metrics';
import {
  familyInfo, formatMetricValue, METRIC_FAMILIES, metricInfo, type FamilyInfo, type MetricFamily,
} from '@/lib/health/catalog';
import type { HealthMetricTrend, TrendPoint } from '@/lib/health/types';
import { dateOnlyParts, formatDateOnly } from '@/lib/ui/date-only';
import { HEALTH_NO_ACCESS_MESSAGE } from './vitals-tiles';

// A daily trend of one measurement: a line chart (inline SVG, web only) with a 7, 30 and 90 day switch, a text
// summary and a data table holding the same numbers. Days without a reading are gaps: the line only joins
// neighbouring days and nothing is interpolated.

export const TREND_PERIODS = [7, 30, 90] as const;
export type TrendDays = typeof TREND_PERIODS[number];
export const DEFAULT_TREND_DAYS: TrendDays = 30;

/** A period read from a URL param or a prop: one of 7, 30, 90, otherwise the default. */
export function toTrendDays(value: unknown): TrendDays {
  const n = typeof value === 'string' ? Number(value) : value;
  return TREND_PERIODS.find(p => p === n) ?? DEFAULT_TREND_DAYS;
}

/** A family key read from a URL param or a prop, or undefined when it is not one. */
export function toMetricFamily(value: unknown): MetricFamily | undefined {
  return typeof value === 'string' ? familyInfo(value)?.key : undefined;
}

export interface TrendChartProps {
  patientSystemId: string | null | undefined;
  /** The measurement shown. Pass it with `onFamilyChange` to keep the choice in the URL; otherwise the chart keeps it itself. */
  family?:         MetricFamily;
  onFamilyChange?: (family: MetricFamily) => void;
  /** The measurement shown first when `family` is not controlled. Default blood pressure. */
  defaultFamily?:  MetricFamily;
  /** The period shown: 7, 30 or 90 days. Pass it with `onDaysChange` to keep the choice in the URL. */
  days?:           TrendDays;
  onDaysChange?:   (days: TrendDays) => void;
  /** A shorter chart for a side panel. */
  compact?:        boolean;
  /** When false nothing is requested. Default true. */
  enabled?:        boolean;
  noAccessMessage?: string;
}

const FONT_STACK = '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif';
const DAY_MS = 86_400_000;

function dayNumber(date: string | null | undefined): number | null {
  const p = dateOnlyParts(date);
  return p ? Math.round(Date.UTC(p.y, p.m - 1, p.d) / DAY_MS) : null;
}

function dateOfDay(day: number): string {
  return new Date(day * DAY_MS).toISOString().slice(0, 10);
}

const shortDate = (date: string) => formatDateOnly(date, { day: 'numeric', month: 'short' }, date);
const fullDate = (date: string) => formatDateOnly(date, { day: 'numeric', month: 'short', year: 'numeric' }, date);

function seriesLabel(series: HealthMetricTrend): string {
  return metricInfo(series.type).label;
}

function seriesUnit(series: HealthMetricTrend): string {
  return metricInfo(series.type).unitLabel || series.unit;
}

/** One sentence per series: latest value, lowest and highest. Empty when the series has no points. */
function seriesSummary(series: HealthMetricTrend): string {
  const points = series.points;
  if (points.length === 0) return '';
  const unit = seriesUnit(series);
  const last = points[points.length - 1];
  const low = Math.min(...points.map(p => p.min));
  const high = Math.max(...points.map(p => p.max));
  const days = points.length === 1 ? '1 day with readings' : `${points.length} days with readings`;
  return `${seriesLabel(series)}: latest daily average ${formatMetricValue(last.avg)} ${unit} on ${fullDate(last.date)}; `
    + `lowest ${formatMetricValue(low)}, highest ${formatMetricValue(high)} ${unit} over ${days}.`;
}

function abnormalDays(seriesList: readonly HealthMetricTrend[]): number {
  const days = new Set<string>();
  for (const series of seriesList) for (const p of series.points) if (p.abnormal) days.add(p.date);
  return days.size;
}

export function TrendChart({
  patientSystemId, family: familyProp, onFamilyChange, defaultFamily = 'BLOOD_PRESSURE',
  days: daysProp, onDaysChange, compact = false, enabled = true, noAccessMessage = HEALTH_NO_ACCESS_MESSAGE,
}: TrendChartProps) {
  const [ownFamily, setOwnFamily] = useState<MetricFamily>(defaultFamily);
  const [ownDays, setOwnDays] = useState<TrendDays>(DEFAULT_TREND_DAYS);
  // Without the SVG (native) the table is the only view of the numbers, so it starts open there.
  const [tableOpen, setTableOpen] = useState(!IS_WEB);
  const tableId = `trend-table-${useId().replace(/:/g, '')}`;

  const family: FamilyInfo = familyInfo(familyProp ?? ownFamily) ?? METRIC_FAMILIES[0];
  const days = toTrendDays(daysProp ?? ownDays);
  const trend = useHealthMetricTrend(patientSystemId, family.types, days, { enabled });

  const setFamily = (next: MetricFamily) => { setOwnFamily(next); onFamilyChange?.(next); };
  const setDays = (next: TrendDays) => { setOwnDays(next); onDaysChange?.(next); };

  const seriesList = trend.data ?? [];
  const hasPoints = seriesList.some(s => s.points.length > 0);
  const flagged = abnormalDays(seriesList);
  const summaries = seriesList.map(seriesSummary).filter(Boolean);

  let body;
  if (trend.loading) {
    body = <LoadingView label="Loading the trend" compact />;
  } else if (!trend.data) {
    if (trend.errorStatus === 403) body = <NoAccessView message={noAccessMessage} compact />;
    else if (trend.error) body = <ErrorView message={trend.error} onRetry={trend.refetch} compact />;
    else body = null;
  } else if (!hasPoints) {
    body = (
      <>
        {trend.error ? <Banner tone="danger" text={trend.error} action={{ label: 'Retry', onPress: trend.refetch }} /> : null}
        <EmptyView
          icon="analytics-outline"
          title={`No ${family.label.toLowerCase()} readings in the last ${days} days`}
          sub="Choose a longer period or another measurement."
          compact
        />
      </>
    );
  } else {
    const description = [
      `${family.label} over the last ${days} days.`,
      ...summaries,
      flagged > 0 ? `${flagged} ${flagged === 1 ? 'day has' : 'days have'} a reading outside the reference range.` : '',
      'The data table lists every day.',
    ].filter(Boolean).join(' ');
    body = (
      <>
        {trend.error ? <Banner tone="danger" text={trend.error} action={{ label: 'Retry', onPress: trend.refetch }} /> : null}
        {IS_WEB ? (
          <>
            <LineChart seriesList={seriesList} height={compact ? 160 : 220} description={description} />
            <Legend seriesList={seriesList} flagged={flagged > 0} />
          </>
        ) : null}
        <View style={styles.summary}>
          {summaries.map(sentence => <Text key={sentence} style={styles.summaryText}>{sentence}</Text>)}
          {flagged > 0 ? (
            <Text style={styles.summaryText}>
              {`${flagged} ${flagged === 1 ? 'day has' : 'days have'} a reading outside the reference range.`}
            </Text>
          ) : null}
        </View>
        {IS_WEB ? (
          <TableToggle open={tableOpen} controls={tableId} onToggle={() => setTableOpen(open => !open)} />
        ) : null}
        {tableOpen ? <TrendTable id={tableId} family={family} seriesList={seriesList} days={days} /> : null}
      </>
    );
  }

  return (
    <View style={styles.wrap}>
      <View style={styles.controls}>
        <View style={styles.select}>
          <SelectField<MetricFamily>
            label="Measurement"
            value={family.key}
            onChange={next => { if (next) setFamily(next); }}
            options={METRIC_FAMILIES.map(f => ({ value: f.key, label: `${f.label} (${f.unitLabel})` }))}
          />
        </View>
        <PeriodSwitch days={days} onChange={setDays} />
      </View>
      {body}
    </View>
  );
}

/**
 * Opens and closes the data table, the accessible alternative to the chart. A disclosure button: it reports
 * whether the table is open (`aria-expanded`) and which element it controls (`aria-controls`).
 */
function TableToggle({ open, controls, onToggle }: { open: boolean; controls: string; onToggle: () => void }) {
  const { accent } = useUiTheme();
  return (
    <Pressable
      onPress={onToggle}
      accessibilityRole="button"
      accessibilityState={{ expanded: open }}
      aria-expanded={open}
      {...webOnly({ 'aria-controls': open ? controls : undefined })}
      style={({ pressed }) => [styles.toggle, pressed && styles.togglePressed]}
    >
      <Icon name={open ? 'chevron-up' : 'list-outline'} size={14} color={accent} />
      <Text style={[styles.toggleText, { color: accent }]}>{open ? 'Hide data table' : 'Show data table'}</Text>
    </Pressable>
  );
}

function PeriodSwitch({ days, onChange }: { days: TrendDays; onChange: (days: TrendDays) => void }) {
  const { accent, accentBg } = useUiTheme();
  return (
    <View style={styles.period}>
      <Text style={styles.periodLabel}>Period</Text>
      <View style={styles.periodRow} role="group" aria-label="Period">
        {TREND_PERIODS.map(option => {
          const selected = option === days;
          return (
            <Pressable
              key={option}
              accessibilityRole="button"
              accessibilityLabel={`${option} days`}
              accessibilityState={{ selected }}
              {...ariaPressed(selected)}
              onPress={() => { if (!selected) onChange(option); }}
              style={[styles.periodButton, selected && { borderColor: accent, backgroundColor: accentBg }]}
            >
              <Text style={[styles.periodText, selected && { color: accent, fontWeight: '700' }]}>{`${option} days`}</Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

// ─── The chart ────────────────────────────────────────────────────────────────

interface SeriesStyle { stroke: string; dash?: string; marker: 'circle' | 'square' }

/** The second series differs by line pattern and marker shape as well as colour. */
function seriesStyle(index: number, accent: string): SeriesStyle {
  return index === 0
    ? { stroke: accent, marker: 'circle' }
    : { stroke: colors.textMuted, dash: '5 4', marker: 'square' };
}

function niceScale(min: number, max: number): { lo: number; hi: number; step: number } {
  let a = min, b = max;
  if (a === b) { const pad = Math.abs(a) * 0.05 || 1; a -= pad; b += pad; }
  const rough = (b - a) / 4;
  const magnitude = Math.pow(10, Math.floor(Math.log10(rough)));
  const ratio = rough / magnitude;
  const step = (ratio <= 1 ? 1 : ratio <= 2 ? 2 : ratio <= 5 ? 5 : 10) * magnitude;
  let lo = Math.floor(a / step) * step;
  const hi = Math.ceil(b / step) * step;
  // Readings are never negative; do not let the padding of a flat series invent a negative axis.
  if (min >= 0 && lo < 0) lo = 0;
  return { lo, hi, step };
}

function Marker({ x, y, style }: { x: number; y: number; style: SeriesStyle }) {
  return style.marker === 'circle'
    ? <circle cx={x} cy={y} r={3.5} fill={style.stroke} />
    : <rect x={x - 3.5} y={y - 3.5} width={7} height={7} fill={style.stroke} />;
}

function LineChart({ seriesList, height, description }: {
  seriesList:  readonly HealthMetricTrend[];
  height:      number;
  description: string;
}) {
  const { accent } = useUiTheme();
  const [width, setWidth] = useState(0);

  const model = useMemo(() => {
    const values = seriesList.flatMap(s => s.points.map(p => p.avg)).filter(Number.isFinite);
    if (values.length === 0) return null;
    const scale = niceScale(Math.min(...values), Math.max(...values));
    const ticks: number[] = [];
    for (let v = scale.lo; v <= scale.hi + scale.step / 1000; v += scale.step) ticks.push(Number(v.toFixed(6)));

    const pointDays = seriesList.flatMap(s => s.points.map(p => dayNumber(p.date))).filter((d): d is number => d !== null);
    const first = dayNumber(seriesList[0]?.from) ?? Math.min(...pointDays);
    const last = dayNumber(seriesList[0]?.to) ?? Math.max(...pointDays);
    return { scale, ticks, first: Math.min(first, ...pointDays), last: Math.max(last, ...pointDays) };
  }, [seriesList]);

  if (!model) return null;

  const tickLabels = model.ticks.map(formatMetricValue);
  const left = Math.max(32, Math.max(...tickLabels.map(t => t.length)) * 7 + 12);
  const right = 14, top = 12, bottom = 26;
  const plotW = Math.max(1, width - left - right);
  const plotH = Math.max(1, height - top - bottom);
  const span = model.last - model.first;
  const xOf = (day: number) => (span === 0 ? left + plotW / 2 : left + ((day - model.first) / span) * plotW);
  const yOf = (value: number) => top + (1 - (value - model.scale.lo) / (model.scale.hi - model.scale.lo)) * plotH;

  const axisDays = span === 0 ? [model.first] : plotW > 220 && span >= 2
    ? [model.first, model.first + Math.round(span / 2), model.last]
    : [model.first, model.last];

  const textStyle: CSSProperties = { fontFamily: FONT_STACK, fontSize: 11, fill: colors.textMuted };

  return (
    <View style={styles.chart} onLayout={e => setWidth(Math.round(e.nativeEvent.layout.width))}>
      {width > 0 ? (
        <svg width={width} height={height} role="img" aria-label={description} focusable="false" style={{ display: 'block' }}>
          {model.ticks.map((tick, i) => (
            <g key={tick}>
              <line x1={left} x2={left + plotW} y1={yOf(tick)} y2={yOf(tick)} stroke={colors.border} strokeWidth={1} />
              <text x={left - 6} y={yOf(tick) + 4} textAnchor="end" style={textStyle}>{tickLabels[i]}</text>
            </g>
          ))}
          {axisDays.map((day, i) => (
            <text
              key={day}
              x={xOf(day)}
              y={height - 8}
              textAnchor={span === 0 ? 'middle' : i === 0 ? 'start' : i === axisDays.length - 1 ? 'end' : 'middle'}
              style={textStyle}
            >
              {shortDate(dateOfDay(day))}
            </text>
          ))}
          {seriesList.map((series, index) => {
            const style = seriesStyle(index, accent);
            const placed = series.points
              .map(point => ({ point, day: dayNumber(point.date) }))
              .filter((p): p is { point: TrendPoint; day: number } => p.day !== null && Number.isFinite(p.point.avg));
            // The line joins neighbouring days only; a day without a reading breaks it.
            let path = '';
            placed.forEach((p, i) => {
              const joined = i > 0 && p.day - placed[i - 1].day === 1;
              path += `${joined ? 'L' : 'M'}${xOf(p.day).toFixed(1)} ${yOf(p.point.avg).toFixed(1)} `;
            });
            const unit = seriesUnit(series);
            return (
              <g key={series.type}>
                <path
                  d={path.trim()} fill="none" stroke={style.stroke} strokeWidth={2}
                  strokeDasharray={style.dash} strokeLinejoin="round" strokeLinecap="round"
                />
                {placed.map(({ point, day }) => {
                  const x = xOf(day), y = yOf(point.avg);
                  const readings = point.count === 1 ? '1 reading' : `${point.count} readings`;
                  return (
                    <g key={point.date}>
                      <title>
                        {`${seriesLabel(series)}, ${fullDate(point.date)}: ${formatMetricValue(point.avg)} ${unit} (${readings})`
                          + (point.abnormal ? ', outside the reference range' : '')}
                      </title>
                      {point.abnormal ? <circle cx={x} cy={y} r={7.5} fill="none" stroke={STATUS_TONES.warning.fg} strokeWidth={2} /> : null}
                      <Marker x={x} y={y} style={style} />
                    </g>
                  );
                })}
              </g>
            );
          })}
        </svg>
      ) : null}
    </View>
  );
}

function Legend({ seriesList, flagged }: { seriesList: readonly HealthMetricTrend[]; flagged: boolean }) {
  const { accent } = useUiTheme();
  if (seriesList.length < 2 && !flagged) return null;
  return (
    <View style={styles.legend}>
      {seriesList.length > 1 ? seriesList.map((series, index) => {
        const style = seriesStyle(index, accent);
        return (
          <View key={series.type} style={styles.legendItem}>
            <svg width={28} height={12} aria-hidden="true" focusable="false">
              <line x1={1} x2={27} y1={6} y2={6} stroke={style.stroke} strokeWidth={2} strokeDasharray={style.dash} />
              <Marker x={14} y={6} style={style} />
            </svg>
            <Text style={styles.legendText}>{seriesLabel(series)}</Text>
          </View>
        );
      }) : null}
      {flagged ? (
        <View style={styles.legendItem}>
          <svg width={18} height={18} aria-hidden="true" focusable="false">
            <circle cx={9} cy={9} r={7} fill="none" stroke={STATUS_TONES.warning.fg} strokeWidth={2} />
          </svg>
          <Text style={styles.legendText}>Ringed: a reading outside the reference range that day</Text>
        </View>
      ) : null}
    </View>
  );
}

// ─── The data table ───────────────────────────────────────────────────────────

function cellText(point: TrendPoint | undefined): string {
  if (!point) return 'No reading';
  const avg = formatMetricValue(point.avg);
  if (point.count <= 1) return avg;
  return `${avg} (${point.count} readings, ${formatMetricValue(point.min)} to ${formatMetricValue(point.max)})`;
}

function TrendTable({ id, family, seriesList, days }: {
  id:         string;
  family:     FamilyInfo;
  seriesList: readonly HealthMetricTrend[];
  days:       number;
}) {
  const caption = `${family.label}: daily averages, last ${days} days, newest first`;
  const columns = ['Date', ...seriesList.map(s => `${seriesLabel(s)} (${seriesUnit(s)})`), 'Outside reference range'];
  const dates = Array.from(new Set(seriesList.flatMap(s => s.points.map(p => p.date)))).sort().reverse();
  const rows = dates.map(date => {
    const points = seriesList.map(s => s.points.find(p => p.date === date));
    return [fullDate(date), ...points.map(cellText), points.some(p => p?.abnormal) ? 'Yes' : 'No'];
  });

  if (IS_WEB) {
    const cell: CSSProperties = {
      padding: '6px 10px', textAlign: 'left', verticalAlign: 'top', borderBottom: `1px solid ${colors.border}`,
      fontSize: fontSize.sm, color: colors.text,
    };
    const head: CSSProperties = { ...cell, fontWeight: 600, color: colors.textMuted, backgroundColor: colors.bgSubtle };
    return (
      <div id={id} style={{ overflowX: 'auto', maxWidth: '100%', border: `1px solid ${colors.border}`, borderRadius: radius.md }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontFamily: FONT_STACK }}>
          <caption style={{ padding: '8px 10px', textAlign: 'left', fontSize: fontSize.sm, fontWeight: 600, color: colors.text }}>
            {caption}
          </caption>
          <thead>
            <tr>{columns.map(column => <th key={column} scope="col" style={head}>{column}</th>)}</tr>
          </thead>
          <tbody>
            {rows.map(row => (
              <tr key={row[0]}>
                {row.map((value, i) => (i === 0
                  ? <th key={i} scope="row" style={{ ...cell, fontWeight: 600, whiteSpace: 'nowrap' }}>{value}</th>
                  : <td key={i} style={cell}>{value}</td>))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }

  return (
    <View nativeID={id} style={styles.nativeTable} accessibilityLabel={caption}>
      <Text style={styles.nativeCaption}>{caption}</Text>
      {rows.map(row => (
        <View key={row[0]} style={styles.nativeRow}>
          <Text style={styles.nativeDate}>{row[0]}</Text>
          {row.slice(1).map((value, i) => (
            <Text key={i} style={styles.nativeCell}>{`${columns[i + 1]}: ${value}`}</Text>
          ))}
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:     { gap: space.md, minWidth: 0 },
  controls: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-end', gap: space.md },
  select:   { flexGrow: 1, flexShrink: 1, flexBasis: 220, minWidth: 0, maxWidth: 360 },

  period:       { gap: space.xs, maxWidth: '100%' },
  periodLabel:  { fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  periodRow:    { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs },
  periodButton: {
    minHeight: CONTROL_HEIGHT, justifyContent: 'center', paddingHorizontal: space.md,
    borderWidth: 1, borderColor: colors.borderInput, borderRadius: radius.md, backgroundColor: colors.surface,
  },
  periodText:   { fontSize: fontSize.sm, fontWeight: '500', color: colors.textMuted },

  toggle: {
    minHeight: 32, maxWidth: '100%', flexDirection: 'row', alignItems: 'center', gap: space.sm, alignSelf: 'flex-start',
    paddingHorizontal: space.md, paddingVertical: space.xs, borderWidth: 1, borderColor: 'transparent', borderRadius: radius.md,
  },
  togglePressed: { opacity: 0.85 },
  toggleText:    { fontSize: fontSize.sm, fontWeight: '600', flexShrink: 1 },

  chart:       { width: '100%', minWidth: 0 },
  legend:      { flexDirection: 'row', flexWrap: 'wrap', columnGap: space.lg, rowGap: space.xs },
  legendItem:  { flexDirection: 'row', alignItems: 'center', gap: space.xs, maxWidth: '100%' },
  legendText:  { flexShrink: 1, fontSize: fontSize.xs, color: colors.textMuted },
  summary:     { gap: 2 },
  summaryText: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  nativeTable:   { gap: space.sm },
  nativeCaption: { fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  nativeRow:     { gap: 2, paddingVertical: space.xs, borderBottomWidth: 1, borderBottomColor: colors.border },
  nativeDate:    { fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  nativeCell:    { fontSize: fontSize.sm, color: colors.textMuted },
});
