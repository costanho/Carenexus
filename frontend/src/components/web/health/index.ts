// Vitals and symptoms components shared by the patient screen and the doctor and caregiver views.
//
//   <HealthPanel patientSystemId={id} canRecord={...} canView={...} compact theme={theme} />   whole panel
//   <LatestVitalsCard onOpen={() => navigate('health-tracking')} theme={theme} />             dashboard card
//
// The parts are exported too, for screens that lay them out themselves.

export { HealthPanel, type HealthPanelProps } from './health-panel';
export { LatestVitalsCard, type LatestVitalsCardProps } from './latest-vitals-card';
export { VitalsTiles, MetricSeverityBadge, HEALTH_NO_ACCESS_MESSAGE, type VitalsTilesProps } from './vitals-tiles';
export {
  TrendChart, TREND_PERIODS, DEFAULT_TREND_DAYS, toTrendDays, toMetricFamily,
  type TrendChartProps, type TrendDays,
} from './trend-chart';
export { VitalsHistory, type VitalsHistoryProps } from './vitals-history';
export { SymptomTimeline, type SymptomTimelineProps } from './symptom-timeline';
export { RecordVitalsForm, type RecordVitalsFormProps } from './record-vitals-form';
export { SymptomForm, type SymptomFormProps } from './symptom-form';
export {
  RecordVitalsDialog, SymptomDialog, recordedNotice, loggedNotice, type HealthNotice,
} from './health-dialogs';
