import { UserSettingsScreen } from '@/components/web/settings/user-settings';
import { ROLE_THEME, type SectionProps } from '@/lib/nav/sections';

/**
 * Caregiver dashboard section `notification-settings`: the shared notification settings screen. The settings are
 * the caregiver's own account settings; there are none per loved one.
 */
export default function PatientProxyNotificationSettings({ theme }: Partial<SectionProps>) {
  return <UserSettingsScreen role="proxy" theme={theme ?? ROLE_THEME.proxy} title="Notification Settings" />;
}
