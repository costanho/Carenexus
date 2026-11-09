import { Ionicons } from '@expo/vector-icons';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ScrollView, StyleSheet, Text, TouchableOpacity, useWindowDimensions, View } from 'react-native';
import { isForbidden, type CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import { tidyDoctorName, useDoctors } from '@/hooks/use-doctors';

// Shared pieces for the caregiver's per-loved-one record screens (schedule, medications, documents,
// health summary, care plans, settings). Everything is module scope so nothing remounts on re-render.

export const WHITE        = '#FFFFFF';
export const TEXT         = '#111827';
export const GRAY         = '#6B7280';
export const GRAY2        = '#6B7280';
export const BORDER       = '#E5E7EB';
export const BG           = '#F9FAFB';
export const PURPLE       = '#7C3AED';
export const PURPLE_BG    = '#F5F3FF';
export const PURPLE_LIGHT = '#EDE9FE';
export const GREEN        = '#047857';
export const ORANGE       = '#B45309';
export const RED          = '#B91C1C';

export type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

export function useScale() {
  const { width } = useWindowDimensions();
  const s = (n: number) => Math.round(n * (width / 390));
  return { s };
}

const AVATAR_COLORS = ['#4F46E5', '#0F766E', '#B45309', '#B91C1C', '#6D28D9', '#047857', '#0369A1'];
export function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < (name?.length ?? 0); i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}
export function initials(name: string | null | undefined) {
  return (name ?? '').split(' ').filter(Boolean).map(w => w[0]).join('').slice(0, 2).toUpperCase() || '?';
}
export function capitalize(s: string | null | undefined) {
  if (!s) return '—';
  const t = s.replace(/_/g, ' ');
  return t.charAt(0).toUpperCase() + t.slice(1).toLowerCase();
}
export function depName(dep: CaregiverDependent | null | undefined) {
  return dep?.patientName?.trim() || 'Your loved one';
}
export function depFirstName(dep: CaregiverDependent | null | undefined) {
  return dep?.patientName?.trim().split(/\s+/)[0] || 'your loved one';
}
/** "A", "A and B", "A, B and C". */
export function joinNames(names: string[]) {
  if (names.length <= 1) return names[0] ?? '';
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
}

export type ViewFlag =
  | 'canViewRecords'
  | 'canViewAppointments'
  | 'canViewMedications'
  | 'canViewLabResults'
  | 'canViewImaging'
  | 'canViewConsultations';

/**
 * Same rule as the backend (PatientPermission.isGrantedBy): FULL_ACCESS grants every view permission,
 * otherwise the flag must be on. The dependents list only holds active grants.
 */
export function canView(dep: CaregiverDependent, flag: ViewFlag): boolean {
  return dep.accessLevel === 'FULL_ACCESS' || !!dep[flag];
}

/** Doctor display names from the shared active-doctor list (loadActiveDoctors). Null when unknown: never a raw id. */
export function useDoctorNames(): (doctorSystemId: string | null | undefined) => string | null {
  const { doctors } = useDoctors();
  const names = useMemo(
    () => new Map(doctors.map(d => [d.doctor_system_id, tidyDoctorName(d.full_name)])),
    [doctors],
  );
  return useCallback((id) => (id ? names.get(id) || null : null), [names]);
}

// ─── Per-loved-one records (one request per loved one the caregiver may see) ───

interface RecordsState<T> {
  items:   T[];
  /** True until the first round of requests has finished. */
  loading: boolean;
  error:   string | null;
  /** Loved ones the server refused (403) although their grant looked allowed. */
  denied:  string[];
  refetch: () => void;
}

/**
 * Loads `load(dep)` for every loved one whose grant has `flag`, and merges the results. A 403 marks that
 * loved one as not shared; any other failure sets `error` (the records that did load are kept).
 * `load` must be a stable (module-scope) function.
 */
export function usePerDependentRecords<T>(
  dependents: CaregiverDependent[],
  flag: ViewFlag,
  load: (dep: CaregiverDependent) => Promise<T[]>,
  failMessage: string,
): RecordsState<T> {
  const [items,     setItems]     = useState<T[]>([]);
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  const [fetching,  setFetching]  = useState(false);
  const [error,     setError]     = useState<string | null>(null);
  const [denied,    setDenied]    = useState<string[]>([]);
  const [tick,      setTick]      = useState(0);

  const depsRef = useRef(dependents);
  depsRef.current = dependents;
  const key = dependents.map(d => `${d.patientSystemId}:${canView(d, flag) ? 1 : 0}`).join(',');

  useEffect(() => {
    const allowed = depsRef.current.filter(d => canView(d, flag));
    if (allowed.length === 0) {
      setItems([]); setDenied([]); setError(null); setLoadedKey(key); setFetching(false);
      return;
    }
    let cancelled = false;
    setFetching(true);
    Promise.allSettled(allowed.map(d => load(d)))
      .then(settled => {
        if (cancelled) return;
        const all: T[] = [];
        const refused: string[] = [];
        let failed = 0;
        settled.forEach((res, i) => {
          if (res.status === 'fulfilled') all.push(...res.value);
          else if (isForbidden(res.reason)) refused.push(allowed[i].patientSystemId);
          else failed++;
        });
        setItems(all);
        setDenied(refused);
        setError(failed > 0 ? failMessage : null);
        setLoadedKey(key);
      })
      .finally(() => { if (!cancelled) setFetching(false); });
    return () => { cancelled = true; };
    // `key` captures the loved ones and their permission; `load`, `flag` and `failMessage` are constants per screen
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, tick]);

  const refetch = useCallback(() => setTick(t => t + 1), []);
  // Loading until the results match the current loved ones (no empty-state flash before the first request starts)
  return { items, loading: fetching || loadedKey !== key, error, denied, refetch };
}

// ─── Header ───────────────────────────────────────────────────────────────────

export function ScreenHeader({ title, sub, onBack, backLabel = 'Back' }: {
  title: string; sub?: string; onBack?: () => void; backLabel?: string;
}) {
  const { s } = useScale();
  return (
    <View style={{ marginBottom: s(14), gap: s(4) }}>
      {onBack && (
        <TouchableOpacity
          style={[styles.backBtn, { gap: s(5), paddingVertical: s(4), marginBottom: s(4) }]}
          onPress={onBack}
          activeOpacity={0.7}
          accessibilityRole="button"
          accessibilityLabel={backLabel}
          hitSlop={8}
        >
          <Ionicons name="arrow-back-outline" size={s(18)} color={PURPLE} importantForAccessibility="no" />
          <Text style={[styles.backText, { fontSize: s(14) }]}>Back</Text>
        </TouchableOpacity>
      )}
      <Text style={[styles.title, { fontSize: s(20) }]} accessibilityRole="header">{title}</Text>
      {sub ? <Text style={[styles.sub, { fontSize: s(12) }]}>{sub}</Text> : null}
    </View>
  );
}

// ─── Loved-one picker ─────────────────────────────────────────────────────────

export const ALL = 'all';

/** Horizontal chips to pick one loved one (or all). Hidden when there is only one loved one. */
export function DependentPicker({ dependents, value, onChange, includeAll = true }: {
  dependents: CaregiverDependent[];
  value: string;
  onChange: (patientSystemId: string) => void;
  includeAll?: boolean;
}) {
  const { s } = useScale();
  if (dependents.length < 2) return null;
  const options = [
    ...(includeAll ? [{ id: ALL, label: 'All loved ones', a11y: 'All loved ones' }] : []),
    ...dependents.map(d => ({ id: d.patientSystemId, label: depName(d), a11y: depName(d) })),
  ];
  return (
    <ScrollView
      horizontal
      showsHorizontalScrollIndicator={false}
      style={{ marginBottom: s(12), flexGrow: 0 }}
      contentContainerStyle={{ gap: s(8) }}
      accessibilityRole="tablist"
      accessibilityLabel="Choose a loved one"
    >
      {options.map(o => {
        const selected = value === o.id;
        return (
          <TouchableOpacity
            key={o.id}
            style={[styles.chip, { borderRadius: s(18), paddingHorizontal: s(12), paddingVertical: s(7), gap: s(6) }, selected && styles.chipActive]}
            onPress={() => onChange(o.id)}
            activeOpacity={0.7}
            accessibilityRole="tab"
            accessibilityLabel={o.a11y}
            accessibilityState={{ selected }}
          >
            {o.id !== ALL && (
              <View
                style={{ width: s(20), height: s(20), borderRadius: s(10), backgroundColor: avatarColor(o.label), alignItems: 'center', justifyContent: 'center' }}
                importantForAccessibility="no-hide-descendants"
                accessibilityElementsHidden
              >
                <Text style={{ color: WHITE, fontSize: s(9), fontWeight: '700' }}>{initials(o.label)}</Text>
              </View>
            )}
            <Text style={[styles.chipText, { fontSize: s(12), maxWidth: s(150) }, selected && styles.chipTextActive]} numberOfLines={1}>{o.label}</Text>
          </TouchableOpacity>
        );
      })}
    </ScrollView>
  );
}

/** Keeps a picker value valid: falls back to `fallback` once the loved ones load and the value is not among them. */
export function usePickedDependent(dependents: CaregiverDependent[], initial: string | undefined, fallback: 'all' | 'first') {
  const [value, setValue] = useState<string>(initial ?? (fallback === 'all' ? ALL : ''));
  useEffect(() => {
    if (dependents.length === 0) return;
    const valid = (value === ALL && fallback === 'all') || dependents.some(d => d.patientSystemId === value);
    if (!valid) setValue(fallback === 'all' ? ALL : dependents[0].patientSystemId);
  }, [dependents, value, fallback]);
  const selected = value === ALL ? null : dependents.find(d => d.patientSystemId === value) ?? null;
  return { value, setValue, selected };
}

// ─── Filter tabs ──────────────────────────────────────────────────────────────

export function FilterTabs<T extends string>({ options, value, onChange, label }: {
  options: { key: T; label: string }[];
  value: T;
  onChange: (key: T) => void;
  label: string;
}) {
  const { s } = useScale();
  return (
    <View style={[styles.filterRow, { borderRadius: s(10), padding: s(3), marginBottom: s(12), gap: s(3) }]} accessibilityRole="tablist" accessibilityLabel={label}>
      {options.map(o => {
        const selected = value === o.key;
        return (
          <TouchableOpacity
            key={o.key}
            style={[styles.filterTab, { borderRadius: s(8), paddingVertical: s(7), paddingHorizontal: s(4) }, selected && styles.filterTabActive]}
            onPress={() => onChange(o.key)}
            activeOpacity={0.7}
            accessibilityRole="tab"
            accessibilityLabel={o.label}
            accessibilityState={{ selected }}
          >
            <Text style={[styles.filterText, { fontSize: s(12) }, selected && styles.filterTextActive]} numberOfLines={1}>{o.label}</Text>
          </TouchableOpacity>
        );
      })}
    </View>
  );
}

// ─── Stats ────────────────────────────────────────────────────────────────────

export function StatRow({ items }: { items: { label: string; value: string; color: string; bg: string }[] }) {
  const { s } = useScale();
  return (
    <View style={{ flexDirection: 'row', gap: s(8), marginBottom: s(12) }}>
      {items.map(it => (
        <View
          key={it.label}
          style={{ flex: 1, minWidth: 0, backgroundColor: it.bg, borderRadius: s(12), paddingVertical: s(10), paddingHorizontal: s(6), alignItems: 'center' }}
          accessible
          accessibilityLabel={`${it.label}: ${it.value}`}
        >
          <Text style={{ fontSize: s(20), fontWeight: '700', color: it.color }} numberOfLines={1}>{it.value}</Text>
          <Text style={{ fontSize: s(11), fontWeight: '500', color: it.color, marginTop: s(2) }} numberOfLines={1}>{it.label}</Text>
        </View>
      ))}
    </View>
  );
}

// ─── Not shared ───────────────────────────────────────────────────────────────

/** Inline note naming the loved ones who have not shared this kind of record. */
export function NotSharedBanner({ text }: { text: string }) {
  const { s } = useScale();
  return (
    <View style={[styles.notice, { borderRadius: s(10), padding: s(10), gap: s(8), marginBottom: s(12) }]} accessible accessibilityLabel={text}>
      <Ionicons name="lock-closed-outline" size={s(14)} color={ORANGE} importantForAccessibility="no" />
      <Text style={[styles.noticeText, { fontSize: s(12) }]}>{text}</Text>
    </View>
  );
}

/** Whole-panel state when the chosen loved one (or every loved one) has not shared this kind of record. */
export function NotSharedState({ title, sub }: { title: string; sub?: string }) {
  const { s } = useScale();
  return (
    <View style={[styles.notShared, { borderRadius: s(14), padding: s(20), gap: s(8) }]} accessible accessibilityLabel={[title, sub].filter(Boolean).join('. ')}>
      <View style={{ width: s(48), height: s(48), borderRadius: s(24), backgroundColor: '#FEF3C7', alignItems: 'center', justifyContent: 'center' }}>
        <Ionicons name="lock-closed-outline" size={s(22)} color={ORANGE} importantForAccessibility="no" />
      </View>
      <Text style={{ fontSize: s(14), fontWeight: '700', color: TEXT, textAlign: 'center' }}>{title}</Text>
      {sub ? <Text style={{ fontSize: s(12), color: GRAY, textAlign: 'center', lineHeight: s(17) }}>{sub}</Text> : null}
    </View>
  );
}

// ─── Section card ─────────────────────────────────────────────────────────────

export function SectionCard({ title, children }: { title?: string; children: React.ReactNode }) {
  const { s } = useScale();
  return (
    <View style={[styles.card, { borderRadius: s(14), padding: s(14), marginBottom: s(12), gap: s(10) }]}>
      {title ? <Text style={[styles.cardTitle, { fontSize: s(14) }]} accessibilityRole="header" numberOfLines={2}>{title}</Text> : null}
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  backBtn:  { flexDirection: 'row', alignItems: 'center', alignSelf: 'flex-start' },
  backText: { color: PURPLE, fontWeight: '500' },
  title:    { fontWeight: '700', color: TEXT },
  sub:      { color: GRAY },

  chip:           { flexDirection: 'row', alignItems: 'center', borderWidth: 1, borderColor: BORDER, backgroundColor: WHITE },
  chipActive:     { borderColor: PURPLE, backgroundColor: PURPLE_BG },
  chipText:       { color: GRAY, fontWeight: '500' },
  chipTextActive: { color: PURPLE, fontWeight: '700' },

  filterRow:        { flexDirection: 'row', backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  filterTab:        { flex: 1, minWidth: 0, alignItems: 'center' },
  filterTabActive:  { backgroundColor: PURPLE },
  filterText:       { color: GRAY, fontWeight: '500' },
  filterTextActive: { color: WHITE, fontWeight: '600' },

  notice:     { flexDirection: 'row', alignItems: 'center', backgroundColor: '#FFFBEB', borderWidth: 1, borderColor: '#FDE68A' },
  noticeText: { color: TEXT, flex: 1, lineHeight: 17 },
  notShared:  { alignItems: 'center', backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },

  card:      { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER },
  cardTitle: { fontWeight: '700', color: TEXT },
});
