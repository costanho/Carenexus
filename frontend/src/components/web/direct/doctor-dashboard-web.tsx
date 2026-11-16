import { useIsFocused } from 'expo-router';
import { useCallback } from 'react';
import { StyleSheet, View } from 'react-native';
import { SectionNavContext, useSectionNav } from '@/hooks/use-section-nav';
import { ROLE_THEME } from '@/lib/nav/sections';
import { NavDrawer } from '../layout/nav-drawer';
import { SectionHost } from '../layout/section-host';
import { MENU_FOCUS_KEY, rememberNavFocus, useNotificationActions, useShellLayout } from '../layout/shell-support';
import { registry } from '../sections/doctor.sections';
import { DoctorLeftSidebar } from './doctor/doctor-left-sidebar';
import { DoctorTopNavbar } from './doctor/doctor-top-navbar';

const THEME = ROLE_THEME.doctor;

/**
 * Doctor dashboard shell (web). Which section is shown comes from the URL
 * (`/dashboard/doctor?section=<key>`) and what a section is comes from the registry in
 * components/web/sections/doctor.sections.tsx; this file only lays out the sidebar, top bar and content.
 */
export function DoctorDashboardWeb() {
  // Opening a section adds a history entry, and the router keeps the previous screen mounted underneath for
  // Back. Only the screen in front renders the dashboard, so hidden copies hold no data and do not poll.
  const focused = useIsFocused();
  if (!focused) return <View style={styles.container} />;
  return <DoctorShell />;
}

function DoctorShell() {
  const nav = useSectionNav(registry);
  const layout = useShellLayout();
  const notifications = useNotificationActions(nav);
  const { navigate } = nav;
  const { closeDrawer } = layout;

  const navigateFromDrawer = useCallback((key: string) => {
    closeDrawer();
    // The drawer is closed in the copy the navigation mounts, so focus goes back to its menu button.
    rememberNavFocus(MENU_FOCUS_KEY);
    navigate(key);
  }, [closeDrawer, navigate]);
  const openProfile = useCallback(() => navigate('profile'), [navigate]);

  return (
    <SectionNavContext.Provider value={nav}>
      <View style={styles.container}>
        {!layout.isSmall && (
          <DoctorLeftSidebar collapsed={layout.isCollapsed} activeKey={nav.section} onNavigate={navigate} />
        )}
        <View style={styles.main}>
          <DoctorTopNavbar
            onMenuPress={layout.isSmall ? layout.openDrawer : undefined}
            onProfilePress={openProfile}
            onOpenThread={notifications.onOpenThread}
            onNotificationLink={notifications.onNavigate}
          />
          <SectionHost nav={nav} theme={THEME} />
        </View>
        {/* Phones: the sidebar is a drawer behind the menu button, so pages get the full width. */}
        <NavDrawer open={layout.drawerOpen} onClose={closeDrawer} closeColor="#FFFFFF">
          <DoctorLeftSidebar collapsed={false} activeKey={nav.section} onNavigate={navigateFromDrawer} />
        </NavDrawer>
      </View>
    </SectionNavContext.Provider>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, flexDirection: 'row', backgroundColor: '#F9FAFB' },
  main:      { flex: 1, flexDirection: 'column', minWidth: 0 },
});
