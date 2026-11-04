import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import { StyleSheet, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { useCaregiverDependents } from '@/hooks/use-caregiver-dependents';
import { useDoctorPatients } from '@/hooks/use-doctor-patients';
import type { MessagingMode } from '@/lib/communications/types';
import { BORDER, GRAY, LIGHT, TEXT, useTheme } from './ui';

export interface PatientOption {
  systemId: string;
  name:     string;
}

interface Props {
  mode:     MessagingMode;
  value:    string | null;
  onChange: (option: PatientOption | null) => void;
}

/** Inbox filter by patient: a caregiver's loved ones or a doctor's patients. Nothing for patients. */
export function PatientFilter({ mode, value, onChange }: Props) {
  if (mode === 'CAREGIVER') return <CaregiverFilter value={value} onChange={onChange} />;
  if (mode === 'DOCTOR') return <DoctorFilter value={value} onChange={onChange} />;
  return null;
}

function CaregiverFilter({ value, onChange }: Omit<Props, 'mode'>) {
  const { dependents } = useCaregiverDependents();
  const options = useMemo(() => dependents
    .filter(d => d.isActive && d.accessStatus !== 'REVOKED')
    .map(d => ({ systemId: d.patientSystemId, name: d.patientName ?? 'Your loved one' })), [dependents]);
  return <FilterMenu options={options} value={value} onChange={onChange} />;
}

function DoctorFilter({ value, onChange }: Omit<Props, 'mode'>) {
  const { patients } = useDoctorPatients();
  const options = useMemo(() => patients
    .filter(p => p.patient_system_id)
    .map(p => ({ systemId: p.patient_system_id, name: p.full_name })), [patients]);
  return <FilterMenu options={options} value={value} onChange={onChange} searchable />;
}

const SEARCH_FROM = 8;

function FilterMenu({ options, value, onChange, searchable }: {
  options: PatientOption[]; value: string | null; onChange: (o: PatientOption | null) => void; searchable?: boolean;
}) {
  const { accent, accentBg } = useTheme();
  const [open,  setOpen]  = useState(false);
  const [query, setQuery] = useState('');
  if (options.length === 0) return null;

  const current = options.find(o => o.systemId === value) ?? null;
  const currentName = current?.name ?? 'All patients';
  const q = query.trim().toLowerCase();
  const shown = q ? options.filter(o => o.name.toLowerCase().includes(q)) : options;
  const withSearch = searchable && options.length > SEARCH_FROM;

  function choose(o: PatientOption | null) {
    onChange(o);
    setOpen(false);
    setQuery('');
  }

  return (
    <View style={styles.wrap}>
      <TouchableOpacity
        style={[styles.toggle, value && { borderColor: accent, backgroundColor: accentBg }]}
        onPress={() => setOpen(o => !o)}
        activeOpacity={0.8}
        accessibilityRole="button"
        accessibilityLabel={`Filter by patient: ${currentName}`}
        accessibilityState={{ expanded: open }}
        aria-expanded={open}
      >
        <Ionicons name="person-circle-outline" size={15} color={value ? accent : GRAY} importantForAccessibility="no" />
        <Text style={[styles.toggleText, value && { color: accent }]} numberOfLines={1}>Patient: {currentName}</Text>
        <Ionicons name={open ? 'chevron-up' : 'chevron-down'} size={14} color={GRAY} importantForAccessibility="no" />
      </TouchableOpacity>

      {open && (
        <View style={styles.menu}>
          {withSearch && (
            <TextInput
              style={styles.search}
              value={query}
              onChangeText={setQuery}
              placeholder="Search patients by name"
              placeholderTextColor={LIGHT}
              accessibilityLabel="Search patients to filter by"
            />
          )}
          <View accessibilityRole="radiogroup" accessibilityLabel="Filter by patient">
            {[null, ...shown].map(o => {
              const on = (o?.systemId ?? null) === value;
              const name = o?.name ?? 'All patients';
              return (
                <TouchableOpacity
                  key={o?.systemId ?? 'all'}
                  style={[styles.option, on && { backgroundColor: accentBg }]}
                  onPress={() => choose(o)}
                  accessibilityRole="radio"
                  accessibilityState={{ checked: on }}
                  aria-checked={on}
                  accessibilityLabel={name}
                >
                  <Ionicons name={on ? 'radio-button-on' : 'radio-button-off'} size={16} color={on ? accent : LIGHT} importantForAccessibility="no" />
                  <Text style={styles.optionText} numberOfLines={1}>{name}</Text>
                </TouchableOpacity>
              );
            })}
            {shown.length === 0 && <Text style={styles.note}>No patients match "{query}".</Text>}
          </View>
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:       { gap: 6 },
  toggle:     { flexDirection: 'row', alignItems: 'center', gap: 6, alignSelf: 'flex-start', maxWidth: '100%', borderWidth: 1, borderColor: BORDER, borderRadius: 16, paddingHorizontal: 10, paddingVertical: 5 },
  toggleText: { fontSize: 12, color: TEXT, fontWeight: '600', flexShrink: 1 },
  menu:       { borderWidth: 1, borderColor: BORDER, borderRadius: 8, paddingVertical: 4, maxHeight: 260, overflow: 'scroll' },
  search:     { marginHorizontal: 8, marginVertical: 4, borderWidth: 1, borderColor: BORDER, borderRadius: 6, paddingHorizontal: 10, paddingVertical: 7, fontSize: 13, color: TEXT },
  option:     { flexDirection: 'row', alignItems: 'center', gap: 8, paddingHorizontal: 10, paddingVertical: 7 },
  optionText: { fontSize: 13, color: TEXT, flexShrink: 1 },
  note:       { fontSize: 12, color: GRAY, paddingHorizontal: 10, paddingVertical: 6 },
});
