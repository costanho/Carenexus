import { Ionicons } from '@expo/vector-icons';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { useCaregiverDependents } from '@/hooks/use-caregiver-dependents';
import { ComingSoonState, EmptyState, ErrorState, LoadingState } from '@/components/web/proxy/patient/proxy-states';
import { avatarColor, BG, BORDER, capitalize, depName, GRAY, initials, ScreenHeader, SectionCard, TEXT, useScale, WHITE } from './patient-proxy-mobile-shared';

// Care plans are not stored by the backend yet, so (as on web) this lists the loved ones and says plainly
// that no care plan is available. Nothing here is made up.
export default function PatientProxyMobileCarePlans({ onBack }: { onBack: () => void }) {
  const { s } = useScale();
  const { dependents, loading, error, refetch } = useCaregiverDependents();

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={{ padding: s(14), paddingBottom: s(24) }} showsVerticalScrollIndicator={false}>
      <ScreenHeader title="Care Plans" sub="Care plans for your loved ones" onBack={onBack} />

      {loading && dependents.length === 0 ? (
        <LoadingState label="Loading loved ones" />
      ) : error && dependents.length === 0 ? (
        <ErrorState message={error} onRetry={refetch} />
      ) : dependents.length === 0 ? (
        <EmptyState icon="people-outline" title="No loved ones linked yet" />
      ) : (
        <>
          <ComingSoonState
            icon="clipboard-outline"
            title="Care plans are not available yet"
            sub="Care teams can't share care plans through CareNexus yet. When they can, they will appear here."
          />
          <SectionCard>
            {dependents.map((dep, i) => {
              const name = depName(dep);
              return (
                <View key={dep.accessId}>
                  {i > 0 && <View style={[styles.sep, { marginBottom: s(10) }]} />}
                  <View style={[styles.row, { gap: s(10) }]} accessible accessibilityLabel={`${name}: no care plan has been shared`}>
                    <View style={{ width: s(38), height: s(38), borderRadius: s(19), backgroundColor: avatarColor(name), alignItems: 'center', justifyContent: 'center' }}>
                      <Text style={{ color: WHITE, fontSize: s(13), fontWeight: '700' }}>{initials(name)}</Text>
                    </View>
                    <View style={{ flex: 1, minWidth: 0 }}>
                      <Text style={{ fontSize: s(13), fontWeight: '700', color: TEXT }} numberOfLines={1}>{name}</Text>
                      <Text style={{ fontSize: s(11), color: GRAY, marginTop: s(1) }} numberOfLines={1}>
                        {capitalize(dep.relationship)} · No care plan has been shared
                      </Text>
                    </View>
                    <Ionicons name="clipboard-outline" size={s(17)} color={BORDER} importantForAccessibility="no" />
                  </View>
                </View>
              );
            })}
          </SectionCard>
        </>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll: { flex: 1, backgroundColor: BG },
  row:    { flexDirection: 'row', alignItems: 'center' },
  sep:    { height: StyleSheet.hairlineWidth, backgroundColor: BORDER },
});
