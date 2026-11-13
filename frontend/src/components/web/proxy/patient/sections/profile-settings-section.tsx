import { AccountSecurityPanel } from '@/components/web/account';
import { ROLE_THEME } from '@/components/web/ui';
import { useSectionNav } from '@/hooks/use-section-nav';
import type { SectionProps } from '@/lib/nav/sections';
import PatientProxyProfileSettings from '../patient-proxy-profile-settings';

// `?section=profile-settings`: the caregiver's own account. `tab=security` (the ACCOUNT_SECURITY notification
// target) opens password, email, two-factor, sessions, privacy and legal cards; `patient=<PAT id>` (the
// grant-change notification target) marks that loved one in the Linked Patients list.

export default function ProxyProfileSettingsSection(props: Partial<SectionProps>) {
  const nav = useSectionNav();
  const navigate = props.navigate ?? nav.navigate;
  const theme = props.theme ?? ROLE_THEME.proxy;
  return (
    <PatientProxyProfileSettings
      {...props}
      params={props.params ?? nav.params}
      navigate={navigate}
      theme={theme}
      onSelectPatient={dependent => navigate('patient-profile', { patient: dependent.patientSystemId })}
      securityPanel={<AccountSecurityPanel role="proxy" theme={theme} />}
    />
  );
}
