// Billing actions for the patient and caregiver screens (built on '@/components/web/billing'):
//   PayDialog               start a payment and follow it until the gateway settles it
//   PaymentMethodsManager   the patient's saved methods: add, make default, remove
//   ClaimForm               submit an insurance claim with attachments
//   ClaimTracker            every claim of a patient, with progress and Appeal
//   PatientBillingView      the whole section body for one patient (tabs, invoice detail, the dialogs above)

export { PayDialog, type PayDialogProps } from './pay-dialog';
export {
  AddPaymentMethodDialog, PaymentMethodsManager, type AddPaymentMethodDialogProps, type PaymentMethodsManagerProps,
} from './payment-methods-manager';
export { ClaimForm, type ClaimFormProps } from './claim-form';
export { ClaimTracker, type ClaimFilterKey, type ClaimTrackerProps } from './claim-tracker';
export { AppealClaimDialog, type AppealClaimDialogProps } from './appeal-claim-dialog';
export { PaymentHistory, type PaymentHistoryProps } from './payment-history';
export { GatewayNotice } from './gateway-notice';
export {
  PatientBillingView, parseInvoiceIdParam, type BillingTabKey, type PatientBillingViewProps,
} from './patient-billing-view';
export { canSubmitClaimFor, reservedAmount } from './rules';
