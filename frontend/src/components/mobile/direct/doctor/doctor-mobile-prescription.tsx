import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import {
  ActivityIndicator, ScrollView, StyleSheet, Text, TextInput,
  TouchableOpacity, View, useWindowDimensions,
} from 'react-native';
import {
  type DoctorPrescription, type PrescriptionStatus,
  useDoctorPrescriptions,
} from '@/hooks/use-doctor-prescriptions';
import { tidyDoctorName } from '@/hooks/use-doctors';
import { daysUntilDateOnly, formatDateOnly } from '@/lib/ui/date-only';

const NAVY   = '#0D1B2E';
const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const LGRAY  = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const BLUE   = '#4F46E5';
const GREEN  = '#047857';
const AMBER  = '#B45309';
const RED    = '#B91C1C';
const TEAL   = '#0F766E';
const PURPLE = '#6D28D9';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type StatusFilter = 'ALL' | PrescriptionStatus;

// ─── Helpers ──────────────────────────────────────────────────────────────────

/** "ON_HOLD" -> "On hold"; used for any status or route the app has no label for. */
function readable(value: string | null | undefined, fallback = 'Unknown') {
  const t = String(value ?? '').replace(/_/g, ' ').toLowerCase().trim();
  return t ? t[0].toUpperCase() + t.slice(1) : fallback;
}

// The backend can also send CANCELLED and ON_HOLD, which PrescriptionStatus does not list.
function statusConfig(st: PrescriptionStatus | string) {
  switch (st) {
    case 'ACTIVE':    return { label: 'Active',    color: GREEN, bg: '#D1FAE5', icon: 'checkmark-circle-outline' as IoniconsName };
    case 'COMPLETED': return { label: 'Completed', color: BLUE,  bg: '#EEF2FF', icon: 'checkbox-outline'         as IoniconsName };
    case 'EXPIRED':   return { label: 'Expired',   color: RED,   bg: '#FEE2E2', icon: 'close-circle-outline'     as IoniconsName };
    case 'CANCELLED': return { label: 'Cancelled', color: RED,   bg: '#FEE2E2', icon: 'ban-outline'              as IoniconsName };
    case 'ON_HOLD':   return { label: 'On hold',   color: AMBER, bg: '#FEF3C7', icon: 'pause-circle-outline'     as IoniconsName };
    default:          return { label: readable(st), color: GRAY, bg: '#F3F4F6', icon: 'help-circle-outline'      as IoniconsName };
  }
}

function routeConfig(r: string | null | undefined) {
  switch ((r ?? '').toUpperCase()) {
    case 'ORAL':       return { label: 'Oral',       color: TEAL,   bg: '#CCFBF1', icon: 'cafe-outline'       as IoniconsName };
    case 'TOPICAL':    return { label: 'Topical',    color: PURPLE, bg: '#EDE9FE', icon: 'color-fill-outline' as IoniconsName };
    case 'IV':         return { label: 'IV',         color: RED,    bg: '#FEE2E2', icon: 'pulse-outline'      as IoniconsName };
    case 'IM':         return { label: 'IM',         color: AMBER,  bg: '#FEF3C7', icon: 'fitness-outline'    as IoniconsName };
    case 'INHALED':    return { label: 'Inhaled',    color: BLUE,   bg: '#EEF2FF', icon: 'cloudy-outline'     as IoniconsName };
    case 'SUBLINGUAL': return { label: 'Sublingual', color: AMBER,  bg: '#FEF3C7', icon: 'water-outline'      as IoniconsName };
    case 'NASAL':      return { label: 'Nasal',      color: TEAL,   bg: '#CCFBF1', icon: 'medical-outline'    as IoniconsName };
    case 'OPHTHALMIC': return { label: 'Eye drops',  color: BLUE,   bg: '#EEF2FF', icon: 'eye-outline'        as IoniconsName };
    default:           return { label: readable(r, 'Other'), color: GRAY, bg: '#F3F4F6', icon: 'medical-outline' as IoniconsName };
  }
}

/** Expires today or within the next 14 days (calendar days on this device; expiry is a date-only value). */
function isExpiringSoon(expiry: string | null): boolean {
  const days = daysUntilDateOnly(expiry);
  return days != null && days >= 0 && days <= 14;
}

/** Drops a leading "Dr." so avatar initials come from the name ("Dr. Michael Kariuki" -> "MK"). */
function withoutTitle(name: string) {
  return name.replace(/^\s*(dr(\.\s*|\s+))+/i, '').trim();
}

const AVATAR_COLORS = ['#4F46E5', '#0F766E', '#B45309', '#B91C1C', '#6D28D9', '#047857', '#0369A1'];
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

// ─── Stat Chip ─────────────────────────────────────────────────────────────
type Scale = (n: number) => number;

function StatChip({ icon, iconBg, iconColor, label, value, s, sh }: {
  icon: IoniconsName; iconBg: string; iconColor: string; label: string; value: number; s: Scale; sh: Scale;
}) {
  return (
    <View accessible accessibilityLabel={`${label}: ${value}`} style={{
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
function RxCard({ rx, expanded, onToggle, s, sh }: {
  rx: DoctorPrescription; expanded: boolean; onToggle: (id: number) => void; s: Scale; sh: Scale;
}) {
  const sc       = statusConfig(rx.status);
  const rc       = routeConfig(rx.route);
  const doctor   = tidyDoctorName(rx.doctor_name);
  const pac      = avatarColor(rx.patient_name);
  const dac      = avatarColor(doctor);
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
          <Text style={{ fontSize: s(11), color: AMBER, fontWeight: '600', flexShrink: 1 }}>Expiring soon — {formatDateOnly(rx.expiry_date)}</Text>
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
          <Text style={{ fontSize: s(10), color: LGRAY }}>{formatDateOnly(rx.prescribed_date)}</Text>
        </View>

        {/* Medication */}
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(12) }}>
          <View style={{ width: s(42), height: s(42), borderRadius: s(10), backgroundColor: '#EEF2FF', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
            <Ionicons name="medkit-outline" size={s(20)} color={BLUE} />
          </View>
          <View style={{ flex: 1, minWidth: 0 }}>
            <Text style={{ fontSize: s(14), fontWeight: '700', color: TEXT, lineHeight: s(20) }} numberOfLines={2}>
              {rx.medication_name}
            </Text>
            {!!rx.generic_name && (
              <Text style={{ fontSize: s(11), color: GRAY, fontStyle: 'italic', marginTop: sh(1) }} numberOfLines={2}>{rx.generic_name}</Text>
            )}
          </View>
        </View>

        {/* Dosage / Frequency info grid */}
        <View style={{ backgroundColor: BG, borderRadius: s(10), padding: s(10), borderWidth: 1, borderColor: BORDER, gap: sh(6) }}>
          <View style={{ flexDirection: 'row', gap: s(6) }}>
            <Ionicons name="flask-outline" size={s(13)} color={LGRAY} />
            <View style={{ flex: 1 }}>
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
              <View style={{ flex: 1 }}>
                <Text style={{ fontSize: s(9), color: LGRAY, textTransform: 'uppercase', letterSpacing: 0.4 }}>Quantity</Text>
                <Text style={{ fontSize: s(12), color: TEXT, fontWeight: '600' }}>{rx.quantity}</Text>
              </View>
            </View>
          )}
        </View>

        {/* Patient + Doctor */}
        <View style={{ flexDirection: 'row', gap: s(10), flexWrap: 'wrap' }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(6), maxWidth: '100%' }}>
            <View style={{ width: s(24), height: s(24), borderRadius: s(12), backgroundColor: pac, alignItems: 'center', justifyContent: 'center' }}>
              <Text style={{ color: WHITE, fontSize: s(9), fontWeight: '700' }}>{initials(rx.patient_name)}</Text>
            </View>
            <View style={{ flexShrink: 1 }}>
              <Text style={{ fontSize: s(9), color: LGRAY, textTransform: 'uppercase', letterSpacing: 0.3 }}>Patient</Text>
              <Text style={{ fontSize: s(11), fontWeight: '600', color: TEXT }} numberOfLines={1}>{rx.patient_name}</Text>
            </View>
          </View>
          <View style={{ width: 1, backgroundColor: BORDER }} />
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(6), maxWidth: '100%' }}>
            <View style={{ width: s(24), height: s(24), borderRadius: s(12), backgroundColor: dac, alignItems: 'center', justifyContent: 'center' }}>
              <Text style={{ color: WHITE, fontSize: s(9), fontWeight: '700' }}>{initials(withoutTitle(doctor))}</Text>
            </View>
            <View style={{ flexShrink: 1 }}>
              <Text style={{ fontSize: s(9), color: LGRAY, textTransform: 'uppercase', letterSpacing: 0.3 }}>Prescribed by</Text>
              <Text style={{ fontSize: s(11), fontWeight: '600', color: TEXT }} numberOfLines={1}>{doctor}</Text>
            </View>
          </View>
        </View>

        {/* Expiry */}
        {!!rx.expiry_date && (
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(6) }}>
            <Ionicons name="calendar-outline" size={s(13)} color={LGRAY} />
            <Text style={{ fontSize: s(11), color: GRAY }}>Expires: </Text>
            <Text style={[{ fontSize: s(11), fontWeight: '600', color: TEXT }, expiring && { color: AMBER }]}>
              {formatDateOnly(rx.expiry_date)}
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
          onPress={() => onToggle(rx.prescription_id)}
          activeOpacity={0.7}
          accessibilityRole="button"
          accessibilityLabel={`${expanded ? 'Hide' : 'Show'} special instructions for ${rx.medication_name}`}
          accessibilityState={{ expanded }}
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

// ─── Main Component ───────────────────────────────────────────────────────────

export default function DoctorMobilePrescription() {
  const { prescriptions, loading, error, refetch } = useDoctorPrescriptions();
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
    <View style={{ backgroundColor: NAVY, paddingTop: sh(12), paddingBottom: sh(16), paddingHorizontal: s(20) }}>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
        <View style={{ flex: 1, minWidth: 0 }}>
          <Text style={{ color: WHITE, fontSize: s(20), fontWeight: '700' }} accessibilityRole="header">Prescriptions</Text>
          <Text style={{ color: '#94A3B8', fontSize: s(12), marginTop: sh(2) }}>
            {loading && prescriptions.length === 0 ? 'Loading…' : `${prescriptions.length} prescription${prescriptions.length !== 1 ? 's' : ''}`}
          </Text>
        </View>
        <TouchableOpacity
          onPress={refetch}
          activeOpacity={0.7}
          style={{ width: s(38), height: s(38), borderRadius: s(19), backgroundColor: 'rgba(255,255,255,0.1)', alignItems: 'center', justifyContent: 'center' }}
          accessibilityRole="button"
          accessibilityLabel="Refresh"
        >
          <Ionicons name="refresh-outline" size={s(20)} color={WHITE} />
        </TouchableOpacity>
      </View>
    </View>
  );

  // ─── Loading / Error ────────────────────────────────────────────────────────
  // Full-screen states only before the first load; a refetch keeps the list and expanded cards.
  if (loading && prescriptions.length === 0) return (
    <View style={{ flex: 1, backgroundColor: BG }}>
      {header}
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
        <ActivityIndicator size="large" color={BLUE} />
      </View>
    </View>
  );

  if (error && prescriptions.length === 0) return (
    <View style={{ flex: 1, backgroundColor: BG }}>
      {header}
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', gap: sh(12), paddingHorizontal: s(24) }}>
        <Ionicons name="alert-circle-outline" size={s(52)} color={RED} />
        <Text style={{ fontSize: s(15), color: GRAY, textAlign: 'center' }}>{error}</Text>
        <TouchableOpacity style={{ backgroundColor: BLUE, borderRadius: s(8), paddingHorizontal: s(20), paddingVertical: sh(10) }} onPress={refetch} activeOpacity={0.8} accessibilityRole="button" accessibilityLabel="Retry loading prescriptions">
          <Text style={{ color: WHITE, fontWeight: '600', fontSize: s(14) }}>Retry</Text>
        </TouchableOpacity>
      </View>
    </View>
  );

  return (
    <View style={{ flex: 1, backgroundColor: BG }}>
      {header}

      <ScrollView style={{ flex: 1 }} showsVerticalScrollIndicator={false} contentContainerStyle={{ paddingBottom: sh(32) }} keyboardShouldPersistTaps="handled" keyboardDismissMode="on-drag">
        {/* Stats */}
        <ScrollView horizontal showsHorizontalScrollIndicator={false}
          contentContainerStyle={{ paddingHorizontal: s(16), paddingTop: sh(10), paddingBottom: sh(4), gap: s(8), alignItems: 'flex-start' }}>
          <StatChip icon="medical-outline"          iconBg="#EEF2FF" iconColor={BLUE}  label="Total"     value={stats.total}     s={s} sh={sh} />
          <StatChip icon="checkmark-circle-outline" iconBg="#D1FAE5" iconColor={GREEN} label="Active"    value={stats.active}    s={s} sh={sh} />
          <StatChip icon="checkbox-outline"         iconBg="#EEF2FF" iconColor={BLUE}  label="Completed" value={stats.completed} s={s} sh={sh} />
          <StatChip icon="close-circle-outline"     iconBg="#FEE2E2" iconColor={RED}   label="Expired"   value={stats.expired}   s={s} sh={sh} />
          <StatChip icon="refresh-outline"          iconBg="#F3F4F6" iconColor={GRAY}  label="Refillable" value={stats.refills}  s={s} sh={sh} />
          <StatChip icon="warning-outline"          iconBg="#FEF3C7" iconColor={AMBER} label="Expiring"  value={stats.expiring}  s={s} sh={sh} />
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
            accessibilityLabel="Search prescriptions by medication or patient"
          />
          {!!search && (
            <TouchableOpacity onPress={() => setSearch('')} activeOpacity={0.7} accessibilityRole="button" accessibilityLabel="Clear search">
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
                accessibilityRole="tab"
                accessibilityLabel={`Status: ${f.label}`}
                accessibilityState={{ selected: active }}
              >
                <Text style={{ fontSize: s(12), color: active ? f.color : GRAY, fontWeight: active ? '700' : '500' }}>
                  {f.label}
                </Text>
              </TouchableOpacity>
            );
          })}
        </ScrollView>

        {!!error && (
          <Text style={{ fontSize: s(12), color: RED, paddingHorizontal: s(16), marginBottom: sh(4) }} accessibilityRole="alert">{error}</Text>
        )}

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
            filtered.map(r => <RxCard key={r.prescription_id} rx={r} expanded={expandedIds.has(r.prescription_id)} onToggle={toggleExpand} s={s} sh={sh} />)
          )}
        </View>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({});
