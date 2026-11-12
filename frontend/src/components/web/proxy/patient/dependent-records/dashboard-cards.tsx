import { type ReactNode, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Button, Card, colors, ErrorView, fontSize, Icon, type IoniconsName, LoadingView, radius, space,
  StatusBadge, statusLabel, statusTone, UiThemeProvider, useUiTheme,
} from '@/components/web/ui';
import type { CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import {
  type DependentRecordsGroup, listDependentLabResults, listDependentPrescriptions, useDependentRecords,
  type LabResultDto, type PrescriptionDto,
} from '@/hooks/use-dependent-records';
import { AccessNote, isAccessStatus } from './common';
import { dependentName, formatDay } from './format';
import { CRITICAL_NOTICE } from './record-cards';

// The caregiver home's per-loved-one cards: active medications and the most recent lab results. Each half is
// shown only when the caregiver's access includes it; otherwise a "not shared with you" note takes its place.

/** Rows shown per card; the full lists are one click away. */
const PREVIEW_SIZE = 3;
/** Loved ones shown (and loaded) before "Show all". */
const SHOW_LIMIT = 3;

export interface DependentHighlightsProps {
  dependents:        readonly CaregiverDependent[];
  /** Opens the Medications section for this loved one. */
  onOpenMedications: (dependent: CaregiverDependent) => void;
  /** Opens Documents & Results on the lab results of this loved one; with an id, on that result. */
  onOpenLabResults:  (dependent: CaregiverDependent, resultId?: number) => void;
}

export function DependentHighlights({ dependents, onOpenMedications, onOpenLabResults }: DependentHighlightsProps) {
  const [expanded, setExpanded] = useState(false);
  // Only the loved ones on screen are loaded; "Show all" loads the rest and keeps what is already there.
  const shown = expanded ? dependents : dependents.slice(0, SHOW_LIMIT);

  const medications = useDependentRecords<PrescriptionDto, CaregiverDependent>(
    shown, 'canViewMedications',
    (patient, page, size) => listDependentPrescriptions(patient, { status: 'ACTIVE' }, page, size),
    [],
    { pageSize: PREVIEW_SIZE, keyOf: p => p.prescriptionId, errorMessage: 'Could not load medications. Please try again.' },
  );
  const labResults = useDependentRecords<LabResultDto, CaregiverDependent>(
    shown, 'canViewLabResults',
    (patient, page, size) => listDependentLabResults(patient, {}, page, size),
    [],
    { pageSize: PREVIEW_SIZE, keyOf: r => r.resultId, errorMessage: 'Could not load lab results. Please try again.' },
  );

  if (dependents.length === 0) return null;
  const hidden = dependents.length - SHOW_LIMIT;

  return (
    <UiThemeProvider role="proxy">
      <View style={styles.wrap}>
        <Text style={styles.heading} accessibilityRole="header" {...ariaLevel(2)}>Medications and lab results</Text>
        {shown.map((dependent, index) => {
          const name = dependentName(dependent);
          const meds = medications.groups[index];
          const labs = labResults.groups[index];
          return (
            <Card key={dependent.patientSystemId} style={styles.card}>
              <Text style={styles.name} accessibilityRole="header" {...ariaLevel(3)}>{name}</Text>
              <View style={styles.panels}>
                <Panel
                  title="Active medications"
                  icon="medkit-outline"
                  group={meds}
                  what="Medications"
                  name={name}
                  emptyText="No active medications."
                  onRetry={() => medications.retry(dependent.patientSystemId)}
                  viewAll={{
                    label: meds.total > meds.items.length ? `View all ${meds.total} active medications` : 'View medications',
                    context: `for ${name}`,
                    onPress: () => onOpenMedications(dependent),
                  }}
                >
                  {meds.items.map(p => (
                    <View key={p.prescriptionId} style={styles.row} role="listitem">
                      <Text style={styles.rowTitle}>{p.medicationName}</Text>
                      <Text style={styles.rowMeta}>{[p.dosage, p.frequency].filter(Boolean).join(' · ')}</Text>
                    </View>
                  ))}
                </Panel>
                <Panel
                  title="Recent lab results"
                  icon="flask-outline"
                  group={labs}
                  what="Lab results"
                  name={name}
                  emptyText="No lab results yet."
                  onRetry={() => labResults.retry(dependent.patientSystemId)}
                  viewAll={{
                    label: labs.total > labs.items.length ? `View all ${labs.total} lab results` : 'View lab results',
                    context: `for ${name}`,
                    onPress: () => onOpenLabResults(dependent),
                  }}
                >
                  {labs.items.map(r => (
                    <View key={r.resultId} role="listitem">
                      <LabRow result={r} name={name} onPress={() => onOpenLabResults(dependent, r.resultId)} />
                    </View>
                  ))}
                </Panel>
              </View>
            </Card>
          );
        })}
        {hidden > 0 ? (
          <Button
            label={expanded ? 'Show fewer loved ones' : `Show ${hidden} more ${hidden === 1 ? 'loved one' : 'loved ones'}`}
            variant="ghost"
            size="sm"
            icon={expanded ? 'chevron-up' : 'chevron-down'}
            onPress={() => setExpanded(e => !e)}
            style={styles.more}
          />
        ) : null}
      </View>
    </UiThemeProvider>
  );
}

function Panel<T>({ title, icon, group, what, name, emptyText, onRetry, viewAll, children }: {
  title:     string;
  icon:      IoniconsName;
  group:     DependentRecordsGroup<T, CaregiverDependent>;
  /** What the panel shows, capitalised and plural, for the "not shared" note. */
  what:      string;
  name:      string;
  emptyText: string;
  onRetry:   () => void;
  viewAll:   { label: string; context: string; onPress: () => void };
  children:  ReactNode;
}) {
  const { accent } = useUiTheme();
  let body: ReactNode;
  if (group.status === 'loading') {
    body = <LoadingView compact label={`Loading ${what.toLowerCase()} for ${name}`} />;
  } else if (isAccessStatus(group.status)) {
    body = <AccessNote compact status={group.status} what={what} name={name} />;
  } else if (group.status === 'failed') {
    body = <ErrorView compact message={group.error ?? `Could not load ${what.toLowerCase()}.`} onRetry={onRetry} />;
  } else if (group.items.length === 0) {
    body = <Text style={styles.empty}>{emptyText}</Text>;
  } else {
    body = (
      <>
        <View style={styles.rows} role="list" aria-label={`${title} for ${name}`}>{children}</View>
        <Button
          label={viewAll.label}
          accessibilityLabel={`${viewAll.label} ${viewAll.context}`}
          variant="ghost"
          size="sm"
          icon="arrow-forward"
          onPress={viewAll.onPress}
        />
      </>
    );
  }
  return (
    <View style={styles.panel}>
      <View style={styles.panelHead}>
        <Icon name={icon} size={16} color={accent} />
        <Text style={styles.panelTitle} accessibilityRole="header" {...ariaLevel(4)}>{title}</Text>
      </View>
      {body}
    </View>
  );
}

function LabRow({ result, name, onPress }: { result: LabResultDto; name: string; onPress: () => void }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={
        `${result.testName}, ${formatDay(result.testDate)}, ${statusLabel(result.status)}`
        + `${result.isCritical ? `, ${CRITICAL_NOTICE.toLowerCase()}` : ''}. Open this result for ${name}`
      }
      onPress={onPress}
      style={({ pressed }) => [styles.row, styles.rowButton, pressed && styles.rowPressed]}
    >
      <View style={styles.rowTexts}>
        <Text style={styles.rowTitle}>{result.testName}</Text>
        <Text style={styles.rowMeta}>{formatDay(result.testDate)}</Text>
      </View>
      <StatusBadge label={statusLabel(result.status)} tone={statusTone('lab', result.status)} />
      <Icon name="chevron-forward" size={16} color={colors.textMuted} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  wrap:    { gap: space.md, minWidth: 0 },
  heading: { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  card:    { gap: space.md, borderRadius: 16 },
  name:    { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },

  // Two columns that stack on a phone.
  panels:     { flexDirection: 'row', flexWrap: 'wrap', gap: space.lg },
  panel:      { flexGrow: 1, flexShrink: 1, flexBasis: 260, minWidth: 0, gap: space.sm },
  panelHead:  { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  panelTitle: { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  empty:      { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  rows:       { gap: space.xs, minWidth: 0 },
  row:        { gap: 2, paddingVertical: space.xs, minWidth: 0 },
  rowButton:  {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, minHeight: 40,
    paddingHorizontal: space.sm, marginHorizontal: -space.sm, borderRadius: radius.md,
  },
  rowPressed: { backgroundColor: colors.bgSubtle },
  rowTexts:   { flexGrow: 1, flexShrink: 1, flexBasis: 120, minWidth: 0, gap: 2 },
  rowTitle:   { fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  rowMeta:    { fontSize: fontSize.sm, color: colors.textMuted },
  more:       { alignSelf: 'center' },
});
