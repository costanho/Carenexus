import { Ionicons } from '@expo/vector-icons';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useCaregiverDependents, type CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import { formatDateOnly } from '@/lib/ui/date-only';
import { ErrorState, LoadingState } from '@/components/web/proxy/patient/proxy-states';
import {
  avatarColor, BG, BORDER, capitalize, depName, GRAY, GRAY2, GREEN, initials, ORANGE, PURPLE, PURPLE_BG, RED,
  ScreenHeader, SectionCard, TEXT, useScale, WHITE,
} from './patient-proxy-mobile-shared';

function accessLabel(level: string) {
  if (level === 'FULL_ACCESS') return 'Full Access';
  if (level === 'VIEW_ONLY')   return 'View Only';
  if (level === 'EDIT_ONLY')   return 'Edit Only';
  if (level === 'CUSTOM')      return 'Custom';
  return capitalize(level);
}
function healthDotColor(s: string | null) {
  if (s === 'STABLE')   return GREEN;
  if (s === 'MONITOR')  return ORANGE;
  if (s === 'CRITICAL') return RED;
  return GRAY2;
}

function InfoRow({ label, value }: { label: string; value: string }) {
  const { s } = useScale();
  return (
    <View style={[styles.infoRow, { gap: s(8), paddingVertical: s(4) }]} accessible accessibilityLabel={`${label}: ${value}`}>
      <Text style={{ fontSize: s(12), color: GRAY, fontWeight: '500', flexShrink: 0 }}>{label}</Text>
      <Text style={{ fontSize: s(13), color: TEXT, fontWeight: '600', flex: 1, textAlign: 'right' }} numberOfLines={2}>{value}</Text>
    </View>
  );
}

function LovedOneRow({ dep, onManage }: { dep: CaregiverDependent; onManage?: (dep: CaregiverDependent) => void }) {
  const { s } = useScale();
  const name   = depName(dep);
  // expiresAt is the end of a calendar day; only the date part is shown
  const expiry = dep.expiresAt ? `Ends ${formatDateOnly(dep.expiresAt)}` : 'No end date';
  return (
    <View style={[styles.row, { gap: s(10), paddingVertical: s(6) }]}>
      <View importantForAccessibility="no-hide-descendants" accessibilityElementsHidden>
        <View style={{ width: s(42), height: s(42), borderRadius: s(21), backgroundColor: avatarColor(name), alignItems: 'center', justifyContent: 'center' }}>
          <Text style={{ color: WHITE, fontSize: s(14), fontWeight: '700' }}>{initials(name)}</Text>
        </View>
        <View style={[styles.dot, { width: s(11), height: s(11), borderRadius: s(6), backgroundColor: healthDotColor(dep.patientHealthStatus) }]} />
      </View>
      <View style={{ flex: 1, minWidth: 0, gap: s(2) }} accessible accessibilityLabel={`${name}, ${capitalize(dep.relationship)}, ${accessLabel(dep.accessLevel)}, ${expiry}`}>
        <Text style={{ fontSize: s(13), fontWeight: '700', color: TEXT }} numberOfLines={1}>{name}</Text>
        <Text style={{ fontSize: s(11), color: PURPLE, fontWeight: '500' }} numberOfLines={1}>{capitalize(dep.relationship)}</Text>
        <View style={[styles.row, { gap: s(8), flexWrap: 'wrap' }]}>
          <View style={{ backgroundColor: PURPLE_BG, borderRadius: s(10), paddingHorizontal: s(7), paddingVertical: s(2) }}>
            <Text style={{ fontSize: s(10), color: PURPLE, fontWeight: '600' }}>{accessLabel(dep.accessLevel)}</Text>
          </View>
          <Text style={{ fontSize: s(10), color: GRAY }} numberOfLines={1}>{expiry}</Text>
        </View>
      </View>
      {onManage && (
        <TouchableOpacity
          style={[styles.manageBtn, { borderRadius: s(8), paddingHorizontal: s(10), paddingVertical: s(6), flexShrink: 0 }]}
          onPress={() => onManage(dep)}
          activeOpacity={0.8}
          accessibilityRole="button"
          accessibilityLabel={`Open profile and access for ${name}`}
        >
          <Text style={{ fontSize: s(12), color: TEXT, fontWeight: '500' }}>Manage</Text>
        </TouchableOpacity>
      )}
    </View>
  );
}

/** Profile tab: the caregiver's own account details and the loved ones linked to it. */
export default function PatientProxyMobileSettings({ onNavigate, onSelectPatient }: {
  onNavigate?: (key: string) => void;
  onSelectPatient?: (dep: CaregiverDependent) => void;
}) {
  const { s } = useScale();
  const { user, loading: userLoading } = useCurrentUser();
  const { dependents, loading, error, refetch } = useCaregiverDependents();

  const fullName = user ? `${user.first_name} ${user.last_name}`.trim() || 'Caregiver' : userLoading ? '…' : 'Caregiver';

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={{ padding: s(14), paddingBottom: s(24) }} showsVerticalScrollIndicator={false}>
      <ScreenHeader title="Profile" sub="Your account and linked loved ones" />

      {/* Hero */}
      <SectionCard>
        <View style={[styles.row, { gap: s(12) }]}>
          <View
            style={{ width: s(60), height: s(60), borderRadius: s(30), backgroundColor: user ? avatarColor(fullName) : PURPLE, alignItems: 'center', justifyContent: 'center' }}
            importantForAccessibility="no-hide-descendants"
            accessibilityElementsHidden
          >
            <Text style={{ color: WHITE, fontSize: s(20), fontWeight: '700' }}>{user ? initials(fullName) : ''}</Text>
          </View>
          <View style={{ flex: 1, minWidth: 0 }}>
            <Text style={{ fontSize: s(17), fontWeight: '700', color: TEXT }} numberOfLines={1}>{fullName}</Text>
            <Text style={{ fontSize: s(12), color: GRAY, marginTop: s(2) }} numberOfLines={1}>Caregiver</Text>
          </View>
        </View>
      </SectionCard>

      {/* Personal information */}
      <SectionCard title="Personal Information">
        <InfoRow label="First name" value={user?.first_name || '—'} />
        <View style={styles.sep} />
        <InfoRow label="Last name" value={user?.last_name || '—'} />
        <View style={styles.sep} />
        <InfoRow label="Email" value={user?.email || '—'} />
        <View style={styles.sep} />
        <InfoRow label="Role" value="Caregiver" />
      </SectionCard>

      {/* Linked loved ones */}
      <SectionCard title="Linked Loved Ones">
        <Text style={{ fontSize: s(12), color: GRAY, marginTop: -s(4) }}>The people who have given you caregiver access</Text>
        {loading && dependents.length === 0 ? (
          <LoadingState compact label="Loading loved ones" />
        ) : error && dependents.length === 0 ? (
          <ErrorState compact message={error} onRetry={refetch} />
        ) : dependents.length === 0 ? (
          <Text style={{ fontSize: s(13), color: GRAY, paddingVertical: s(6) }}>No loved ones linked yet.</Text>
        ) : (
          dependents.map((dep, i) => (
            <View key={dep.accessId}>
              {i > 0 && <View style={styles.sep} />}
              <LovedOneRow dep={dep} onManage={onSelectPatient} />
            </View>
          ))
        )}
        {onNavigate && (
          <TouchableOpacity
            style={[styles.addBtn, { borderRadius: s(10), paddingVertical: s(10), gap: s(6), marginTop: s(4) }]}
            onPress={() => onNavigate('add-dependent')}
            activeOpacity={0.8}
            accessibilityRole="button"
          >
            <Ionicons name="add" size={s(16)} color={PURPLE} importantForAccessibility="no" />
            <Text style={{ fontSize: s(13), color: PURPLE, fontWeight: '600' }}>
              {!loading && !error && dependents.length === 0 ? 'Add a Loved One' : 'Add Another Loved One'}
            </Text>
          </TouchableOpacity>
        )}
      </SectionCard>

      {/* Security */}
      <SectionCard title="Security">
        <View style={[styles.row, { gap: s(8) }]}>
          <Ionicons name="lock-closed-outline" size={s(16)} color={GRAY} importantForAccessibility="no" />
          <Text style={{ fontSize: s(12), color: GRAY, flex: 1, lineHeight: s(17) }}>
            Password changes and two-factor authentication are not available in the app yet.
          </Text>
        </View>
      </SectionCard>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll:    { flex: 1, backgroundColor: BG },
  row:       { flexDirection: 'row', alignItems: 'center' },
  infoRow:   { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  sep:       { height: StyleSheet.hairlineWidth, backgroundColor: BORDER },
  dot:       { position: 'absolute', bottom: 0, right: 0, borderWidth: 2, borderColor: WHITE },
  manageBtn: { borderWidth: 1, borderColor: BORDER, backgroundColor: WHITE },
  addBtn:    { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', borderWidth: 1, borderColor: PURPLE },
});
