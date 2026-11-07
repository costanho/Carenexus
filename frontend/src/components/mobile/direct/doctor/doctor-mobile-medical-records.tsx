import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import {
  ActivityIndicator, ScrollView, StyleSheet, Text, TextInput,
  TouchableOpacity, View, useWindowDimensions,
} from 'react-native';
import { type DoctorMedicalRecord, type RecordType, useDoctorMedicalRecords } from '@/hooks/use-doctor-medical-records';
import { tidyDoctorName } from '@/hooks/use-doctors';

const NAVY   = '#0D1B2E';
const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const LGRAY  = '#9CA3AF';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const BLUE   = '#4F46E5';
const GREEN  = '#10B981';
const TEAL   = '#0D9488';
const AMBER  = '#F59E0B';
const RED    = '#EF4444';
const PURPLE = '#8B5CF6';
const DARK_BLUE = '#1E3A5F';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type TypeFilter   = 'ALL' | RecordType;

// ─── Helpers ──────────────────────────────────────────────────────────────────

function typeConfig(t: RecordType): { label: string; color: string; bg: string; icon: IoniconsName } {
  switch (t) {
    case 'CONSULT':      return { label: 'Consult',      color: BLUE,      bg: '#EEF2FF', icon: 'medical-outline' };
    case 'LAB':          return { label: 'Lab',          color: TEAL,      bg: '#CCFBF1', icon: 'flask-outline'             };
    case 'PRESCRIPTION': return { label: 'Prescription', color: GREEN,     bg: '#D1FAE5', icon: 'medkit-outline'            };
    case 'REFERRAL':     return { label: 'Referral',     color: DARK_BLUE, bg: '#DBEAFE', icon: 'git-compare-outline'       };
    case 'NOTE':         return { label: 'Note',         color: AMBER,     bg: '#FEF3C7', icon: 'document-text-outline'     };
    case 'IMAGING':      return { label: 'Imaging',      color: PURPLE,    bg: '#EDE9FE', icon: 'image-outline'             };
    default:             return { label: readable(t),    color: GRAY,      bg: '#F3F4F6', icon: 'document-outline'          };
  }
}

/** "DISCHARGE_SUMMARY" -> "Discharge summary", for a type the app has no label for. */
function readable(value: string | null | undefined) {
  const t = String(value ?? '').replace(/_/g, ' ').toLowerCase().trim();
  return t ? t[0].toUpperCase() + t.slice(1) : 'Record';
}

/** Drops a leading "Dr." so avatar initials come from the name ("Dr. Michael Kariuki" -> "MK"). */
function withoutTitle(name: string) {
  return name.replace(/^\s*(dr(\.\s*|\s+))+/i, '').trim();
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
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

const TYPE_FILTERS: { key: TypeFilter; label: string; color: string }[] = [
  { key: 'ALL',          label: 'All',          color: BLUE      },
  { key: 'CONSULT',      label: 'Consult',      color: BLUE      },
  { key: 'LAB',          label: 'Lab',          color: TEAL      },
  { key: 'PRESCRIPTION', label: 'Prescription', color: GREEN     },
  { key: 'REFERRAL',     label: 'Referral',     color: DARK_BLUE },
  { key: 'NOTE',         label: 'Note',         color: AMBER     },
  { key: 'IMAGING',      label: 'Imaging',      color: PURPLE    },
];

// ─── Stat chip ─────────────────────────────────────────────────────────────
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

// ─── Record Card ───────────────────────────────────────────────────────────
function RecordCard({ record, expanded, onToggle, s, sh }: {
  record: DoctorMedicalRecord; expanded: boolean; onToggle: (id: number) => void; s: Scale; sh: Scale;
}) {
  const tc       = typeConfig(record.record_type);
  const pac      = avatarColor(record.patient_name);
  const author   = tidyDoctorName(record.doctor_name);
  const hasExtra = !!(record.treatment_plan || record.observations || record.icd_codes.length > 0);

  return (
    <View style={{
      backgroundColor: WHITE, borderRadius: s(16), borderWidth: 1,
      borderColor: BORDER, overflow: 'hidden', marginBottom: sh(12),
      borderLeftWidth: s(4), borderLeftColor: tc.color,
    }}>
      {/* Top */}
      <View style={{ padding: s(14), gap: sh(8) }}>
        {/* Type badge + date */}
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: s(6) }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(5), backgroundColor: tc.bg, paddingHorizontal: s(8), paddingVertical: sh(3), borderRadius: 20 }}>
            <Ionicons name={tc.icon} size={s(11)} color={tc.color} />
            <Text style={{ fontSize: s(10), fontWeight: '700', color: tc.color }}>{tc.label}</Text>
          </View>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(4) }}>
            <Ionicons name="calendar-outline" size={s(11)} color={LGRAY} />
            <Text style={{ fontSize: s(10), color: LGRAY }}>{formatDate(record.created_at)}</Text>
          </View>
        </View>

        {/* Title */}
        <Text style={{ fontSize: s(14), fontWeight: '700', color: TEXT, lineHeight: s(20) }}>
          {record.title}
        </Text>

        {/* Patient + author row */}
        <View style={{ flexDirection: 'row', gap: s(10), flexWrap: 'wrap' }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(6), maxWidth: '100%' }}>
            <View style={{ width: s(24), height: s(24), borderRadius: s(12), backgroundColor: pac, alignItems: 'center', justifyContent: 'center' }}>
              <Text style={{ color: WHITE, fontSize: s(9), fontWeight: '700' }}>{initials(record.patient_name)}</Text>
            </View>
            <View style={{ flexShrink: 1 }}>
              <Text style={{ fontSize: s(9), color: LGRAY, textTransform: 'uppercase', letterSpacing: 0.3 }}>Patient</Text>
              <Text style={{ fontSize: s(11), fontWeight: '600', color: TEXT }} numberOfLines={1}>{record.patient_name}</Text>
            </View>
          </View>
          <View style={{ width: 1, backgroundColor: BORDER }} />
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(6), maxWidth: '100%' }}>
            <View style={{ width: s(24), height: s(24), borderRadius: s(12), backgroundColor: avatarColor(author), alignItems: 'center', justifyContent: 'center' }}>
              <Text style={{ color: WHITE, fontSize: s(9), fontWeight: '700' }}>{initials(withoutTitle(author))}</Text>
            </View>
            <View style={{ flexShrink: 1 }}>
              <Text style={{ fontSize: s(9), color: LGRAY, textTransform: 'uppercase', letterSpacing: 0.3 }}>Author</Text>
              <Text style={{ fontSize: s(11), fontWeight: '600', color: TEXT }} numberOfLines={1}>{author}</Text>
            </View>
          </View>
        </View>

        {/* Description */}
        {!!record.description && (
          <View style={{ backgroundColor: BG, borderRadius: s(8), padding: s(10), borderWidth: 1, borderColor: BORDER }}>
            <Text style={{ fontSize: s(12), color: GRAY, lineHeight: s(18) }} numberOfLines={expanded ? undefined : 3}>
              {record.description}
            </Text>
          </View>
        )}

        {/* Expanded sections */}
        {expanded && (
          <View style={{ gap: sh(10) }}>
            {!!record.treatment_plan && (
              <View style={{ borderLeftWidth: 3, borderLeftColor: GREEN, paddingLeft: s(10), gap: sh(4) }}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(4) }}>
                  <Ionicons name="clipboard-outline" size={s(12)} color={GREEN} />
                  <Text style={{ fontSize: s(10), fontWeight: '700', color: GREEN, textTransform: 'uppercase', letterSpacing: 0.4 }}>Treatment Plan</Text>
                </View>
                <Text style={{ fontSize: s(12), color: GRAY, lineHeight: s(18) }}>{record.treatment_plan}</Text>
              </View>
            )}

            {!!record.observations && (
              <View style={{ borderLeftWidth: 3, borderLeftColor: BLUE, paddingLeft: s(10), gap: sh(4) }}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(4) }}>
                  <Ionicons name="eye-outline" size={s(12)} color={BLUE} />
                  <Text style={{ fontSize: s(10), fontWeight: '700', color: BLUE, textTransform: 'uppercase', letterSpacing: 0.4 }}>Observations</Text>
                </View>
                <Text style={{ fontSize: s(12), color: GRAY, lineHeight: s(18) }}>{record.observations}</Text>
              </View>
            )}

            {record.icd_codes.length > 0 && (
              <View style={{ gap: sh(6) }}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(4) }}>
                  <Ionicons name="code-slash-outline" size={s(12)} color={PURPLE} />
                  <Text style={{ fontSize: s(10), fontWeight: '700', color: PURPLE, textTransform: 'uppercase', letterSpacing: 0.4 }}>ICD Codes</Text>
                </View>
                <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: s(5) }}>
                  {record.icd_codes.map((c, i) => (
                    <View key={i} style={{ backgroundColor: '#EDE9FE', borderWidth: 1, borderColor: '#C4B5FD', borderRadius: s(6), paddingHorizontal: s(8), paddingVertical: sh(3) }}>
                      <Text style={{ fontSize: s(10), fontWeight: '700', color: PURPLE }}>{c.code}</Text>
                      <Text style={{ fontSize: s(9), color: GRAY }}>{c.description}</Text>
                    </View>
                  ))}
                </View>
              </View>
            )}
          </View>
        )}
      </View>

      {/* Expand footer */}
      {hasExtra && (
        <TouchableOpacity
          style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: s(4), paddingVertical: sh(10), borderTopWidth: 1, borderTopColor: BORDER, backgroundColor: BG }}
          onPress={() => onToggle(record.record_id)}
          activeOpacity={0.7}
          accessibilityRole="button"
          accessibilityLabel={`${expanded ? 'Show less of' : 'View full'} record: ${record.title}`}
          accessibilityState={{ expanded }}
        >
          <Text style={{ fontSize: s(12), color: BLUE, fontWeight: '600' }}>
            {expanded ? 'Show less' : 'View full record'}
          </Text>
          <Ionicons name={expanded ? 'chevron-up' : 'chevron-down'} size={s(13)} color={BLUE} />
        </TouchableOpacity>
      )}
    </View>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────

export default function DoctorMobileMedicalRecords() {
  const { records, loading, error, refetch } = useDoctorMedicalRecords();
  const { width, height } = useWindowDimensions();

  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));

  const [typeFilter,  setTypeFilter]  = useState<TypeFilter>('ALL');
  const [search,      setSearch]      = useState('');
  const [expandedIds, setExpandedIds] = useState<Set<number>>(new Set());

  function toggleExpand(id: number) {
    setExpandedIds(prev => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  }

  const stats = useMemo(() => ({
    total:        records.length,
    consults:     records.filter(r => r.record_type === 'CONSULT').length,
    labs:         records.filter(r => r.record_type === 'LAB').length,
    prescriptions:records.filter(r => r.record_type === 'PRESCRIPTION').length,
    referrals:    records.filter(r => r.record_type === 'REFERRAL').length,
    notes:        records.filter(r => r.record_type === 'NOTE').length,
  }), [records]);

  const filtered = useMemo(() => (
    records
      .filter(r => typeFilter === 'ALL' || r.record_type === typeFilter)
      .filter(r => !search
        || r.title.toLowerCase().includes(search.toLowerCase())
        || r.patient_name.toLowerCase().includes(search.toLowerCase())
        || r.icd_codes.some(c => c.code.toLowerCase().includes(search.toLowerCase()))
      )
  ), [records, typeFilter, search]);

  // ─── Header ────────────────────────────────────────────────────────────────
  const header = (
    <View style={{ backgroundColor: NAVY, paddingTop: sh(12), paddingBottom: sh(16), paddingHorizontal: s(20) }}>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
        <View style={{ flex: 1, minWidth: 0 }}>
          <Text style={{ color: WHITE, fontSize: s(20), fontWeight: '700' }} accessibilityRole="header">Medical Records</Text>
          <Text style={{ color: '#94A3B8', fontSize: s(12), marginTop: sh(2) }}>
            {loading && records.length === 0 ? 'Loading…' : `${records.length} record${records.length !== 1 ? 's' : ''}`}
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
  if (loading && records.length === 0) return (
    <View style={{ flex: 1, backgroundColor: BG }}>
      {header}
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
        <ActivityIndicator size="large" color={BLUE} />
      </View>
    </View>
  );

  if (error && records.length === 0) return (
    <View style={{ flex: 1, backgroundColor: BG }}>
      {header}
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', gap: sh(12), paddingHorizontal: s(24) }}>
        <Ionicons name="alert-circle-outline" size={s(52)} color={RED} />
        <Text style={{ fontSize: s(15), color: GRAY, textAlign: 'center' }}>{error}</Text>
        <TouchableOpacity style={{ backgroundColor: BLUE, borderRadius: s(8), paddingHorizontal: s(20), paddingVertical: sh(10) }} onPress={refetch} activeOpacity={0.8} accessibilityRole="button" accessibilityLabel="Retry loading medical records">
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
          <StatChip icon="folder-open-outline"   iconBg="#EEF2FF" iconColor={BLUE}      label="Total"   value={stats.total} s={s} sh={sh} />
          <StatChip icon="pulse-outline"            iconBg="#EEF2FF" iconColor={BLUE}      label="Consults" value={stats.consults} s={s} sh={sh} />
          <StatChip icon="flask-outline"          iconBg="#CCFBF1" iconColor={TEAL}      label="Labs"    value={stats.labs} s={s} sh={sh} />
          <StatChip icon="medkit-outline"         iconBg="#D1FAE5" iconColor={GREEN}     label="Rx"      value={stats.prescriptions} s={s} sh={sh} />
          <StatChip icon="git-compare-outline"    iconBg="#DBEAFE" iconColor={DARK_BLUE} label="Refs"    value={stats.referrals} s={s} sh={sh} />
          <StatChip icon="document-text-outline"  iconBg="#FEF3C7" iconColor={AMBER}     label="Notes"   value={stats.notes} s={s} sh={sh} />
        </ScrollView>

        {/* Search bar */}
        <View style={{
          flexDirection: 'row', alignItems: 'center', gap: s(8),
          marginHorizontal: s(16), marginTop: sh(4), marginBottom: sh(6),
          paddingHorizontal: s(12), paddingVertical: sh(8),
          backgroundColor: WHITE, borderRadius: s(12), borderWidth: 1, borderColor: BORDER,
        }}>
          <Ionicons name="search-outline" size={s(16)} color={LGRAY} />
          <TextInput
            style={{ flex: 1, fontSize: s(13), color: TEXT }}
            placeholder="Search by title, patient, ICD…"
            placeholderTextColor={LGRAY}
            value={search}
            onChangeText={setSearch}
            returnKeyType="search"
            accessibilityLabel="Search records by title, patient or ICD code"
          />
          {!!search && (
            <TouchableOpacity onPress={() => setSearch('')} activeOpacity={0.7} accessibilityRole="button" accessibilityLabel="Clear search">
              <Ionicons name="close-circle" size={s(16)} color={LGRAY} />
            </TouchableOpacity>
          )}
        </View>

        {/* Type filter pills */}
        <ScrollView horizontal showsHorizontalScrollIndicator={false}
          contentContainerStyle={{ paddingHorizontal: s(16), paddingTop: sh(2), paddingBottom: sh(6), gap: s(5), alignItems: 'flex-start' }}>
          {TYPE_FILTERS.map(f => {
            const active = typeFilter === f.key;
            return (
              <TouchableOpacity
                key={f.key}
                style={{
                  paddingHorizontal: s(12), paddingVertical: sh(5), borderRadius: 20, borderWidth: 1,
                  borderColor: active ? f.color : BORDER,
                  backgroundColor: active ? f.color + '18' : WHITE,
                  alignSelf: 'flex-start',
                }}
                onPress={() => setTypeFilter(f.key)}
                activeOpacity={0.7}
                accessibilityRole="tab"
                accessibilityLabel={`Record type: ${f.label}`}
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
          {filtered.length} record{filtered.length !== 1 ? 's' : ''}
        </Text>

        {/* Record list */}
        <View style={{ paddingHorizontal: s(16) }}>
          {filtered.length === 0 ? (
            <View style={{ alignItems: 'center', gap: sh(10), paddingVertical: sh(60) }}>
              <Ionicons name="folder-open-outline" size={s(60)} color={BORDER} />
              <Text style={{ fontSize: s(16), fontWeight: '600', color: TEXT }}>No records found</Text>
              <Text style={{ fontSize: s(13), color: GRAY }}>Try adjusting your filter or search</Text>
            </View>
          ) : (
            filtered.map(r => <RecordCard key={r.record_id} record={r} expanded={expandedIds.has(r.record_id)} onToggle={toggleExpand} s={s} sh={sh} />)
          )}
        </View>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({});
