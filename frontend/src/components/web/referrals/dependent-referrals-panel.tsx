import { NoAccessView, type RoleTheme, UiThemeProvider } from '@/components/web/ui';
import { type CaregiverGrant, effectivePermission } from '@/lib/caregiver/permissions';
import { PatientReferralList } from './patient-referral-list';

/** What the panel reads from a loved one's grant; `CaregiverDependent` satisfies it. */
export interface ReferralsDependent extends CaregiverGrant {
  patientSystemId: string;
  patientName?:    string | null;
}

export interface DependentReferralsPanelProps {
  /** The loved one whose referrals to show, as returned by the caregiver dependents list. */
  dependent: ReferralsDependent;
  /**
   * A referral to bring forward: the `id` of the caregiver notification link
   * `/dashboard/proxy?section=documents-results&tab=referrals&patient=<PAT id>&id=<id>`.
   */
  highlightId?: number | null;
  /** The dashboard's accent colours. Default: the caregiver (proxy) theme. */
  theme?: RoleTheme;
  /** Heading level of each referral's title, to fit the screen the panel is mounted in. Default 3. */
  headingLevel?: 2 | 3;
}

/**
 * A loved one's referrals for a caregiver, read-only. Shown only while the grant allows viewing records
 * (the backend REFERRALS permission: an active grant with `canViewRecords`, or FULL_ACCESS). The server checks
 * every request again, so a grant that expired or was revoked since the page loaded ends in the no-access state.
 */
export function DependentReferralsPanel({ dependent, highlightId = null, theme, headingLevel = 3 }: DependentReferralsPanelProps) {
  const name = dependent.patientName?.trim() || 'your loved one';
  const allowed = effectivePermission(dependent, 'canViewRecords');

  return (
    <UiThemeProvider theme={theme} role={theme ? undefined : 'proxy'}>
      {allowed ? (
        <PatientReferralList
          // A different loved one is a different list: start again rather than carry opened cards across.
          key={dependent.patientSystemId}
          patientSystemId={dependent.patientSystemId}
          audience="caregiver"
          patientName={dependent.patientName}
          highlightId={highlightId}
          headingLevel={headingLevel}
        />
      ) : (
        <NoAccessView
          message={`Referrals are not shared with you for ${name}.`}
          sub={`Viewing referrals needs permission to view records. ${name === 'your loved one' ? 'They' : name} can change what you can see in their caregiver settings.`}
        />
      )}
    </UiThemeProvider>
  );
}
