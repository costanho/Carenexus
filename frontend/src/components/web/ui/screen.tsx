import type { ReactNode } from 'react';
import { ScrollView, StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import { ariaLevel } from './a11y';
import { UiThemeProvider } from './theme';
import { type AppRole, colors, fontSize, PHONE_BREAKPOINT, type RoleTheme, space } from './tokens';

export interface ScreenProps {
  title:     string;
  subtitle?: string;
  /** Buttons shown beside the title (they wrap under it on a phone). */
  actions?:  ReactNode;
  children:  ReactNode;
  /** Scroll the page when the content is taller than the window. Default true; pass false when the screen manages its own scrolling. */
  scroll?:   boolean;
  /** The dashboard's accent colours (`SectionProps.theme`); provided to every primitive inside the screen. */
  theme?:    RoleTheme;
  /** Shortcut for the role's default theme when no `theme` is passed. */
  role?:     AppRole;
  /** Heading level of the title. Default 1: the screen title is the page's main heading. */
  headingLevel?: 1 | 2;
}

/** The frame of a dashboard section: page heading, optional actions and padded content that fits a 375 px window. */
export function Screen({ title, subtitle, actions, children, scroll = true, theme, role, headingLevel = 1 }: ScreenProps) {
  const { width } = useWindowDimensions();
  const pad = width < PHONE_BREAKPOINT ? space.lg : space.xl;

  const header = (
    <View style={styles.header}>
      <View style={styles.titles}>
        <Text style={styles.title} accessibilityRole="header" {...ariaLevel(headingLevel)}>{title}</Text>
        {subtitle ? <Text style={styles.subtitle}>{subtitle}</Text> : null}
      </View>
      {actions ? <View style={styles.actions}>{actions}</View> : null}
    </View>
  );

  return (
    <UiThemeProvider theme={theme} role={role}>
      {scroll ? (
        <ScrollView
          style={styles.fill}
          contentContainerStyle={[styles.content, { padding: pad }]}
          keyboardShouldPersistTaps="handled"
        >
          {header}
          {children}
        </ScrollView>
      ) : (
        <View style={[styles.fill, styles.content, styles.fixed, { padding: pad }]}>
          {header}
          {children}
        </View>
      )}
    </UiThemeProvider>
  );
}

const styles = StyleSheet.create({
  fill:     { flex: 1, backgroundColor: colors.bg },
  content:  { gap: space.lg, width: '100%', maxWidth: 1200, alignSelf: 'center' },
  fixed:    { minHeight: 0 },
  header:   { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.md },
  // minWidth keeps the title on its own line when the actions do not fit beside it on a phone.
  titles:   { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, gap: space.xs },
  title:    { fontSize: fontSize.title, fontWeight: '700', color: colors.text },
  subtitle: { fontSize: fontSize.md, color: colors.textMuted, lineHeight: 20 },
  actions:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, maxWidth: '100%' },
});
