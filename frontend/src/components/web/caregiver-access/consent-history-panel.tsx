import { useEffect, useRef } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { colors, fontSize, PagedList, space, StatusBadge, type StatusTone } from '@/components/web/ui';
import { useConsentHistory } from '@/hooks/use-caregiver-access';
import {
  caregiverDisplayName,
  consentActorLabel,
  consentEndsAccess,
  consentEventLabel,
  describeConsentScope,
  formatAccessDateTime,
} from '@/lib/caregiver-access/format';
import type { ConsentRecord } from '@/lib/caregiver-access/types';

const EVENT_TONES: Record<string, StatusTone> = {
  GRANTED:                'success',
  COMMUNICATION_APPROVED: 'success',
  EXTENDED:               'success',
  PERMISSIONS_CHANGED:    'info',
  NARROWED:               'info',
  REVOKED:                'danger',
  EXPIRED:                'neutral',
};

function ConsentRow({ record, showCaregiver }: { record: ConsentRecord; showCaregiver: boolean }) {
  const ended = consentEndsAccess(record.eventKind);
  return (
    <View style={styles.row}>
      <View style={styles.rowHead}>
        <StatusBadge label={consentEventLabel(record.eventKind)} tone={EVENT_TONES[record.eventKind] ?? 'neutral'} />
        <Text style={styles.when}>{formatAccessDateTime(record.recordedAt) || 'Time not recorded'}</Text>
      </View>
      {showCaregiver ? <Text style={styles.line}>{`Caregiver: ${caregiverDisplayName(record)}`}</Text> : null}
      <Text style={styles.line}>{`Done by: ${consentActorLabel(record)}`}</Text>
      <Text style={styles.scopeTitle}>{ended ? 'Access that ended' : 'Access after this step'}</Text>
      {describeConsentScope(record.scope).map((line, index) => (
        <Text key={index} style={styles.line}>{line}</Text>
      ))}
    </View>
  );
}

/**
 * The consent ledger: one row per recorded step (granted, changed, approved, extended, reduced, removed,
 * expired) with a summary of the access right after it. With `accessId` only the steps of that grant are listed;
 * without it every caregiver's steps are listed, including caregivers whose access was removed.
 */
export function ConsentHistoryPanel({ accessId, caregiverName, refreshKey = 0 }: {
  accessId:       number | null;
  /** The caregiver of the grant, for the wording; omit for the full ledger. */
  caregiverName?: string;
  /** Change it after a save so an open panel shows the step that was just recorded. */
  refreshKey?:    number;
}) {
  const history = useConsentHistory(accessId);

  const { refetch } = history;
  const seenKey = useRef(refreshKey);
  useEffect(() => {
    if (seenKey.current === refreshKey) return;
    seenKey.current = refreshKey;
    refetch();
  }, [refreshKey, refetch]);

  const whole = accessId === null;
  return (
    <PagedList
      state={history}
      keyOf={record => record.consentId}
      renderItem={record => <ConsentRow record={record} showCaregiver={whole} />}
      label={whole ? 'Consent history' : `Consent history for ${caregiverName ?? 'this caregiver'}`}
      loadMoreLabel="Load earlier records"
      header={
        <Text style={styles.intro}>
          {whole
            ? 'Every recorded change to who can see your information, newest first. Records are never edited or deleted.'
            : `Every recorded change to ${caregiverName ?? 'this caregiver'}'s access, newest first. Records are never edited or deleted.`}
        </Text>
      }
      empty={{
        icon: 'document-text-outline',
        title: 'No consent records yet',
        sub: 'Changes made before CareNexus started keeping consent records are not listed.',
      }}
    />
  );
}

const styles = StyleSheet.create({
  intro: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  row:   { gap: space.xs, paddingVertical: space.sm, borderBottomWidth: 1, borderBottomColor: colors.border, minWidth: 0 },
  rowHead: {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between',
    columnGap: space.sm, rowGap: space.xs,
  },
  when:       { fontSize: fontSize.sm, color: colors.textMuted },
  scopeTitle: { fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted, marginTop: space.xs },
  line:       { fontSize: fontSize.sm, color: colors.text, lineHeight: 19 },
});
