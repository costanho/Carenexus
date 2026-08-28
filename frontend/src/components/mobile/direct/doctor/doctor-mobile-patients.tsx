import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import {
  ActivityIndicator, ScrollView, StyleSheet, Text, TextInput,
  TouchableOpacity, View, useWindowDimensions,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { type DoctorPatient, useDoctorPatients } from '@/hooks/use-doctor-patients';

const NAVY   = '#0D1B2E';
const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const LGRAY  = '#9CA3AF';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const BLUE   = '#4F46E5';
const GREEN  = '#10B981';
const ORANGE = '#F59E0B';
const RED    = '#EF4444';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type HealthFilter = 'ALL' | 'STABLE' | 'MONITOR' | 'CRITICAL';

// ─── Helpers ──────────────────────────────────────────────────────────────────

function healthConfig(h: DoctorPatient['health_status']) {
  switch (h) {
    case 'STABLE':   return { bg: '#D1FAE5', color: GREEN,  border: '#6EE7B7', icon: 'checkmark-circle-outline' as IoniconsName, label: 'Stable'   };
    case 'MONITOR':  return { bg: '#FEF3C7', color: ORANGE, border: '#FDE68A', icon: 'alert-circle-outline'     as IoniconsName, label: 'Monitor'  };
    case 'CRITICAL': return { bg: '#FEE2E2', color: RED,    border: '#FECACA', icon: 'close-circle-outline'     as IoniconsName, label: 'Critical' };
    default:         return { bg: '#F3F4F6', color: LGRAY,  border: BORDER,    icon: 'help-circle-outline'      as IoniconsName, label: 'Unknown'  };
  }
}

function calcAge(dob: string | null): string {
  if (!dob) return '—';
  const years = Math.floor((Date.now() - new Date(dob).getTime()) / (365.25 * 24 * 60 * 60 * 1000));
  return `${years} yrs`;
}

function formatDate(iso: string | null): string {
  if (!iso) return 'Never';
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

function formatMemberSince(iso: string): string {
  return new Date(iso).toLocaleDateString('en-GB', { month: 'short', year: 'numeric' });
}

const AVATAR_COLORS = ['#4F46E5', '#0D9488', '#F59E0B', '#EF4444', '#8B5CF6', '#10B981', '#0EA5E9'];
function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}
function initials(name: string) {
  return name.split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
}

// ─── Main Component ───────────────────────────────────────────────────────────

export default function DoctorMobilePatients() {
  const { patients, loading, error, refetch } = useDoctorPatients();
  const insets = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();

  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));

  const [healthFilter, setHealthFilter] = useState<HealthFilter>('ALL');
  const [search, setSearch] = useState('');

  const stats = useMemo(() => ({
    total:    patients.length,
    stable:   patients.filter(p => p.health_status === 'STABLE').length,
    monitor:  patients.filter(p => p.health_status === 'MONITOR').length,
    critical: patients.filter(p => p.health_status === 'CRITICAL').length,
  }), [patients]);

  const filtered = useMemo(() => (
    patients
      .filter(p => healthFilter === 'ALL' || p.health_status === healthFilter)
      .filter(p => !search || p.full_name.toLowerCase().includes(search.toLowerCase())
        || p.email?.toLowerCase().includes(search.toLowerCase()))
  ), [patients, healthFilter, search]);

  const healthFilters: { key: HealthFilter; label: string; color: string }[] = [
    { key: 'ALL',      label: 'All',      color: BLUE   },
    { key: 'STABLE',   label: 'Stable',   color: GREEN  },
    { key: 'MONITOR',  label: 'Monitor',  color: ORANGE },
    { key: 'CRITICAL', label: 'Critical', color: RED    },
  ];

  // ─── Header ────────────────────────────────────────────────────────────────
  const header = (
    <View style={[{ backgroundColor: NAVY, paddingTop: insets.top + sh(12), paddingBottom: sh(16), paddingHorizontal: s(20) }]}>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
        <View>
          <Text style={{ color: WHITE, fontSize: s(20), fontWeight: '700' }}>My Patients</Text>
          <Text style={{ color: '#94A3B8', fontSize: s(12), marginTop: sh(2) }}>
            {loading ? 'Loading…' : `${patients.length} patient${patients.length !== 1 ? 's' : ''}`}
          </Text>
        </View>
        <TouchableOpacity
          onPress={refetch}
          activeOpacity={0.7}
          style={{ width: s(38), height: s(38), borderRadius: s(19), backgroundColor: 'rgba(255,255,255,0.1)', alignItems: 'center', justifyContent: 'center' }}
        >
          <Ionicons name="refresh-outline" size={s(20)} color={WHITE} />
        </TouchableOpacity>
      </View>
    </View>
  );

  // ─── Stat Card ─────────────────────────────────────────────────────────────
  function StatChip({ icon, iconBg, iconColor, label, value }: {
    icon: IoniconsName; iconBg: string; iconColor: string; label: string; value: number;
  }) {
    return (
      <View style={{
        backgroundColor: WHITE, borderRadius: s(12), borderWidth: 1, borderColor: BORDER,
        paddingHorizontal: s(14), paddingVertical: sh(6), alignItems: 'center', gap: sh(3),
        minWidth: s(78), alignSelf: 'flex-start',
      }}>
        <View style={{ width: s(26), height: s(26), borderRadius: s(7), backgroundColor: iconBg, alignItems: 'center', justifyContent: 'center' }}>
          <Ionicons name={icon} size={s(15)} color={iconColor} />
        </View>
        <Text style={{ fontSize: s(20), fontWeight: '700', color: TEXT }}>{value}</Text>
        <Text style={{ fontSize: s(10), color: GRAY }}>{label}</Text>
      </View>
    );
  }

  // ─── Patient Card ──────────────────────────────────────────────────────────
  function PatientCard({ patient }: { patient: DoctorPatient }) {
    const hc = healthConfig(patient.health_status);
    const ac = avatarColor(patient.full_name);
    const age = calcAge(patient.date_of_birth);

    return (
      <View style={{
        backgroundColor: WHITE, borderRadius: s(16), borderWidth: 1,
        borderColor: BORDER, overflow: 'hidden', marginBottom: sh(12),
      }}>
        {/* Health status left border accent */}
        <View style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: s(4), backgroundColor: hc.color }} />

        {/* Top section */}
        <View style={{ flexDirection: 'row', alignItems: 'flex-start', gap: s(12), padding: s(16), paddingLeft: s(20) }}>
          {/* Avatar */}
          <View style={{ width: s(48), height: s(48), borderRadius: s(24), backgroundColor: ac, alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
            <Text style={{ color: WHITE, fontSize: s(16), fontWeight: '700' }}>{initials(patient.full_name)}</Text>
          </View>

          {/* Name + meta */}
          <View style={{ flex: 1 }}>
            <Text style={{ fontSize: s(15), fontWeight: '700', color: TEXT, marginBottom: sh(2) }} numberOfLines={1}>
              {patient.full_name}
            </Text>
            <View style={{ flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: s(4) }}>
              <Text style={{ fontSize: s(11), color: GRAY }}>{age}</Text>
              {!!patient.gender && (
                <>
                  <View style={{ width: 2, height: 2, borderRadius: 1, backgroundColor: LGRAY }} />
                  <Text style={{ fontSize: s(11), color: GRAY }}>
                    {patient.gender.charAt(0) + patient.gender.slice(1).toLowerCase()}
                  </Text>
                </>
              )}
              {!!patient.patient_created_at && (
                <>
                  <View style={{ width: 2, height: 2, borderRadius: 1, backgroundColor: LGRAY }} />
                  <Text style={{ fontSize: s(11), color: GRAY }}>Since {formatMemberSince(patient.patient_created_at)}</Text>
                </>
              )}
            </View>
          </View>

          {/* Badges */}
          <View style={{ gap: sh(4), alignItems: 'flex-end' }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(3), backgroundColor: hc.bg, paddingHorizontal: s(7), paddingVertical: sh(3), borderRadius: 20 }}>
              <Ionicons name={hc.icon} size={s(10)} color={hc.color} />
              <Text style={{ fontSize: s(10), fontWeight: '600', color: hc.color }}>{hc.label}</Text>
            </View>
            {!!patient.blood_type && (
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(3), backgroundColor: '#EEF2FF', paddingHorizontal: s(7), paddingVertical: sh(3), borderRadius: 20 }}>
                <Ionicons name="water-outline" size={s(10)} color={BLUE} />
                <Text style={{ fontSize: s(10), fontWeight: '600', color: BLUE }}>{patient.blood_type}</Text>
              </View>
            )}
          </View>
        </View>

        {/* Contact row */}
        {(!!patient.email || !!patient.phone) && (
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: s(12), paddingHorizontal: s(20), paddingBottom: sh(10) }}>
            {!!patient.email && (
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(4) }}>
                <Ionicons name="mail-outline" size={s(12)} color={LGRAY} />
                <Text style={{ fontSize: s(11), color: GRAY }} numberOfLines={1}>{patient.email}</Text>
              </View>
            )}
            {!!patient.phone && (
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(4) }}>
                <Ionicons name="call-outline" size={s(12)} color={LGRAY} />
                <Text style={{ fontSize: s(11), color: GRAY }}>{patient.phone}</Text>
              </View>
            )}
          </View>
        )}

        {/* Divider */}
        <View style={{ height: 1, backgroundColor: BORDER, marginHorizontal: s(16) }} />

        {/* Allergies */}
        {patient.allergies.length > 0 && (
          <View style={{ paddingHorizontal: s(16), paddingTop: sh(10), paddingBottom: sh(6) }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(4), marginBottom: sh(5) }}>
              <Ionicons name="warning-outline" size={s(11)} color={RED} />
              <Text style={{ fontSize: s(10), fontWeight: '700', color: RED, textTransform: 'uppercase', letterSpacing: 0.4 }}>Allergies</Text>
            </View>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: s(5) }}>
              {patient.allergies.map(a => (
                <View key={a} style={{ backgroundColor: '#FEE2E2', borderWidth: 1, borderColor: '#FECACA', borderRadius: 20, paddingHorizontal: s(8), paddingVertical: sh(3) }}>
                  <Text style={{ fontSize: s(10), fontWeight: '600', color: RED }}>{a}</Text>
                </View>
              ))}
            </View>
          </View>
        )}

        {/* Chronic conditions */}
        {patient.chronic_conditions.length > 0 && (
          <View style={{ paddingHorizontal: s(16), paddingTop: sh(6), paddingBottom: sh(8) }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(4), marginBottom: sh(5) }}>
              <Ionicons name="heart-outline" size={s(11)} color={ORANGE} />
              <Text style={{ fontSize: s(10), fontWeight: '700', color: ORANGE, textTransform: 'uppercase', letterSpacing: 0.4 }}>Conditions</Text>
            </View>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: s(5) }}>
              {patient.chronic_conditions.map(c => (
                <View key={c} style={{ backgroundColor: '#FEF3C7', borderWidth: 1, borderColor: '#FDE68A', borderRadius: 20, paddingHorizontal: s(8), paddingVertical: sh(3) }}>
                  <Text style={{ fontSize: s(10), fontWeight: '600', color: ORANGE }}>{c}</Text>
                </View>
              ))}
            </View>
          </View>
        )}

        {/* Footer */}
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(10), paddingHorizontal: s(16), paddingVertical: sh(12), borderTopWidth: 1, borderTopColor: BORDER, backgroundColor: BG }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(4) }}>
            <Ionicons name="calendar-number-outline" size={s(13)} color={BLUE} />
            <Text style={{ fontSize: s(12), fontWeight: '700', color: TEXT }}>{patient.appointment_count}</Text>
            <Text style={{ fontSize: s(11), color: GRAY }}>visit{patient.appointment_count !== 1 ? 's' : ''}</Text>
          </View>
          <View style={{ width: 1, height: s(14), backgroundColor: BORDER }} />
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(4), flex: 1 }}>
            <Ionicons name="time-outline" size={s(13)} color={LGRAY} />
            <Text style={{ fontSize: s(11), color: GRAY }} numberOfLines={1}>
              Last: <Text style={{ fontWeight: '600', color: TEXT }}>{formatDate(patient.last_appointment_at)}</Text>
            </Text>
          </View>
          <TouchableOpacity
            style={{ flexDirection: 'row', alignItems: 'center', gap: s(2), backgroundColor: '#EEF2FF', paddingHorizontal: s(10), paddingVertical: sh(5), borderRadius: s(8) }}
            activeOpacity={0.8}
          >
            <Text style={{ fontSize: s(11), color: BLUE, fontWeight: '600' }}>Profile</Text>
            <Ionicons name="chevron-forward" size={s(12)} color={BLUE} />
          </TouchableOpacity>
        </View>
      </View>
    );
  }

  // ─── Loading / Error ────────────────────────────────────────────────────────
  if (loading) {
    return (
      <View style={{ flex: 1, backgroundColor: BG }}>
        {header}
        <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
          <ActivityIndicator size="large" color={BLUE} />
        </View>
      </View>
    );
  }

  if (error) {
    return (
      <View style={{ flex: 1, backgroundColor: BG }}>
        {header}
        <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', gap: sh(12), paddingHorizontal: s(24) }}>
          <Ionicons name="alert-circle-outline" size={s(52)} color={RED} />
          <Text style={{ fontSize: s(15), color: GRAY, textAlign: 'center' }}>{error}</Text>
          <TouchableOpacity
            style={{ backgroundColor: BLUE, borderRadius: s(8), paddingHorizontal: s(20), paddingVertical: sh(10) }}
            onPress={refetch} activeOpacity={0.8}
          >
            <Text style={{ color: WHITE, fontWeight: '600', fontSize: s(14) }}>Retry</Text>
          </TouchableOpacity>
        </View>
      </View>
    );
  }

  return (
    <View style={{ flex: 1, backgroundColor: BG }}>
      {header}

      <ScrollView
        style={{ flex: 1 }}
        showsVerticalScrollIndicator={false}
        contentContainerStyle={{ paddingBottom: sh(32) }}
      >
        {/* Stats scroll */}
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={{ paddingHorizontal: s(16), paddingTop: sh(10), paddingBottom: sh(4), gap: s(8), alignItems: 'flex-start' }}
        >
          <StatChip icon="people-outline"           iconBg="#EEF2FF" iconColor={BLUE}   label="Total"    value={stats.total}    />
          <StatChip icon="checkmark-circle-outline" iconBg="#D1FAE5" iconColor={GREEN}  label="Stable"   value={stats.stable}   />
          <StatChip icon="alert-circle-outline"     iconBg="#FEF3C7" iconColor={ORANGE} label="Monitor"  value={stats.monitor}  />
          <StatChip icon="close-circle-outline"     iconBg="#FEE2E2" iconColor={RED}    label="Critical" value={stats.critical} />
        </ScrollView>

        {/* Health filter pills */}
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={{ paddingHorizontal: s(16), paddingTop: sh(2), paddingBottom: sh(2), gap: s(5), alignItems: 'flex-start' }}
        >
          {healthFilters.map(f => {
            const active = healthFilter === f.key;
            return (
              <TouchableOpacity
                key={f.key}
                style={{
                  paddingHorizontal: s(16), paddingVertical: sh(8), borderRadius: 20,
                  borderWidth: 1,
                  borderColor: active ? f.color : BORDER,
                  backgroundColor: active ? f.color + '18' : WHITE,
                  alignSelf: 'flex-start',
                }}
                onPress={() => setHealthFilter(f.key)}
                activeOpacity={0.7}
              >
                <Text style={{ fontSize: s(14), color: active ? f.color : GRAY, fontWeight: active ? '700' : '500' }}>
                  {f.label}
                </Text>
              </TouchableOpacity>
            );
          })}
        </ScrollView>

        {/* Search bar */}
        <View style={{
          flexDirection: 'row', alignItems: 'center', gap: s(8),
          marginHorizontal: s(16), marginTop: sh(8), marginBottom: sh(6),
          paddingHorizontal: s(12), paddingVertical: sh(8),
          backgroundColor: WHITE, borderRadius: s(12),
          borderWidth: 1, borderColor: BORDER,
        }}>
          <Ionicons name="search-outline" size={s(16)} color={LGRAY} />
          <TextInput
            style={{ flex: 1, fontSize: s(13), color: TEXT }}
            placeholder="Search by name or email…"
            placeholderTextColor={LGRAY}
            value={search}
            onChangeText={setSearch}
            returnKeyType="search"
            clearButtonMode="while-editing"
          />
          {!!search && (
            <TouchableOpacity onPress={() => setSearch('')} activeOpacity={0.7}>
              <Ionicons name="close-circle" size={s(16)} color={LGRAY} />
            </TouchableOpacity>
          )}
        </View>

        {/* Result count */}
        <Text style={{ fontSize: s(12), color: GRAY, fontWeight: '500', paddingHorizontal: s(16), marginBottom: sh(2), marginTop: sh(2) }}>
          {filtered.length} patient{filtered.length !== 1 ? 's' : ''}
        </Text>

        {/* Patient cards */}
        <View style={{ paddingHorizontal: s(16) }}>
          {filtered.length === 0 ? (
            <View style={{ alignItems: 'center', gap: sh(10), paddingVertical: sh(60) }}>
              <Ionicons name="people-outline" size={s(60)} color={BORDER} />
              <Text style={{ fontSize: s(16), fontWeight: '600', color: TEXT }}>No patients found</Text>
              <Text style={{ fontSize: s(13), color: GRAY }}>Try adjusting your filter</Text>
            </View>
          ) : (
            filtered.map(p => <PatientCard key={p.patient_id} patient={p} />)
          )}
        </View>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({});
