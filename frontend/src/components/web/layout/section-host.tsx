import { Suspense, useMemo } from 'react';
import { ActivityIndicator, StyleSheet, View } from 'react-native';
import type { SectionNav } from '@/hooks/use-section-nav';
import { useSectionFocusRestore } from '@/lib/nav/nav-focus';
import type { RoleTheme, SectionDef, SectionProps } from '@/lib/nav/sections';
import { ErrorBoundary } from './error-boundary';

/** Centered spinner shown while a section's code is loading. */
export function SectionLoader({ color }: { color: string }) {
  return (
    <View style={styles.loader} role="status" aria-label="Loading">
      <ActivityIndicator size="large" color={color} accessibilityLabel="Loading" />
    </View>
  );
}

/** Runs a registry entry's `render` as its own component, so an entry may use hooks. */
function SectionView({ def, props }: { def: SectionDef; props: SectionProps }) {
  return def.render(props);
}

// Focusable from script only (never a Tab stop): the container takes focus after a navigation.
const SCRIPT_FOCUS = { tabIndex: -1 } as object;

/**
 * Renders the active section of a dashboard: the registry entry's `render`, inside Suspense (lazy screens)
 * and an error boundary (a failing section leaves the sidebar and top bar usable).
 *
 * The section sits in the page's main landmark, named after the section. Opening a section mounts a fresh
 * copy of the dashboard; unless the sidebar item or the menu button that started the navigation takes focus
 * back, this container does, so keyboard and screen-reader users continue at the new section.
 */
export function SectionHost({ nav, theme }: { nav: SectionNav; theme: RoleTheme }) {
  const { def, role, params, navigate, openThread, homeKey } = nav;
  const containerRef = useSectionFocusRestore();
  const props = useMemo<SectionProps>(
    () => ({ role, params, navigate, openThread, theme }),
    [role, params, navigate, openThread, theme],
  );
  const goHome = useMemo(
    () => (def && def.key !== homeKey ? () => navigate(homeKey) : undefined),
    [def, homeKey, navigate],
  );
  if (!def) return null;
  return (
    <View ref={containerRef as never} style={styles.section} role="main" aria-label={def.label} {...SCRIPT_FOCUS}>
      <ErrorBoundary resetKey={def.key} onHome={goHome} accent={theme.accent}>
        <Suspense fallback={<SectionLoader color={theme.accent} />}>
          <SectionView key={def.key} def={def} props={props} />
        </Suspense>
      </ErrorBoundary>
    </View>
  );
}

const styles = StyleSheet.create({
  loader:  { flex: 1, alignItems: 'center', justifyContent: 'center' },
  // Fills the space the section had as a direct child of the shell. No focus ring: it is not a control.
  section: { flex: 1, minWidth: 0, minHeight: 0, outlineStyle: 'none' as never },
});
