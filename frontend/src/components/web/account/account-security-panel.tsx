import { StyleSheet, View } from 'react-native';
import { type AppRole, ROLE_THEME, type RoleTheme, space, UiThemeProvider } from '@/components/web/ui';
import { ChangeEmailCard } from './change-email-card';
import { ChangePasswordCard } from './change-password-card';
import { LegalAcceptancesCard } from './legal-acceptances-card';
import { MfaCard } from './mfa-card';
import { PrivacyDataCard } from './privacy-data-card';
import { SessionsCard } from './sessions-card';

export interface AccountSecurityPanelProps {
  /** The signed-in user's role; picks the accent colours when no `theme` is passed. */
  role:   AppRole;
  /** The dashboard's accent colours (`SectionProps.theme`). */
  theme?: RoleTheme;
}

/**
 * Account security and privacy of the signed-in user: password, sign-in email, two-factor authentication,
 * active sessions, data export / deletion / closure and legal acceptances. Mounted by each dashboard under
 * `tab=security` of its profile section (the admin dashboard mounts it as the `account` section). It draws no
 * page heading of its own: every card has a level-2 heading, so the host screen keeps its own title.
 */
export function AccountSecurityPanel({ role, theme }: AccountSecurityPanelProps) {
  return (
    <UiThemeProvider theme={theme ?? ROLE_THEME[role]}>
      <View style={styles.panel}>
        <ChangePasswordCard />
        <ChangeEmailCard />
        <MfaCard />
        <SessionsCard />
        <PrivacyDataCard role={role} />
        <LegalAcceptancesCard />
      </View>
    </UiThemeProvider>
  );
}

export default AccountSecurityPanel;

const styles = StyleSheet.create({
  panel: { gap: space.lg, width: '100%', maxWidth: 820, minWidth: 0 },
});
