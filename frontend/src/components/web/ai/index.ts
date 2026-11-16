// Automated-screening components (alerts and disease detections), shared by the doctor, patient and caregiver
// dashboards. Every view built from them carries the "automated screening, not a diagnosis" label.

export { PatientAlertsPanel, type PatientAlertsPanelProps } from './patient-alerts-panel';
export { HealthAlertsList, type HealthAlertsListProps } from './health-alerts-list';
export { AlertCard, type AlertCardProps } from './alert-card';
export { DetectionDetail, type DetectionDetailProps } from './detection-detail';
export { DetectionPanel, type DetectionPanelProps } from './detection-panel';
export { RunAssessmentDialog, type RunAssessmentDialogProps } from './run-assessment-dialog';
export { ScreeningNotice } from './screening-notice';
export { settleReviewedDetection, useAlertSourceNav } from './alert-list-support';
export { useClinicianName, useMySystemId, type AlertViewer } from './clinician-name';
