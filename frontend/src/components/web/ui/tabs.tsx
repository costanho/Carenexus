import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { webOnly, type WebKeyEvent } from './a11y';
import { useUiTheme } from './theme';
import { colors, CONTROL_HEIGHT, fontSize, radius, space } from './tokens';

export interface TabItem<K extends string = string> {
  key:    K;
  label:  string;
  /** A count shown after the label (unread, pending). Zero and undefined show nothing. */
  badge?: number;
}

export interface TabsProps<K extends string = string> {
  tabs:     readonly TabItem<K>[];
  active:   K;
  onChange: (key: K) => void;
  /** Names the tab list for assistive technology, for example "Record types". */
  label?:   string;
}

function moveFocus(event: WebKeyEvent, toIndex: number) {
  const current = event.currentTarget as { parentElement?: { querySelectorAll?: (s: string) => ArrayLike<{ focus?: () => void }> } } | null;
  const all = current?.parentElement?.querySelectorAll?.('[role="tab"]');
  all?.[toIndex]?.focus?.();
}

/**
 * A row of tabs. Keep the active key in the URL (`params.tab`) so a refresh and the Back button keep the tab.
 * The row scrolls sideways when the tabs do not fit, so it never widens the page on a phone.
 * Keyboard: Tab reaches the active tab (the first tab when `active` matches none); Left and Right (Home, End)
 * move between tabs and select them.
 */
export function Tabs<K extends string = string>({ tabs, active, onChange, label }: TabsProps<K>) {
  const { accent, accentBg } = useUiTheme();
  // Exactly one tab is in the Tab order. With a stale or mistyped `?tab=` value no tab is selected, and the
  // first one stands in so the row can still be reached from the keyboard.
  const focusKey = tabs.some(t => t.key === active) ? active : tabs[0]?.key;

  const onKeyDown = (index: number) => (event: WebKeyEvent) => {
    let next = -1;
    if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
    else if (event.key === 'ArrowLeft') next = (index - 1 + tabs.length) % tabs.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = tabs.length - 1;
    else if (event.key === ' ' || event.key === 'Spacebar') next = index;
    if (next < 0) return;
    event.preventDefault();
    if (tabs[next].key !== active) onChange(tabs[next].key);
    moveFocus(event, next);
  };

  return (
    <ScrollView
      horizontal
      showsHorizontalScrollIndicator={false}
      style={styles.scroll}
      contentContainerStyle={styles.row}
    >
      <View style={styles.list} role="tablist" aria-label={label}>
        {tabs.map((tab, index) => {
          const selected = tab.key === active;
          const count = tab.badge && tab.badge > 0 ? tab.badge : 0;
          return (
            <Pressable
              key={tab.key}
              role="tab"
              aria-selected={selected}
              accessibilityState={{ selected }}
              tabIndex={tab.key === focusKey ? 0 : -1}
              onPress={() => { if (!selected) onChange(tab.key); }}
              style={[styles.tab, selected && { borderBottomColor: accent }]}
              {...webOnly({ onKeyDown: onKeyDown(index) })}
            >
              <Text style={[styles.label, selected && { color: accent, fontWeight: '700' }]}>{tab.label}</Text>
              {count > 0 ? (
                <View style={[styles.badge, { backgroundColor: selected ? accentBg : colors.bgSubtle }]}>
                  <Text style={[styles.badgeText, selected && { color: accent }]}>{count > 99 ? '99+' : count}</Text>
                </View>
              ) : null}
            </Pressable>
          );
        })}
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll: { flexGrow: 0, maxWidth: '100%', borderBottomWidth: 1, borderBottomColor: colors.border },
  row:    { flexGrow: 1 },
  list:   { flexDirection: 'row', gap: space.xs },
  tab: {
    minHeight: CONTROL_HEIGHT, flexDirection: 'row', alignItems: 'center', gap: space.sm,
    paddingHorizontal: space.md, paddingVertical: space.sm,
    borderBottomWidth: 2, borderBottomColor: 'transparent', borderTopLeftRadius: radius.sm, borderTopRightRadius: radius.sm,
  },
  label:     { fontSize: fontSize.md, fontWeight: '500', color: colors.textMuted },
  badge:     { minWidth: 20, height: 20, paddingHorizontal: 6, borderRadius: radius.pill, alignItems: 'center', justifyContent: 'center' },
  badgeText: { fontSize: 11, fontWeight: '700', color: colors.textMuted },
});
