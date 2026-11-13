import { useIsFocused } from 'expo-router';
import { useCallback } from 'react';
import { StyleSheet, View } from 'react-native';
import { SectionNavContext, useSectionNav } from '@/hooks/use-section-nav';
import { ROLE_THEME } from '@/lib/nav/sections';
import { NavDrawer } from '../layout/nav-drawer';
import { SectionHost } from '../layout/section-host';
import { MENU_FOCUS_KEY, rememberNavFocus, useNotificationActions, useShellLayout } from '../layout/shell-support';
import { registry, useMessageDoctor } from '../sections/patient.sections';
import { PatientSidebar } from './patient/patient-left-sidebar';
import { PatientRightSidebar } from './patient/patient-right-sidebar';
import { PatientTopNavbar } from './patient/patient-top-navbar';

const THEME = ROLE_THEME.patient;

/**
 * Patient dashboard shell (web). Which section is shown comes from the URL
 * (`/dashboard/patient?section=<key>`) and what a section is comes from the registry in
 * components/web/sections/patient.sections.tsx; this file only lays out the sidebar, top bar and content.
 */
export function PatientDashboardWeb() {
  // Opening a section adds a history entry, and the router keeps the previous screen mounted underneath for
  // Back. Only the screen in front renders the dashboard, so hidden copies hold no data and do not poll.
  const focused = useIsFocused();
  if (!focused) return <View style={styles.container} />;
  return <PatientShell />;
}

function PatientShell() {
  const nav = useSectionNav(registry);
  const layout = useShellLayout();
  const notifications = useNotificationActions(nav);
  const messageDoctor = useMessageDoctor(nav);
  const { navigate } = nav;
  const { closeDrawer } = layout;

  const navigateFromDrawer = useCallback((key: string) => {
    closeDrawer();
    // The drawer is closed in the copy the navigation mounts, so focus goes back to its menu button.
    rememberNavFocus(MENU_FOCUS_KEY);
    navigate(key);
  }, [closeDrawer, navigate]);
  const openProfile = useCallback(() => navigate('profile-settings'), [navigate]);

  return (
    <SectionNavContext.Provider value={nav}>
      <View style={styles.container}>
        {!layout.isSmall && (
          <PatientSidebar collapsed={layout.isCollapsed} activeKey={nav.section} onNavigate={navigate} />
        )}

        <View style={styles.main}>
          {/* The bell opens a message notification's conversation; every other one follows its actionUrl. */}
          <PatientTopNavbar
            showMenuButton={layout.isSmall}
            menuExpanded={layout.drawerOpen}
            onMenuPress={layout.openDrawer}
            onProfilePress={openProfile}
            onOpenThread={notifications.onOpenThread}
            onNotificationLink={notifications.onNavigate}
          />
          <View style={styles.body}>
            <SectionHost nav={nav} theme={THEME} />
            {layout.isLarge && nav.def?.rightSidebar && (
              <PatientRightSidebar onNavigate={navigate} onMessageDoctor={messageDoctor} />
            )}
          </View>
        </View>

        {/* Phones: the sidebar is a drawer with a backdrop, Escape to close, focus kept inside and returned. */}
        <NavDrawer open={layout.drawerOpen} onClose={closeDrawer}>
          <PatientSidebar collapsed={false} activeKey={nav.section} onNavigate={navigateFromDrawer} />
        </NavDrawer>
      </View>
    </SectionNavContext.Provider>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, flexDirection: 'row', backgroundColor: '#F9FAFB' },
  main:      { flex: 1, flexDirection: 'column', minWidth: 0 },
  body:      { flex: 1, flexDirection: 'row', minWidth: 0 },
});
