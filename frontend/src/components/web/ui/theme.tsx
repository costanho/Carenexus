import { createContext, type ReactNode, useContext } from 'react';
import { type AppRole, DEFAULT_ROLE_THEME, ROLE_THEME, type RoleTheme } from './tokens';

// The role accent the primitives draw with. A screen receives `theme` in its SectionProps and hands it to
// <Screen theme={theme}>, which provides it to every primitive below (including modals and side panels).

const UiThemeContext = createContext<RoleTheme>(DEFAULT_ROLE_THEME);

export function UiThemeProvider({ theme, role, children }: {
  /** The theme to use; wins over `role`. */
  theme?:   RoleTheme;
  /** Shortcut for `ROLE_THEME[role]`. */
  role?:    AppRole;
  children: ReactNode;
}) {
  const inherited = useContext(UiThemeContext);
  const value = theme ?? (role ? ROLE_THEME[role] : inherited);
  return <UiThemeContext.Provider value={value}>{children}</UiThemeContext.Provider>;
}

/** The accent colours of the surrounding dashboard (patient teal when no provider is mounted). */
export function useUiTheme(): RoleTheme {
  return useContext(UiThemeContext);
}
