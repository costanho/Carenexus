import { AccountSecurityPanel } from '@/components/web/account';
import { FeeEditor } from '@/components/web/billing';
import { useCurrentUser } from '@/hooks/use-current-user';
import { ROLE_THEME, type SectionProps } from '@/lib/nav/sections';
import DoctorWebProfile from './doctor-web-profile';

/**
 * Doctor dashboard section `profile`: the profile screen with its two dashboard parts. The Security tab
 * (`?section=profile&tab=security`, the ACCOUNT_SECURITY notification link) holds the account security cards,
 * and the consultation fees sit beside Account Details; new invoices are priced from them.
 */
export default function DoctorWebProfileSection(props: Partial<SectionProps>) {
  const theme = props.theme ?? ROLE_THEME.doctor;
  const { user } = useCurrentUser();
  return (
    <DoctorWebProfile
      {...props}
      theme={theme}
      securityPanel={<AccountSecurityPanel role={props.role ?? 'doctor'} theme={theme} />}
      feeSlot={<FeeEditor doctorSystemId={user?.system_id} />}
    />
  );
}
