import { UserSettingsScreen } from '@/components/web/settings/user-settings';
import { ROLE_THEME, type SectionProps } from '@/lib/nav/sections';

/** Patient dashboard section `settings`: the shared notification settings screen. */
export default function PatientWebSettings({ theme }: Partial<SectionProps>) {
  return <UserSettingsScreen role="patient" theme={theme ?? ROLE_THEME.patient} />;
}
