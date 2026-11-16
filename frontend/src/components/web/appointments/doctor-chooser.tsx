import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import {
  ariaPressed, Banner, Button, colors, decorative, fontSize, Icon, LoadingView, radius, space, TextField, useUiTheme,
} from '@/components/web/ui';
import { DOCTOR_SEARCH_MAX_LENGTH, type Doctor, doctorInitial, useDoctorDirectory } from '@/hooks/use-doctors';
import { primeDoctorNames } from '@/lib/doctors/names';

/** Doctors shown per request; "Show more doctors" loads the next page. */
const PAGE_SIZE = 6;
const SEARCH_DELAY_MS = 300;

export interface DoctorChooserProps {
  /** The chosen doctor, or null. Kept by the parent so the choice survives a search that no longer lists them. */
  selected:  Doctor | null;
  onSelect:  (doctor: Doctor) => void;
  disabled?: boolean;
}

function DoctorCard({ doctor, selected, disabled, onPress }: {
  doctor: Doctor; selected: boolean; disabled: boolean; onPress: () => void;
}) {
  const { accent, accentBg } = useUiTheme();
  const name = doctor.full_name || 'Doctor (name not set)';
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityLabel={doctor.specialization ? `${name}, ${doctor.specialization}` : name}
      accessibilityState={{ selected, disabled }}
      {...ariaPressed(selected)}
      style={[styles.card, selected && { borderColor: accent, backgroundColor: accentBg }]}
    >
      <View style={[styles.avatar, { backgroundColor: accentBg, borderColor: selected ? accent : colors.border }]} {...decorative}>
        <Text style={[styles.avatarText, { color: accent }]}>{doctorInitial(doctor.full_name)}</Text>
      </View>
      <View style={styles.cardText}>
        <Text style={[styles.name, selected && { color: accent }]}>{name}</Text>
        {doctor.specialization ? <Text style={styles.specialization}>{doctor.specialization}</Text> : null}
      </View>
      {selected ? <Icon name="checkmark-circle" size={20} color={accent} /> : null}
    </Pressable>
  );
}

/**
 * Chooses a doctor from the directory of bookable doctors (GET /api/doctors/profile/directory). The search box
 * filters on the server by name or specialization, so it works however many doctors the clinic has.
 */
export function DoctorChooser({ selected, onSelect, disabled = false }: DoctorChooserProps) {
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');

  // The directory is asked a moment after typing stops, so each keystroke is not a request.
  useEffect(() => {
    const text = search.trim();
    if (text === query) return;
    const timer = setTimeout(() => setQuery(text), text ? SEARCH_DELAY_MS : 0);
    return () => clearTimeout(timer);
  }, [search, query]);

  const directory = useDoctorDirectory(query, { pageSize: PAGE_SIZE });
  const { items } = directory;

  // Names seen here are remembered, so appointment lists never need to look these doctors up one by one.
  useEffect(() => { primeDoctorNames(items); }, [items]);

  const selectedListed = !!selected && items.some(d => d.doctor_system_id === selected.doctor_system_id);
  const remaining = Math.max(0, directory.total - items.length);

  return (
    <View style={styles.wrap}>
      <TextField
        label="Search doctors"
        value={search}
        onChange={setSearch}
        placeholder="Search by name or specialization…"
        maxLength={DOCTOR_SEARCH_MAX_LENGTH}
        autoCapitalize="none"
        disabled={disabled}
      />

      {selected && !selectedListed ? (
        <DoctorCard doctor={selected} selected disabled={disabled} onPress={() => onSelect(selected)} />
      ) : null}

      {directory.loading ? (
        <LoadingView compact label="Loading doctors…" />
      ) : directory.error && items.length === 0 ? (
        <Banner tone="danger" text="Could not load doctors." action={{ label: 'Retry', onPress: directory.refetch }} />
      ) : items.length === 0 ? (
        <Text style={styles.note}>
          {query ? 'No doctors match your search.' : 'No doctors are available for booking right now.'}
        </Text>
      ) : (
        <>
          <View style={styles.list} role="group" aria-label="Doctors">
            {items.map(d => (
              <DoctorCard
                key={d.doctor_system_id}
                doctor={d}
                selected={selected?.doctor_system_id === d.doctor_system_id}
                disabled={disabled}
                onPress={() => onSelect(d)}
              />
            ))}
          </View>
          {directory.error ? (
            <Banner tone="danger" text={directory.error} action={{ label: 'Retry', onPress: directory.loadMore }} />
          ) : null}
          {directory.hasMore ? (
            <Button
              label={remaining > 0 ? `Show more doctors (${remaining} more)` : 'Show more doctors'}
              variant="secondary"
              icon="chevron-down"
              onPress={directory.loadMore}
              loading={directory.loadingMore}
              fullWidth
            />
          ) : null}
        </>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: space.md, minWidth: 0 },
  list: { gap: space.sm },
  note: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  card: {
    flexDirection: 'row', alignItems: 'center', gap: space.md, padding: space.md, minWidth: 0,
    borderWidth: 1, borderColor: colors.borderInput, borderRadius: radius.lg, backgroundColor: colors.surface,
  },
  avatar:     { width: 40, height: 40, borderRadius: radius.pill, alignItems: 'center', justifyContent: 'center', borderWidth: 2, flexShrink: 0 },
  avatarText: { fontSize: fontSize.lg, fontWeight: '700' },
  cardText:   { flex: 1, minWidth: 0 },
  name:           { fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  specialization: { fontSize: fontSize.xs, color: colors.textMuted, marginTop: 2 },
});
