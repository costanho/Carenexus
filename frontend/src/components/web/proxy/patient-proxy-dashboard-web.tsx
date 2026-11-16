import { useIsFocused } from 'expo-router';
import { useCallback } from 'react';
import { StyleSheet, View } from 'react-native';
import { type CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import { SectionNavContext, useSectionNav } from '@/hooks/use-section-nav';
import { ROLE_THEME } from '@/lib/nav/sections';
import { NavDrawer } from '../layout/nav-drawer';
import { SectionHost } from '../layout/section-host';
import { MENU_FOCUS_KEY, rememberNavFocus, useNotificationActions, useShellLayout } from '../layout/shell-support';
import { DependentsLoader } from '../sections/proxy-dependents';
import { openPatientProfile, registry } from '../sections/proxy.sections';
import { PatientProxySidebar } from './patient/patient-proxy-sidebar';
import { PatientProxyTopNavbar } from './patient/patient-proxy-topnavbar';

const THEME = ROLE_THEME.proxy;

/**
 * Caregiver (proxy) dashboard shell (web). Which section is shown comes from the URL
 * (`/dashboard/proxy?section=<key>&patient=<PAT id>`) and what a section is comes from the registry in
 * components/web/sections/proxy.sections.tsx; this file only lays out the sidebar, top bar and content.
 */
export function PatientProxyDashboardWeb() {
  // Opening a section adds a history entry, and the router keeps the previous screen mounted underneath for
  // Back. Only the screen in front renders the dashboard, so hidden copies hold no data and do not poll.
  const focused = useIsFocused();
  if (!focused) return <View style={styles.container} />;
  return <ProxyShell />;
}

function ProxyShell() {
  const nav = useSectionNav(registry);
  const layout = useShellLayout();
  const notifications = useNotificationActions(nav);
  const { navigate } = nav;
  const { closeDrawer } = layout;

  const selectPatient = useCallback((dep: CaregiverDependent) => openPatientProfile(navigate, dep), [navigate]);
  const navigateFromDrawer = useCallback((key: string) => {
    closeDrawer();
    // The drawer is closed in the copy the navigation mounts, so focus goes back to its menu button.
    rememberNavFocus(MENU_FOCUS_KEY);
    navigate(key);
  }, [closeDrawer, navigate]);
  const selectPatientFromDrawer = useCallback((dep: CaregiverDependent) => {
    closeDrawer();
    rememberNavFocus(MENU_FOCUS_KEY);
    openPatientProfile(navigate, dep);
  }, [closeDrawer, navigate]);

  return (
    <SectionNavContext.Provider value={nav}>
      {/* Loads the linked patients for the sidebar (both the rail and the drawer read the same list). */}
      <DependentsLoader />
      <View style={styles.container}>
        {!layout.isSmall && (
          <PatientProxySidebar
            collapsed={layout.isCollapsed}
            activeKey={nav.section}
            onNavigate={navigate}
            onSelectPatient={selectPatient}
          />
        )}
        <View style={styles.main}>
          <PatientProxyTopNavbar
            onNavigate={navigate}
            onOpenThread={notifications.onOpenThread}
            onNotificationLink={notifications.onNavigate}
            onMenuPress={layout.isSmall ? layout.openDrawer : undefined}
          />
          <SectionHost nav={nav} theme={THEME} />
        </View>
        {/* Phones: the sidebar is a drawer behind the menu button, so pages get the full width. */}
        <NavDrawer open={layout.drawerOpen} onClose={closeDrawer}>
          <PatientProxySidebar
            collapsed={false}
            activeKey={nav.section}
            onNavigate={navigateFromDrawer}
            onSelectPatient={selectPatientFromDrawer}
          />
        </NavDrawer>
      </View>
    </SectionNavContext.Provider>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, flexDirection: 'row', backgroundColor: '#F9FAFB' },
  main:      { flex: 1, flexDirection: 'column', minWidth: 0 },
});
