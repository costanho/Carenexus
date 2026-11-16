// Patient detail for the doctor dashboard. Other doctor screens import the panel from here.

export {
  PatientDetailPanel, PATIENT_OVERVIEW_TAB, type PatientDetailPanelProps, type PatientDetailTab,
  type PatientPanelHelpers,
} from './patient-detail-panel';
export { PatientOverview } from './patient-overview';
export { HealthStatusControl } from './health-status-control';
export { healthStatusSchema, type HealthStatusFormValues } from './health-status.schema';
export { focusSymptomParam, PATIENT_TAB_KEYS, useDoctorPatientTabs, type DoctorPatientTabsOptions } from './doctor-patient-tabs';
export { PatientHistoryTab, type PatientHistoryTabProps } from './patient-history-tab';
export { DoctorPatientPanel, type DoctorPatientPanelProps } from './doctor-patient-panel';
