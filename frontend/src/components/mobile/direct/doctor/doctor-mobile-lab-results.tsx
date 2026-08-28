import { Ionicons } from '@expo/vector-icons';
import { useMemo, useState } from 'react';
import {
  ActivityIndicator, ScrollView, StyleSheet, Text, TextInput,
  TouchableOpacity, View, useWindowDimensions,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  type DoctorLabResult, type LabFlag, type LabStatus, type LabTestType,
  useDoctorLabResults,
} from '@/hooks/use-doctor-lab-results';

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
type TypeFilter   = 'ALL' | LabTestType;

// ─── Helpers ──────────────────────────────────────────────────────────────────

function testTypeConfig(t: LabTestType) {
  switch (t.toUpperCase()) {
    case 'BLOOD':   return { label: 'Blood',   color: RED,    bg: '#FEE2E2', icon: 'water-outline'     as IoniconsName };
    case 'SWAB':    return { label: 'Swab',    color: TEAL,   bg: '#CCFBF1', icon: 'medical-outline'   as IoniconsName };
    case 'URINE':   return { label: 'Urine',   color: AMBER,  bg: '#FEF3C7', icon: 'flask-outline'     as IoniconsName };
    case 'CULTURE': return { label: 'Culture', color: PURPLE, bg: '#EDE9FE', icon: 'bug-outline'       as IoniconsName };
    default:        return { label: t,         color: BLUE,   bg: '#EEF2FF', icon: 'analytics-outline' as IoniconsName };
  }
}

function statusConfig(s: LabStatus) {
  switch (s) {
    case 'COMPLETED':       return { label: 'Completed',  color: GREEN, bg: '#D1FAE5', icon: 'checkmark-circle-outline' as IoniconsName };
    case 'REQUIRES_REVIEW': return { label: 'Needs Review', color: AMBER, bg: '#FEF3C7', icon: 'eye-outline'            as IoniconsName };
    default:                return { label: s,            color: GRAY,  bg: '#F3F4F6', icon: 'help-circle-outline'     as IoniconsName };
  }
}

function flagColor(f: LabFlag) {
  switch (f) {
    case 'LOW':  return { text: AMBER, bg: '#FEF3C7', icon: 'arrow-down-outline' as IoniconsName };
    case 'HIGH': return { text: RED,   bg: '#FEE2E2', icon: 'arrow-up-outline'   as IoniconsName };
    default:     return { text: GREEN, bg: '#D1FAE5', icon: 'checkmark-outline'  as IoniconsName };
  }
}

function formatDate(iso: string): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

function formatDateTime(iso: string): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('en-GB', {
    day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
  });
}

function labelify(key: string) {
  return key.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
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

const TYPE_FILTERS: { key: TypeFilter; label: string }[] = [
  { key: 'ALL',     label: 'All'     },
  { key: 'BLOOD',   label: 'Blood'   },
  { key: 'URINE',   label: 'Urine'   },
  { key: 'SWAB',    label: 'Swab'    },
  { key: 'CULTURE', label: 'Culture' },
];

// ─── Main Component ───────────────────────────────────────────────────────────

export default function DoctorMobileLabResults() {
  const { results, loading, error, refetch } = useDoctorLabResults();
  const insets = useSafeAreaInsets();
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
    total:     results.length,
    completed: results.filter(r => r.status === 'COMPLETED').length,
    review:    results.filter(r => r.status === 'REQUIRES_REVIEW').length,
    critical:  results.filter(r => r.is_critical).length,
    blood:     results.filter(r => r.test_type === 'BLOOD').length,
    abnormal:  results.filter(r => {
      if (!r.result_data) return false;
      return Object.values(r.result_data).some(s => s.flag !== 'NORMAL');
    }).length,
  }), [results]);

  const filtered = useMemo(() => (
    results
      .filter(r => typeFilter === 'ALL' || r.test_type === typeFilter)
      .filter(r => {
        if (!search) return true;
        const q = search.toLowerCase();
        return (
          r.test_name.toLowerCase().includes(q)   ||
          r.patient_name.toLowerCase().includes(q) ||
          r.doctor_name.toLowerCase().includes(q)
        );
      })
  ), [results, typeFilter, search]);

  if (loading) {
    return (
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
        <ActivityIndicator size="large" color={BLUE} />
      </View>
    );
  }

  if (error) {
    return (
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', gap: s(12), padding: s(24) }}>
        <Ionicons name="alert-circle-outline" size={s(48)} color={RED} />
        <Text style={{ fontSize: s(14), color: GRAY, textAlign: 'center' }}>{error}</Text>
        <TouchableOpacity
          style={{ backgroundColor: BLUE, borderRadius: s(8), paddingHorizontal: s(20), paddingVertical: sh(10) }}
          onPress={refetch}
          activeOpacity={0.8}
        >
          <Text style={{ color: WHITE, fontWeight: '600', fontSize: s(14) }}>Retry</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <View style={{ flex: 1, backgroundColor: BG }}>

      {/* Navy Header */}
      <View style={{
        backgroundColor: NAVY,
        paddingTop: insets.top + sh(10),
        paddingBottom: sh(16),
        paddingHorizontal: s(16),
        gap: sh(4),
      }}>
        <Text style={{ fontSize: s(20), fontWeight: '700', color: WHITE }}>Labs & Results</Text>
        <Text style={{ fontSize: s(12), color: '#8BA3BE' }}>
          {results.length} test{results.length !== 1 ? 's' : ''} for your patients
        </Text>
      </View>

      <ScrollView
        style={{ flex: 1 }}
        showsVerticalScrollIndicator={false}
        contentContainerStyle={{ paddingBottom: sh(32) }}
        keyboardShouldPersistTaps="handled"
      >

        {/* Stat chips */}
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={{
            paddingHorizontal: s(16),
            paddingTop: sh(12),
            paddingBottom: sh(6),
            gap: s(8),
            alignItems: 'flex-start',
          }}
        >
          {([
            { icon: 'flask-outline'            as IoniconsName, iconBg: '#EEF2FF', iconColor: BLUE,  label: 'Total',     value: stats.total     },
            { icon: 'checkmark-circle-outline' as IoniconsName, iconBg: '#D1FAE5', iconColor: GREEN, label: 'Completed', value: stats.completed },
            { icon: 'eye-outline'              as IoniconsName, iconBg: '#FEF3C7', iconColor: AMBER, label: 'Review',    value: stats.review    },
            { icon: 'alert-circle-outline'     as IoniconsName, iconBg: '#FEE2E2', iconColor: RED,   label: 'Critical',  value: stats.critical  },
            { icon: 'water-outline'            as IoniconsName, iconBg: '#FEE2E2', iconColor: RED,   label: 'Blood',     value: stats.blood     },
            { icon: 'warning-outline'          as IoniconsName, iconBg: '#FEF3C7', iconColor: AMBER, label: 'Abnormal',  value: stats.abnormal  },
          ] as const).map(chip => (
            <View key={chip.label} style={{
              backgroundColor: WHITE, borderRadius: s(12), borderWidth: 1, borderColor: BORDER,
              paddingHorizontal: s(14), paddingVertical: sh(6), alignItems: 'center', gap: sh(3),
              minWidth: s(78), alignSelf: 'flex-start',
            }}>
              <View style={{
                width: s(26), height: s(26), borderRadius: s(7),
                backgroundColor: chip.iconBg, alignItems: 'center', justifyContent: 'center',
              }}>
                <Ionicons name={chip.icon} size={s(15)} color={chip.iconColor} />
              </View>
              <Text style={{ fontSize: s(20), fontWeight: '700', color: TEXT }}>{chip.value}</Text>
              <Text style={{ fontSize: s(10), color: GRAY }}>{chip.label}</Text>
            </View>
          ))}
        </ScrollView>

        {/* Search */}
        <View style={{
          marginHorizontal: s(16), marginTop: sh(8),
          flexDirection: 'row', alignItems: 'center', gap: s(8),
          backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER,
          borderRadius: s(10), paddingHorizontal: s(12), paddingVertical: sh(9),
        }}>
          <Ionicons name="search-outline" size={s(16)} color={LGRAY} />
          <TextInput
            style={{ flex: 1, fontSize: s(13), color: TEXT }}
            placeholder="Search test name, patient, doctor…"
            placeholderTextColor={LGRAY}
            value={search}
            onChangeText={setSearch}
            returnKeyType="search"
            clearButtonMode="while-editing"
          />
        </View>

        {/* Type filter pills */}
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={{
            paddingHorizontal: s(16),
            paddingTop: sh(10),
            paddingBottom: sh(4),
            gap: s(6),
            alignItems: 'flex-start',
          }}
        >
          {TYPE_FILTERS.map(f => {
            const tc     = testTypeConfig(f.key as LabTestType);
            const active = typeFilter === f.key;
            const color  = f.key === 'ALL' ? BLUE : tc.color;
            return (
              <TouchableOpacity
                key={f.key}
                style={{
                  paddingHorizontal: s(14), paddingVertical: sh(7),
                  borderRadius: s(20), borderWidth: 1,
                  borderColor: active ? color : BORDER,
                  backgroundColor: active ? color + '18' : WHITE,
                  alignSelf: 'flex-start',
                }}
                onPress={() => setTypeFilter(f.key)}
                activeOpacity={0.7}
              >
                <Text style={{ fontSize: s(13), fontWeight: active ? '700' : '500', color: active ? color : GRAY }}>
                  {f.label}
                </Text>
              </TouchableOpacity>
            );
          })}
        </ScrollView>

        {/* Result count */}
        <Text style={{ fontSize: s(12), color: GRAY, fontWeight: '500', marginHorizontal: s(16), marginBottom: sh(8) }}>
          {filtered.length} result{filtered.length !== 1 ? 's' : ''}
        </Text>

        {/* Cards */}
        {filtered.length === 0 ? (
          <View style={{ alignItems: 'center', gap: sh(10), paddingVertical: sh(64) }}>
            <Ionicons name="flask-outline" size={s(52)} color={BORDER} />
            <Text style={{ fontSize: s(16), fontWeight: '600', color: TEXT }}>No results found</Text>
            <Text style={{ fontSize: s(13), color: GRAY }}>Try adjusting your filters</Text>
          </View>
        ) : (
          <View style={{ gap: sh(12), paddingHorizontal: s(16) }}>
            {filtered.map(result => {
              const tc         = testTypeConfig(result.test_type);
              const sc         = statusConfig(result.status);
              const pac        = avatarColor(result.patient_name);
              const dac        = avatarColor(result.doctor_name);
              const subTests   = result.result_data ? Object.entries(result.result_data) : [];
              const abnormal   = subTests.filter(([, sub]) => sub.flag !== 'NORMAL');
              const isExpanded = expandedIds.has(result.result_id);
              const hasDetails = subTests.length > 0 || result.result_value;

              return (
                <View key={result.result_id} style={{
                  backgroundColor: WHITE,
                  borderRadius: s(14),
                  borderWidth: 1,
                  borderColor: BORDER,
                  borderLeftWidth: 4,
                  borderLeftColor: result.is_critical ? RED : tc.color,
                  padding: s(14),
                  gap: sh(10),
                }}>

                  {/* Critical banner */}
                  {!!result.is_critical && (
                    <View style={{
                      flexDirection: 'row', alignItems: 'center', gap: s(6),
                      backgroundColor: '#FEE2E2', borderRadius: s(8),
                      paddingHorizontal: s(10), paddingVertical: sh(5),
                      borderWidth: 1, borderColor: '#FECACA',
                    }}>
                      <Ionicons name="alert-circle" size={s(12)} color={RED} />
                      <Text style={{ fontSize: s(11), color: RED, fontWeight: '700' }}>
                        Critical — Immediate attention required
                      </Text>
                    </View>
                  )}

                  {/* Review banner */}
                  {result.status === 'REQUIRES_REVIEW' && !result.is_critical && (
                    <View style={{
                      flexDirection: 'row', alignItems: 'center', gap: s(6),
                      backgroundColor: '#FEF3C7', borderRadius: s(8),
                      paddingHorizontal: s(10), paddingVertical: sh(5),
                      borderWidth: 1, borderColor: '#FDE68A',
                    }}>
                      <Ionicons name="eye-outline" size={s(12)} color={AMBER} />
                      <Text style={{ fontSize: s(11), color: AMBER, fontWeight: '600' }}>Pending review</Text>
                    </View>
                  )}

                  {/* Header: badges + date */}
                  <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: s(6) }}>
                    <View style={{ flexDirection: 'row', gap: s(6), flexWrap: 'wrap' }}>
                      <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(4), paddingHorizontal: s(8), paddingVertical: sh(3), borderRadius: s(20), backgroundColor: tc.bg }}>
                        <Ionicons name={tc.icon} size={s(10)} color={tc.color} />
                        <Text style={{ fontSize: s(10), fontWeight: '700', color: tc.color }}>{tc.label}</Text>
                      </View>
                      <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(4), paddingHorizontal: s(8), paddingVertical: sh(3), borderRadius: s(20), backgroundColor: sc.bg }}>
                        <Ionicons name={sc.icon} size={s(10)} color={sc.color} />
                        <Text style={{ fontSize: s(10), fontWeight: '700', color: sc.color }}>{sc.label}</Text>
                      </View>
                      {abnormal.length > 0 && (
                        <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(4), paddingHorizontal: s(8), paddingVertical: sh(3), borderRadius: s(20), backgroundColor: '#FEE2E2' }}>
                          <Ionicons name="warning-outline" size={s(10)} color={RED} />
                          <Text style={{ fontSize: s(10), fontWeight: '700', color: RED }}>{abnormal.length} abnormal</Text>
                        </View>
                      )}
                    </View>
                    <Text style={{ fontSize: s(11), color: LGRAY }}>{formatDate(result.test_date)}</Text>
                  </View>

                  {/* Test name + icon */}
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(12) }}>
                    <View style={{ width: s(40), height: s(40), borderRadius: s(10), backgroundColor: tc.bg, alignItems: 'center', justifyContent: 'center' }}>
                      <Ionicons name={tc.icon} size={s(20)} color={tc.color} />
                    </View>
                    <Text style={{ flex: 1, fontSize: s(14), fontWeight: '700', color: TEXT, lineHeight: s(20) }}>
                      {result.test_name}
                    </Text>
                  </View>

                  {/* Simple result value */}
                  {!!result.result_value && (
                    <View style={{ backgroundColor: BG, borderRadius: s(8), padding: s(10), borderWidth: 1, borderColor: BORDER, gap: sh(3) }}>
                      <Text style={{ fontSize: s(10), color: LGRAY, textTransform: 'uppercase', letterSpacing: 0.5 }}>Result</Text>
                      <Text style={{ fontSize: s(16), fontWeight: '700', color: TEXT }}>
                        {result.result_value}{result.unit ? ` ${result.unit}` : ''}
                      </Text>
                      {!!result.reference_range && (
                        <Text style={{ fontSize: s(10), color: GRAY, fontStyle: 'italic' }}>
                          Reference: {result.reference_range}
                        </Text>
                      )}
                    </View>
                  )}

                  {/* Patient + doctor row */}
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(10), flexWrap: 'wrap' }}>
                    <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(6) }}>
                      <View style={{ width: s(26), height: s(26), borderRadius: s(13), backgroundColor: pac, alignItems: 'center', justifyContent: 'center' }}>
                        <Text style={{ color: WHITE, fontSize: s(9), fontWeight: '700' }}>{initials(result.patient_name)}</Text>
                      </View>
                      <View>
                        <Text style={{ fontSize: s(9), color: LGRAY, textTransform: 'uppercase' }}>Patient</Text>
                        <Text style={{ fontSize: s(11), fontWeight: '600', color: TEXT }}>{result.patient_name}</Text>
                      </View>
                    </View>
                    <View style={{ width: 1, height: s(24), backgroundColor: BORDER }} />
                    <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(6) }}>
                      <View style={{ width: s(26), height: s(26), borderRadius: s(13), backgroundColor: dac, alignItems: 'center', justifyContent: 'center' }}>
                        <Text style={{ color: WHITE, fontSize: s(9), fontWeight: '700' }}>{initials(result.doctor_name)}</Text>
                      </View>
                      <View>
                        <Text style={{ fontSize: s(9), color: LGRAY, textTransform: 'uppercase' }}>Ordered by</Text>
                        <Text style={{ fontSize: s(11), fontWeight: '600', color: TEXT }}>{result.doctor_name}</Text>
                      </View>
                    </View>
                  </View>

                  {/* Reviewed row */}
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(6) }}>
                    <Ionicons
                      name={result.reviewed_at ? 'checkmark-done-outline' : 'time-outline'}
                      size={s(12)}
                      color={result.reviewed_at ? GREEN : AMBER}
                    />
                    <Text style={{ fontSize: s(11), color: result.reviewed_at ? GRAY : AMBER }}>
                      {result.reviewed_at
                        ? `Reviewed ${formatDateTime(result.reviewed_at)}`
                        : 'Awaiting review'
                      }
                    </Text>
                  </View>

                  {/* Expandable sub-tests */}
                  {hasDetails && (
                    <>
                      {isExpanded && (
                        <View style={{ backgroundColor: BG, borderRadius: s(10), borderWidth: 1, borderColor: BORDER, overflow: 'hidden' }}>
                          <Text style={{ fontSize: s(10), color: GRAY, fontWeight: '600', textTransform: 'uppercase', letterSpacing: 0.5, paddingHorizontal: s(12), paddingTop: sh(10), paddingBottom: sh(6) }}>
                            {subTests.length} Parameter{subTests.length !== 1 ? 's' : ''}
                          </Text>
                          {subTests.map(([key, sub]) => {
                            const fc = flagColor(sub.flag);
                            return (
                              <View key={key} style={{ paddingHorizontal: s(12), paddingVertical: sh(8), borderTopWidth: 1, borderTopColor: BORDER, gap: sh(3) }}>
                                <Text style={{ fontSize: s(12), fontWeight: '600', color: TEXT }}>{labelify(key)}</Text>
                                <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(8), flexWrap: 'wrap' }}>
                                  <Text style={{ fontSize: s(12), color: TEXT }}>{sub.value} {sub.unit}</Text>
                                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(3), paddingHorizontal: s(6), paddingVertical: sh(2), borderRadius: s(8), backgroundColor: fc.bg }}>
                                    <Ionicons name={fc.icon} size={s(9)} color={fc.text} />
                                    <Text style={{ fontSize: s(9), fontWeight: '700', color: fc.text }}>{sub.flag}</Text>
                                  </View>
                                </View>
                                <Text style={{ fontSize: s(10), color: LGRAY, fontStyle: 'italic' }}>{sub.reference}</Text>
                                {!!sub.interpretation && (
                                  <Text style={{ fontSize: s(10), color: GRAY }}>{sub.interpretation}</Text>
                                )}
                              </View>
                            );
                          })}
                        </View>
                      )}
                      <TouchableOpacity
                        style={{ flexDirection: 'row', alignItems: 'center', gap: s(4), alignSelf: 'flex-start' }}
                        onPress={() => toggleExpand(result.result_id)}
                        activeOpacity={0.7}
                      >
                        <Text style={{ fontSize: s(12), color: BLUE, fontWeight: '500' }}>
                          {isExpanded
                            ? 'Hide results'
                            : `View ${subTests.length > 0 ? `${subTests.length} parameters` : 'result'}`
                          }
                        </Text>
                        <Ionicons name={isExpanded ? 'chevron-up' : 'chevron-down'} size={s(13)} color={BLUE} />
                      </TouchableOpacity>
                    </>
                  )}
                </View>
              );
            })}
          </View>
        )}
      </ScrollView>
    </View>
  );
}
