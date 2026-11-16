// Correction requests (CONTRACTS 14, API contract be-corrections): a patient, or a caregiver who may manage the
// care plan, asks for a clinical record to be corrected; the authoring doctor answers with a note.
//
//   RequestCorrectionButton        on record, lab, imaging, prescription and consultation cards (patient, proxy)
//   PatientCorrectionRequestsList  the Corrections tab of the patient and, with a dependent's id, of the caregiver
//   DoctorCorrectionQueue          the doctor's queue (Medical Records, tab "corrections")

export { RequestCorrectionButton, type RequestCorrectionButtonProps } from './request-correction-button';
export { PatientCorrectionRequestsList, type PatientCorrectionRequestsListProps } from './patient-correction-requests-list';
export { DoctorCorrectionQueue, type DoctorCorrectionQueueProps } from './doctor-correction-queue';
export { CorrectionRequestCard, type CorrectionRequestCardProps } from './correction-request-card';
