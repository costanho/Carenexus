import { useEffect, useRef } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { colors, fontSize, Icon, type IoniconsName, PagedList, space } from '@/components/web/ui';
import { useCaregiverActivity } from '@/hooks/use-caregiver-access';
import { describeActivity, formatAccessDateTime } from '@/lib/caregiver-access/format';
import type { CaregiverActivityEntry } from '@/lib/caregiver-access/types';

const ACTION_ICONS: Record<string, IoniconsName> = {
  READ:   'eye-outline',
  CREATE: 'add-circle-outline',
  UPDATE: 'create-outline',
  DELETE: 'trash-outline',
};

function ActivityRow({ entry }: { entry: CaregiverActivityEntry }) {
  const icon = ACTION_ICONS[(entry.action ?? '').trim().toUpperCase()] ?? 'ellipse-outline';
  return (
    <View style={styles.row}>
      <Icon name={icon} size={16} color={colors.textMuted} style={styles.rowIcon} />
      <Text style={styles.what}>{describeActivity(entry)}</Text>
      <Text style={styles.when}>{formatAccessDateTime(entry.timestamp) || 'Time not recorded'}</Text>
    </View>
  );
}

/**
 * What the caregiver of one grant viewed or did with the patient's information, newest first, page by page.
 * The rows come from the audit log: the action, the kind of information and the time, nothing else.
 */
export function ActivityPanel({ accessId, caregiverName, onStale }: {
  accessId:      number;
  caregiverName: string;
  /** The grant no longer exists or is no longer the patient's: the screen reloads its lists. */
  onStale:       (message: string) => void;
}) {
  const activity = useCaregiverActivity(accessId);

  const onStaleRef = useRef(onStale);
  onStaleRef.current = onStale;
  const { errorStatus, error } = activity;
  useEffect(() => {
    if ((errorStatus === 403 || errorStatus === 404) && error) onStaleRef.current(error);
  }, [errorStatus, error]);

  return (
    <PagedList
      state={activity}
      keyOf={entry => entry.auditId}
      renderItem={entry => <ActivityRow entry={entry} />}
      label={`Activity of ${caregiverName}`}
      loadMoreLabel="Load earlier activity"
      noAccessMessage="This access is no longer yours to view."
      header={
        <Text style={styles.intro}>
          {`What ${caregiverName} viewed or did with your information, newest first. Attempts that were refused are not listed.`}
        </Text>
      }
      empty={{
        icon: 'footsteps-outline',
        title: 'No activity yet',
        sub: `${caregiverName} has not viewed or changed any of your information.`,
      }}
    />
  );
}

const styles = StyleSheet.create({
  intro: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  row: {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', columnGap: space.sm, rowGap: 2,
    paddingVertical: space.sm, borderBottomWidth: 1, borderBottomColor: colors.border,
  },
  rowIcon: { flexShrink: 0 },
  what:    { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0, fontSize: fontSize.md, color: colors.text, fontWeight: '500' },
  when:    { fontSize: fontSize.sm, color: colors.textMuted },
});
