import { Ionicons } from '@expo/vector-icons';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { useCaregiverDependents } from '@/hooks/use-caregiver-dependents';
import { ComingSoonState, EmptyState, ErrorState, LoadingState } from './proxy-states';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';

const AVATAR_COLORS = ['#4F46E5', '#0D9488', '#F59E0B', '#EF4444', '#8B5CF6', '#10B981', '#0EA5E9'];
function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < (name?.length ?? 0); i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}
function initials(name: string) {
  return (name ?? '?').split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
}

// Care plans are not stored by the backend yet, so this screen lists the
// linked patients and states plainly that no care plan is available.
export default function PatientProxyCarePlans() {
  const { dependents, loading, error, refetch } = useCaregiverDependents();

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>
      <View style={styles.pageHeader}>
        <Text style={styles.pageTitle} accessibilityRole="header">Care Plans</Text>
        <Text style={styles.pageSub}>Care plans for your linked patients</Text>
      </View>

      {loading ? (
        <LoadingState label="Loading linked patients" />
      ) : error ? (
        <ErrorState message={error} onRetry={refetch} />
      ) : dependents.length === 0 ? (
        <EmptyState icon="people-outline" title="No linked patients yet" />
      ) : (
        <>
          <ComingSoonState
            icon="clipboard-outline"
            title="Care plans are not available yet"
            sub="Care teams can't share care plans through CareNexus yet. When they can, they will appear here."
          />
          <View style={styles.card}>
            {dependents.map((dep, i) => {
              const name = dep.patientName ?? 'Unknown';
              return (
                <View key={dep.accessId}>
                  {i > 0 && <View style={styles.sep} />}
                  <View style={styles.row}>
                    <View style={[styles.avatar, { backgroundColor: avatarColor(name) }]}>
                      <Text style={styles.avatarText}>{initials(name)}</Text>
                    </View>
                    <View style={{ flex: 1 }}>
                      <Text style={styles.name}>{name}</Text>
                      <Text style={styles.sub}>No care plan has been shared</Text>
                    </View>
                    <Ionicons name="clipboard-outline" size={18} color={BORDER} importantForAccessibility="no" />
                  </View>
                </View>
              );
            })}
          </View>
        </>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll:     { flex: 1, backgroundColor: BG },
  container:  { padding: 24, paddingBottom: 48, gap: 20 },
  pageHeader: { marginBottom: 4 },
  pageTitle:  { fontSize: 22, fontWeight: '700', color: TEXT },
  pageSub:    { fontSize: 13, color: GRAY, marginTop: 3 },

  card:       { backgroundColor: WHITE, borderRadius: 14, borderWidth: 1, borderColor: BORDER, padding: 16 },
  row:        { flexDirection: 'row', alignItems: 'center', gap: 12 },
  sep:        { height: 1, backgroundColor: BORDER, marginVertical: 12 },
  avatar:     { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center' },
  avatarText: { color: WHITE, fontSize: 14, fontWeight: '700' },
  name:       { fontSize: 14, fontWeight: '700', color: TEXT },
  sub:        { fontSize: 12, color: GRAY, marginTop: 2 },
});

