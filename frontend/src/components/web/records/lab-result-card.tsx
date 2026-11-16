import { useState } from 'react';
import { type LayoutChangeEvent, StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import {
  colors, fontSize, Icon, type IoniconsName, PHONE_BREAKPOINT, radius, space, StatusBadge, statusTone,
  type StatusTone,
} from '@/components/web/ui';
import type { LabResult, LabResultStatus, LabTestType } from '@/hooks/use-lab-results';
import {
  doctorByline, ExpandToggle, formatRecordDay, formatRecordMoment, humaniseKey, RecordBadges, RecordCard,
  type RecordCardActions, RecordCardFooter, RecordCardHeader, RecordMeta, RecordTextBlock, useExpanded,
} from './card-parts';

export const LAB_TYPE_LABELS: Record<LabTestType, { label: string; icon: IoniconsName }> = {
  BLOOD:   { label: 'Blood test', icon: 'water-outline' },
  URINE:   { label: 'Urinalysis', icon: 'beaker-outline' },
  STOOL:   { label: 'Stool test', icon: 'leaf-outline' },
  CULTURE: { label: 'Culture',    icon: 'bug-outline' },
  BIOPSY:  { label: 'Biopsy',     icon: 'cut-outline' },
  GENETIC: { label: 'Genetic',    icon: 'git-branch-outline' },
  SWAB:    { label: 'Swab',       icon: 'bandage-outline' },
  OTHER:   { label: 'Other',      icon: 'flask-outline' },
};

export const RESULT_STATUS_LABELS: Record<LabResultStatus, string> = {
  COMPLETED:       'Completed',
  PENDING:         'Pending',
  CRITICAL:        'Critical',
  REQUIRES_REVIEW: 'Needs review',
  CANCELLED:       'Cancelled',
};

/** Tone of a sub-test flag. LOW is as abnormal as HIGH for a lab value, so both read as a warning. */
function flagTone(flag: string): StatusTone {
  switch (flag) {
    case 'NORMAL':   return 'success';
    case 'CRITICAL': return 'critical';
    case 'HIGH': case 'LOW': case 'ABNORMAL': return 'warning';
    default:         return 'neutral';
  }
}

/** Tone of an antibiotic sensitivity value (culture results). */
function sensitivityTone(value: string): StatusTone {
  if (value === 'SENSITIVE') return 'success';
  if (value === 'RESISTANT') return 'danger';
  return 'warning';
}

interface Analyte {
  key:            string;
  value:          string;
  reference:      string | null;
  flag:           string;
  interpretation: string | null;
}

function isPrimitive(v: unknown): v is string | number | boolean {
  return typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean';
}

function text(v: unknown): string | null {
  if (!isPrimitive(v)) return null;
  const s = String(v).trim();
  return s ? s : null;
}

/** A sub-test entry `{ value, unit?, reference?, flag?, interpretation? }` (`referenceRange` is the old name of `reference`). */
function toAnalyte(key: string, raw: unknown): Analyte | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw) || !('value' in raw)) return null;
  const entry = raw as Record<string, unknown>;
  const value = text(entry.value);
  const unit = text(entry.unit);
  return {
    key,
    value:          value ? (unit ? `${value} ${unit}` : value) : '—',
    reference:      text(entry.reference) ?? text(entry.referenceRange),
    flag:           (text(entry.flag) ?? '').toUpperCase(),
    interpretation: text(entry.interpretation),
  };
}

/** Below this width of the card the four table columns no longer fit and each sub-test is stacked. */
const TABLE_MIN_WIDTH = 520;

function AnalyteTable({ analytes, testName }: { analytes: Analyte[]; testName: string }) {
  const { width: windowWidth } = useWindowDimensions();
  // The card can be much narrower than the window (sidebars on both sides), so its own width decides the
  // layout once it has been measured; before that the window width is the best guess.
  const [ownWidth, setOwnWidth] = useState<number | null>(null);
  const onLayout = (event: LayoutChangeEvent) => setOwnWidth(event.nativeEvent.layout.width);
  const stacked = ownWidth !== null ? ownWidth < TABLE_MIN_WIDTH : windowWidth < PHONE_BREAKPOINT;

  if (stacked) {
    return (
      <View style={styles.stack} role="list" aria-label={`Results of ${testName}`} onLayout={onLayout}>
        {analytes.map(a => (
          <View key={a.key} style={styles.stackItem} role="listitem">
            <View style={styles.stackTop}>
              <Text style={styles.stackName}>{humaniseKey(a.key)}</Text>
              {a.flag ? <StatusBadge label={a.flag} tone={flagTone(a.flag)} /> : null}
            </View>
            {/* Stacked, each value keeps the column name it had in the table. */}
            <Text style={styles.cellMuted}>Result: <Text style={styles.cellValue}>{a.value}</Text></Text>
            {a.reference ? <Text style={styles.cellMuted}>Reference: {a.reference}</Text> : null}
            {a.interpretation ? <Text style={styles.cellMuted}>{a.interpretation}</Text> : null}
          </View>
        ))}
      </View>
    );
  }

  return (
    <View style={styles.table} role="table" aria-label={`Results of ${testName}`} onLayout={onLayout}>
      <View style={[styles.row, styles.headRow]} role="row">
        <Text style={[styles.colTest, styles.headCell]} role="columnheader">Test</Text>
        <Text style={[styles.colValue, styles.headCell]} role="columnheader">Result</Text>
        <Text style={[styles.colRef, styles.headCell]} role="columnheader">Reference</Text>
        <Text style={[styles.colFlag, styles.headCell]} role="columnheader">Flag</Text>
      </View>
      {analytes.map(a => (
        <View key={a.key} style={styles.row} role="row">
          <View style={styles.colTest} role="cell">
            <Text style={styles.cellName}>{humaniseKey(a.key)}</Text>
            {a.interpretation ? <Text style={styles.cellMuted}>{a.interpretation}</Text> : null}
          </View>
          <Text style={[styles.colValue, styles.cellValue]} role="cell">{a.value}</Text>
          <Text style={[styles.colRef, styles.cellMuted]} role="cell">{a.reference ?? '—'}</Text>
          <View style={styles.colFlag} role="cell">
            {a.flag ? <StatusBadge label={a.flag} tone={flagTone(a.flag)} /> : <Text style={styles.cellMuted}>—</Text>}
          </View>
        </View>
      ))}
    </View>
  );
}

/**
 * The structured part of a lab result (`resultData`): sub-tests as a table, free-text entries, grouped values
 * such as antibiotic sensitivities, and the laboratory's comment.
 */
export function LabResultData({ data, testName }: { data: Record<string, unknown>; testName: string }) {
  const analytes: Analyte[] = [];
  const texts: [string, string][] = [];
  const groups: [string, [string, string][]][] = [];

  for (const [key, value] of Object.entries(data)) {
    if (key === 'comment') continue;
    const analyte = toAnalyte(key, value);
    if (analyte) { analytes.push(analyte); continue; }
    const plain = text(value);
    if (plain) { texts.push([key, plain]); continue; }
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      const members = Object.entries(value as Record<string, unknown>)
        .map(([name, v]) => [name, text(v)] as const)
        .filter((pair): pair is readonly [string, string] => pair[1] !== null)
        .map(([name, v]) => [name, v] as [string, string]);
      if (members.length > 0) groups.push([key, members]);
    }
  }
  const comment = text(data.comment);

  return (
    <View style={styles.data}>
      {analytes.length > 0 ? <AnalyteTable analytes={analytes} testName={testName} /> : null}

      {texts.map(([key, value]) => (
        <RecordTextBlock key={key} label={humaniseKey(key)} text={value} />
      ))}

      {groups.map(([key, members]) => (
        <View key={key} style={styles.group}>
          <Text style={styles.groupLabel}>{humaniseKey(key)}</Text>
          <View style={styles.groupItems}>
            {members.map(([name, value]) => (
              <View key={name} style={styles.groupItem}>
                <Text style={styles.groupName}>{humaniseKey(name)}</Text>
                <StatusBadge label={value} tone={sensitivityTone(value.toUpperCase())} icon={null} />
              </View>
            ))}
          </View>
        </View>
      ))}

      {comment ? (
        <View style={styles.comment}>
          <Icon name="information-circle-outline" size={16} color={colors.textMuted} />
          <Text style={styles.commentText}>{comment}</Text>
        </View>
      ) : null}
    </View>
  );
}

export interface LabResultCardProps {
  item:             LabResult;
  /** Extra controls for this result (attachment, correction request), drawn in the card footer. */
  actions?:         RecordCardActions<LabResult>;
  /** Open the full results at once (a result opened from a link). */
  defaultExpanded?: boolean;
}

/** One lab result: status, dates, headline value, the reviewing doctor's note and the full structured results. */
export function LabResultCard({ item, actions, defaultExpanded = false }: LabResultCardProps) {
  const [expanded, toggle] = useExpanded(defaultExpanded);
  const type = LAB_TYPE_LABELS[item.test_type] ?? LAB_TYPE_LABELS.OTHER;
  const isCritical = item.is_critical === 1 || item.status === 'CRITICAL';
  const title = item.test_name || type.label;

  return (
    <RecordCard criticalText={isCritical ? 'Critical result: requires immediate attention' : null}>
      <RecordCardHeader
        icon={type.icon}
        title={title}
        byline={doctorByline(item.doctor_name, item.doctor_specialization)}
        aside={<StatusBadge label={RESULT_STATUS_LABELS[item.status] ?? item.status} tone={statusTone('lab', item.status)} />}
      />

      <RecordBadges>
        <StatusBadge label={type.label} tone="neutral" icon={type.icon} />
      </RecordBadges>

      <RecordMeta
        items={[
          { icon: 'calendar-outline', label: 'Test date', value: formatRecordDay(item.test_date) },
          item.reviewed_at ? { icon: 'checkmark-circle-outline', label: 'Reviewed', value: formatRecordMoment(item.reviewed_at) } : null,
          item.reviewer_name ? { icon: 'person-circle-outline', label: 'Reviewed by', value: item.reviewer_name } : null,
        ]}
      />

      {item.result_value ? (
        <View style={styles.headline}>
          <Text style={styles.headlineLabel}>Result</Text>
          <Text style={styles.headlineValue}>{item.result_value}{item.unit ? ` ${item.unit}` : ''}</Text>
          {item.reference_range ? <Text style={styles.cellMuted}>Reference range: {item.reference_range}</Text> : null}
        </View>
      ) : null}

      {item.review_notes ? (
        <RecordTextBlock label="Doctor's note" icon="chatbox-ellipses-outline" text={item.review_notes} tinted />
      ) : null}

      {expanded && item.result_data ? <LabResultData data={item.result_data} testName={title} /> : null}

      <RecordCardFooter
        toggle={item.result_data ? (
          <ExpandToggle
            expanded={expanded}
            onToggle={toggle}
            showLabel="View full results"
            hideLabel="Hide full results"
            subject={title}
          />
        ) : undefined}
      >
        {actions?.(item)}
      </RecordCardFooter>
    </RecordCard>
  );
}

const styles = StyleSheet.create({
  headline:      { backgroundColor: colors.bgSubtle, borderRadius: radius.md, padding: space.md, gap: 2 },
  headlineLabel: { fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted, textTransform: 'uppercase', letterSpacing: 0.5 },
  headlineValue: { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },

  data: { gap: space.md, minWidth: 0 },

  table:    { borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, overflow: 'hidden' },
  row:      { flexDirection: 'row', alignItems: 'flex-start', gap: space.sm, paddingHorizontal: space.md, paddingVertical: space.sm, borderTopWidth: 1, borderTopColor: colors.border },
  headRow:  { backgroundColor: colors.bgSubtle, borderTopWidth: 0 },
  headCell: { fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted, textTransform: 'uppercase', letterSpacing: 0.3 },
  colTest:  { flex: 2, minWidth: 0 },
  colValue: { flex: 1.5, minWidth: 0 },
  colRef:   { flex: 2, minWidth: 0 },
  colFlag:  { flex: 1.2, minWidth: 0, alignItems: 'flex-start' },

  cellName:  { fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  cellValue: { fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  cellMuted: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  stack:     { gap: space.sm },
  stackItem: { borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, padding: space.md, gap: 2 },
  stackTop:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  stackName: { flexShrink: 1, fontSize: fontSize.sm, fontWeight: '700', color: colors.text },

  group:      { gap: space.xs },
  groupLabel: { fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted, textTransform: 'uppercase', letterSpacing: 0.5 },
  groupItems: { flexDirection: 'row', flexWrap: 'wrap', gap: space.md },
  groupItem:  { gap: 2 },
  groupName:  { fontSize: fontSize.sm, color: colors.text },

  comment:     { flexDirection: 'row', alignItems: 'flex-start', gap: space.sm, backgroundColor: colors.bgSubtle, borderRadius: radius.md, padding: space.md },
  commentText: { flex: 1, minWidth: 0, fontSize: fontSize.sm, color: colors.text, lineHeight: 19 },
});
