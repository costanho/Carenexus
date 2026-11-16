// Referral components (CONTRACTS 12.5). The doctor and patient screens live under components/web/direct;
// the caregiver dashboard mounts `DependentReferralsPanel` for the selected loved one.

export {
  DependentReferralsPanel, type DependentReferralsPanelProps, type ReferralsDependent,
} from './dependent-referrals-panel';
export { PatientReferralCard, PatientReferralList, type PatientReferralListProps } from './patient-referral-list';
export { ReferralBadges, ReferralPriorityBadge, ReferralStatusBadge, UnassignedBadge } from './referral-badges';
export { ReferralTimeline } from './referral-timeline';
export { ReferralFacts } from './referral-facts';
