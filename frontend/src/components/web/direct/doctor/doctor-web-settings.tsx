import { UserSettingsScreen } from '@/components/web/settings/user-settings';
import { ROLE_THEME, type SectionProps } from '@/lib/nav/sections';

/** Doctor dashboard section `settings`: the shared notification settings screen. */
export default function DoctorWebSettings({ theme }: Partial<SectionProps>) {
  return <UserSettingsScreen role="doctor" theme={theme ?? ROLE_THEME.doctor} />;
}
