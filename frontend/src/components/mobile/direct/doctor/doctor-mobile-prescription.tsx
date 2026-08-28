import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import {
  ActivityIndicator, ScrollView, StyleSheet, Text, TextInput,
  TouchableOpacity, View, useWindowDimensions,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  type DoctorPrescription, type PrescriptionStatus,
  useDoctorPrescriptions,
} from '@/hooks/use-doctor-prescriptions';

const NAVY   = '#0D1B2E';
const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const LGRAY  = '#9CA3AF';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const BLUE   = '#4F46E5';
const GREEN  = '#10B981';
const AMBER  = '#F59E0B';
const RED    = '#EF4444';
const TEAL   = '#0D9488';
const PURPLE = '#8B5CF6';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type StatusFilter = 'ALL' | PrescriptionStatus;

// ─── Helpers ──────────────────────────────────────────────────────────────────

function statusConfig(s: PrescriptionStatus) {
  switch (s) {
    case 'ACTIVE':    return { label: 'Active',    color: GREEN, bg: '#D1FAE5', icon: 'checkmark-circle-outline' as IoniconsName };
    case 'COMPLETED': return { label: 'Completed', color: BLUE,  bg: '#EEF2FF', icon: 'checkbox-outline'         as IoniconsName };
    case 'EXPIRED':   return { label: 'Expired',   color: RED,   bg: '#FEE2E2', icon: 'close-circle-outline'     as IoniconsName };
    default:          return { label: s,           color: GRAY,  bg: '#F3F4F6', icon: 'help-circle-outline'      as IoniconsName };
  }
}

function routeConfig(r: string) {
  switch (r.toUpperCase()) {
    case 'ORAL':       return { label: 'Oral',    color: TEAL,   bg: '#CCFBF1', icon: 'cafe-outline'       as IoniconsName };
    case 'TOPICAL':    return { label: 'Topical', color: PURPLE, bg: '#EDE9FE', icon: 'color-fill-outline' as IoniconsName };
    case 'IV':         return { label: 'IV',      color: RED,    bg: '#FEE2E2', icon: 'pulse-outline'      as IoniconsName };
    case 'IM':         return { label: 'IM',      color: AMBER,  bg: '#FEF3C7', icon: 'fitness-outline'    as IoniconsName };
    case 'INHALED':    return { label: 'Inhaled', color: BLUE,   bg: '#EEF2FF', icon: 'cloudy-outline'     as IoniconsName };
    default:           return { label: r,         color: GRAY,   bg: '#F3F4F6', icon: 'medical-outline'    as IoniconsName };
  }
}

function formatDate(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

function isExpiringSoon(expiry: string | null): boolean {
  if (!expiry) return false;
  const days = (new Date(expiry).getTime() - Date.now()) / (1000 * 60 * 60 * 24);
  return days >= 0 && days <= 14;
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

const STATUS_FILTERS: { key: StatusFilter; label: string; color: string }[] = [
  { key: 'ALL',       label: 'All',       color: BLUE  },
  { key: 'ACTIVE',    label: 'Active',    color: GREEN },
  { key: 'COMPLETED', label: 'Completed', color: BLUE  },
  { key: 'EXPIRED',   label: 'Expired',   color: RED   },
];

// ─── Main Component ───────────────────────────────────────────────────────────

export default function DoctorMobilePrescription() {
  const { prescriptions, loading, error, refetch } = useDoctorPrescriptions();
  const insets = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();

  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));

  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');
  const [search,       setSearch]       = useState('');
  const [expandedIds,  setExpandedIds]  = useState<Set<number>>(new Set());

  function toggleExpand(id: number) {
    setExpandedIds(prev => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  }

  const stats = useMemo(() => ({
    total:     prescriptions.length,
    active:    prescriptions.filter(r => r.status === 'ACTIVE').length,
    completed: prescriptions.filter(r => r.status === 'COMPLETED').length,
    expired:   prescriptions.filter(r => r.status === 'EXPIRED').length,
    refills:   prescriptions.filter(r => r.refills_allowed > 0 && r.status === 'ACTIVE').length,
    expiring:  prescriptions.filter(r => isExpiringSoon(r.expiry_date) && r.status === 'ACTIVE').length,
  }), [prescriptions]);

  const filtered = useMemo(() => (
    prescriptions
      .filter(r => statusFilter === 'ALL' || r.status === statusFilter)
      .filter(r => !search
        || r.medication_name.toLowerCase().includes(search.toLowerCase())
        || r.generic_name?.toLowerCase().includes(search.toLowerCase())
        || r.patient_name.toLowerCase().includes(search.toLowerCase())
      )
  ), [prescriptions, statusFilter, search]);

  // ─── Header ────────────────────────────────────────────────────────────────
  const header = (
    <View style={{ backgroundColor: NAVY, paddingTop: insets.top + sh(12), paddingBottom: sh(16), paddingHorizontal: s(20) }}>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
        <View>
          <Text style={{ color: WHITE, fontSize: s(20), fontWeight: '700' }}>Prescriptions</Text>
          <Text style={{ color: '#94A3B8', fontSize: s(12), marginTop: sh(2) }}>
            {loading ? 'Loading…' : `${prescriptions.length} prescription${prescriptions.length !== 1 ? 's' : ''}`}
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

  // ─── Stat Chip ─────────────────────────────────────────────────────────────
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

  // ─── Prescription Card ─────────────────────────────────────────────────────
  function RxCard({ rx }: { rx: DoctorPrescription }) {
    const sc       = statusConfig(rx.status);
    const rc       = routeConfig(rx.route);
    const pac      = avatarColor(rx.patient_name);
    const dac      = avatarColor(rx.doctor_name);
    const expanded = expandedIds.has(rx.prescription_id);
    const expiring = isExpiringSoon(rx.expiry_date) && rx.status === 'ACTIVE';

    return (
      <View style={{
        backgroundColor: WHITE, borderRadius: s(16), borderWidth: 1,
        borderColor: BORDER, overflow: 'hidden', marginBottom: sh(12),
        borderLeftWidth: s(4), borderLeftColor: sc.color,
      }}>
        {/* Expiring banner */}
        {expiring && (
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(6), backgroundColor: '#FEF3C7', paddingHorizontal: s(14), paddingVertical: sh(6), borderBottomWidth: 1, borderBottomColor: '#FDE68A' }}>
            <Ionicons name="warning-outline" size={s(12)} color={AMBER} />
            <Text style={{ fontSize: s(11), color: AMBER, fontWeight: '600' }}>Expiring soon — {formatDate(rx.expiry_date)}</Text>
          </View>
        )}

        <View style={{ padding: s(14), gap: sh(10) }}>
          {/* Badges + date */}
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: s(6) }}>
            <View style={{ flexDirection: 'row', gap: s(6), flexWrap: 'wrap' }}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(4), backgroundColor: sc.bg, paddingHorizontal: s(8), paddingVertical: sh(3), borderRadius: 20 }}>
                <Ionicons name={sc.icon} size={s(10)} color={sc.color} />
                <Text style={{ fontSize: s(10), fontWeight: '700', color: sc.color }}>{sc.label}</Text>
              </View>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(4), backgroundColor: rc.bg, paddingHorizontal: s(8), paddingVertical: sh(3), borderRadius: 20 }}>
                <Ionicons name={rc.icon} size={s(10)} color={rc.color} />
                <Text style={{ fontSize: s(10), fontWeight: '600', color: rc.color }}>{rc.label}</Text>
              </View>
              {rx.refills_allowed > 0 && (
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(4), backgroundColor: '#F3F4F6', paddingHorizontal: s(8), paddingVertical: sh(3), borderRadius: 20 }}>
                  <Ionicons name="refresh-outline" size={s(10)} color={GRAY} />
                  <Text style={{ fontSize: s(10), fontWeight: '600', color: GRAY }}>{rx.refills_allowed}×</Text>
                </View>
              )}
            </View>
            <Text style={{ fontSize: s(10), color: LGRAY }}>{formatDate(rx.prescribed_date)}</Text>
          </View>

          {/* Medication */}
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(12) }}>
            <View style={{ width: s(42), height: s(42), borderRadius: s(10), backgroundColor: '#EEF2FF', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
              <Ionicons name="medkit-outline" size={s(20)} color={BLUE} />
            </View>
            <View style={{ flex: 1 }}>
              <Text style={{ fontSize: s(14), fontWeight: '700', color: TEXT, lineHeight: s(20) }} numberOfLines={2}>
                {rx.medication_name}
              </Text>
              {!!rx.generic_name && (
                <Text style={{ fontSize: s(11), color: GRAY, fontStyle: 'italic', marginTop: sh(1) }}>{rx.generic_name}</Text>
              )}
            </View>
          </View>

          {/* Dosage / Frequency info grid */}
          <View style={{ backgroundColor: BG, borderRadius: s(10), padding: s(10), borderWidth: 1, borderColor: BORDER, gap: sh(6) }}>
            <View style={{ flexDirection: 'row', gap: s(6) }}>
              <Ionicons name="flask-outline" size={s(13)} color={LGRAY} />
              <View>
                <Text style={{ fontSize: s(9), color: LGRAY, textTransform: 'uppercase', letterSpacing: 0.4 }}>Dosage</Text>
                <Text style={{ fontSize: s(12), color: TEXT, fontWeight: '600' }}>{rx.dosage}</Text>
              </View>
            </View>
            <View style={{ flexDirection: 'row', gap: s(6) }}>
              <Ionicons name="time-outline" size={s(13)} color={LGRAY} />
              <View style={{ flex: 1 }}>
                <Text style={{ fontSize: s(9), color: LGRAY, textTransform: 'uppercase', letterSpacing: 0.4 }}>Frequency</Text>
                <Text style={{ fontSize: s(12), color: TEXT, fontWeight: '600' }}>{rx.frequency}</Text>
              </View>
            </View>
            {!!rx.quantity && (
              <View style={{ flexDirection: 'row', gap: s(6) }}>
                <Ionicons name="layers-outline" size={s(13)} color={LGRAY} />
                <View>
                  <Text style={{ fontSize: s(9), color: LGRAY, textTransform: 'uppercase', letterSpacing: 0.4 }}>Quantity</Text>
                  <Text style={{ fontSize: s(12), color: TEXT, fontWeight: '600' }}>{rx.quantity}</Text>
                </View>
              </View>
            )}
          </View>

          {/* Patient + Doctor */}
          <View style={{ flexDirection: 'row', gap: s(10), flexWrap: 'wrap' }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(6) }}>
              <View style={{ width: s(24), height: s(24), borderRadius: s(12), backgroundColor: pac, alignItems: 'center', justifyContent: 'center' }}>
                <Text style={{ color: WHITE, fontSize: s(9), fontWeight: '700' }}>{initials(rx.patient_name)}</Text>
              </View>
              <View>
                <Text style={{ fontSize: s(9), color: LGRAY, textTransform: 'uppercase', letterSpacing: 0.3 }}>Patient</Text>
                <Text style={{ fontSize: s(11), fontWeight: '600', color: TEXT }}>{rx.patient_name}</Text>
              </View>
            </View>
            <View style={{ width: 1, backgroundColor: BORDER }} />
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(6) }}>
              <View style={{ width: s(24), height: s(24), borderRadius: s(12), backgroundColor: dac, alignItems: 'center', justifyContent: 'center' }}>
                <Text style={{ color: WHITE, fontSize: s(9), fontWeight: '700' }}>{initials(rx.doctor_name)}</Text>
              </View>
              <View>
                <Text style={{ fontSize: s(9), color: LGRAY, textTransform: 'uppercase', letterSpacing: 0.3 }}>Prescribed by</Text>
                <Text style={{ fontSize: s(11), fontWeight: '600', color: TEXT }}>{rx.doctor_name}</Text>
              </View>
            </View>
          </View>

          {/* Expiry */}
          {!!rx.expiry_date && (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(6) }}>
              <Ionicons name="calendar-outline" size={s(13)} color={LGRAY} />
              <Text style={{ fontSize: s(11), color: GRAY }}>Expires: </Text>
              <Text style={[{ fontSize: s(11), fontWeight: '600', color: TEXT }, expiring && { color: AMBER }]}>
                {formatDate(rx.expiry_date)}
              </Text>
            </View>
          )}

          {/* Expanded instructions */}
          {expanded && !!rx.special_instructions && (
            <View style={{ backgroundColor: '#EFF6FF', borderRadius: s(10), padding: s(12), borderWidth: 1, borderColor: '#BFDBFE', gap: sh(6) }}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(5) }}>
                <Ionicons name="information-circle-outline" size={s(14)} color={BLUE} />
                <Text style={{ fontSize: s(11), fontWeight: '700', color: BLUE }}>Special Instructions</Text>
              </View>
              <Text style={{ fontSize: s(12), color: GRAY, lineHeight: s(18) }}>{rx.special_instructions}</Text>
            </View>
          )}
        </View>

        {/* Expand footer */}
        {!!rx.special_instructions && (
          <TouchableOpacity
            style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: s(4), paddingVertical: sh(10), borderTopWidth: 1, borderTopColor: BORDER, backgroundColor: BG }}
            onPress={() => toggleExpand(rx.prescription_id)}
            activeOpacity={0.7}
          >
            <Text style={{ fontSize: s(12), color: BLUE, fontWeight: '600' }}>
              {expanded ? 'Hide instructions' : 'View instructions'}
            </Text>
            <Ionicons name={expanded ? 'chevron-up' : 'chevron-down'} size={s(13)} color={BLUE} />
          </TouchableOpacity>
        )}
      </View>
    );
  }

  // ─── Loading / Error ────────────────────────────────────────────────────────
  if (loading) return (
    <View style={{ flex: 1, backgroundColor: BG }}>
      {header}
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
        <ActivityIndicator size="large" color={BLUE} />
      </View>
    </View>
  );

  if (error) return (
    <View style={{ flex: 1, backgroundColor: BG }}>
      {header}
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', gap: sh(12), paddingHorizontal: s(24) }}>
        <Ionicons name="alert-circle-outline" size={s(52)} color={RED} />
        <Text style={{ fontSize: s(15), color: GRAY, textAlign: 'center' }}>{error}</Text>
        <TouchableOpacity style={{ backgroundColor: BLUE, borderRadius: s(8), paddingHorizontal: s(20), paddingVertical: sh(10) }} onPress={refetch} activeOpacity={0.8}>
          <Text style={{ color: WHITE, fontWeight: '600', fontSize: s(14) }}>Retry</Text>
        </TouchableOpacity>
      </View>
    </View>
  );

  return (
    <View style={{ flex: 1, backgroundColor: BG }}>
      {header}

      <ScrollView style={{ flex: 1 }} showsVerticalScrollIndicator={false} contentContainerStyle={{ paddingBottom: sh(32) }}>
        {/* Stats */}
        <ScrollView horizontal showsHorizontalScrollIndicator={false}
          contentContainerStyle={{ paddingHorizontal: s(16), paddingTop: sh(10), paddingBottom: sh(4), gap: s(8), alignItems: 'flex-start' }}>
          <StatChip icon="medical-outline"          iconBg="#EEF2FF" iconColor={BLUE}  label="Total"     value={stats.total}     />
          <StatChip icon="checkmark-circle-outline" iconBg="#D1FAE5" iconColor={GREEN} label="Active"    value={stats.active}    />
          <StatChip icon="checkbox-outline"         iconBg="#EEF2FF" iconColor={BLUE}  label="Completed" value={stats.completed} />
          <StatChip icon="close-circle-outline"     iconBg="#FEE2E2" iconColor={RED}   label="Expired"   value={stats.expired}   />
          <StatChip icon="refresh-outline"          iconBg="#F3F4F6" iconColor={GRAY}  label="Refillable" value={stats.refills}  />
          <StatChip icon="warning-outline"          iconBg="#FEF3C7" iconColor={AMBER} label="Expiring"  value={stats.expiring}  />
        </ScrollView>

        {/* Search */}
        <View style={{
          flexDirection: 'row', alignItems: 'center', gap: s(8),
          marginHorizontal: s(16), marginTop: sh(4), marginBottom: sh(6),
          paddingHorizontal: s(12), paddingVertical: sh(8),
          backgroundColor: WHITE, borderRadius: s(12), borderWidth: 1, borderColor: BORDER,
        }}>
          <Ionicons name="search-outline" size={s(16)} color={LGRAY} />
          <TextInput
            style={{ flex: 1, fontSize: s(13), color: TEXT }}
            placeholder="Search medication or patient…"
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

        {/* Status filter pills */}
        <ScrollView horizontal showsHorizontalScrollIndicator={false}
          contentContainerStyle={{ paddingHorizontal: s(16), paddingBottom: sh(6), gap: s(5), alignItems: 'flex-start' }}>
          {STATUS_FILTERS.map(f => {
            const active = statusFilter === f.key;
            return (
              <TouchableOpacity
                key={f.key}
                style={{
                  paddingHorizontal: s(12), paddingVertical: sh(5), borderRadius: 20, borderWidth: 1,
                  borderColor: active ? f.color : BORDER,
                  backgroundColor: active ? f.color + '18' : WHITE,
                  alignSelf: 'flex-start',
                }}
                onPress={() => setStatusFilter(f.key)}
                activeOpacity={0.7}
              >
                <Text style={{ fontSize: s(12), color: active ? f.color : GRAY, fontWeight: active ? '700' : '500' }}>
                  {f.label}
                </Text>
              </TouchableOpacity>
            );
          })}
        </ScrollView>

        {/* Result count */}
        <Text style={{ fontSize: s(12), color: GRAY, fontWeight: '500', paddingHorizontal: s(16), marginBottom: sh(4) }}>
          {filtered.length} prescription{filtered.length !== 1 ? 's' : ''}
        </Text>

        {/* List */}
        <View style={{ paddingHorizontal: s(16) }}>
          {filtered.length === 0 ? (
            <View style={{ alignItems: 'center', gap: sh(10), paddingVertical: sh(60) }}>
              <Ionicons name="medical-outline" size={s(60)} color={BORDER} />
              <Text style={{ fontSize: s(16), fontWeight: '600', color: TEXT }}>No prescriptions found</Text>
              <Text style={{ fontSize: s(13), color: GRAY }}>Try adjusting your filter or search</Text>
            </View>
          ) : (
            filtered.map(r => <RxCard key={r.prescription_id} rx={r} />)
          )}
        </View>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({});
