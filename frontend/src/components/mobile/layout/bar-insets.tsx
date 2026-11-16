import type { ReactNode } from 'react';
import { SafeAreaInsetsContext, useSafeAreaInsets } from 'react-native-safe-area-context';

/**
 * Wraps a dashboard's content area so screens inside it do not add a safe-area inset that a navbar
 * already handles. Every mobile screen pads its own header with `insets.top` and its list with
 * `insets.bottom`; under the doctor / caregiver top bar that doubled the status-bar gap, and above the
 * bottom tab bar (which owns the home-indicator inset) it left an empty band.
 *
 * `topBar`: a top navbar is shown above the content (it already covers the status bar).
 * The bottom tab bar is always shown below the content, so the bottom inset is always handled.
 */
export function BarInsets({ topBar, children }: { topBar: boolean; children: ReactNode }) {
  const insets = useSafeAreaInsets();
  return (
    <SafeAreaInsetsContext.Provider value={{ ...insets, top: topBar ? 0 : insets.top, bottom: 0 }}>
      {children}
    </SafeAreaInsetsContext.Provider>
  );
}
