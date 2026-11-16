import { StyleSheet, View } from 'react-native';
import { space, StatusBadge, statusTone, type StatusTone } from '@/components/web/ui';
import { referralPriorityLabel, referralStatusLabel } from '@/lib/referrals/format';
import type { Referral, ReferralPriority, ReferralStatus } from '@/lib/referrals/types';

// Status, priority and "Unassigned" badges of a referral. Each badge carries its meaning in words and an icon,
// never by colour alone.

export function ReferralStatusBadge({ status }: { status: ReferralStatus }) {
  return <StatusBadge label={referralStatusLabel(status)} tone={statusTone('referral', status)} />;
}

const PRIORITY_TONE: Record<ReferralPriority, StatusTone> = {
  ROUTINE:   'neutral',
  URGENT:    'warning',
  EMERGENCY: 'critical',
};

export function ReferralPriorityBadge({ priority }: { priority: ReferralPriority }) {
  const tone = PRIORITY_TONE[priority] ?? 'neutral';
  return (
    <StatusBadge
      label={`${referralPriorityLabel(priority)} priority`}
      tone={tone}
      icon={tone === 'neutral' ? 'flag-outline' : tone === 'warning' ? 'flag' : 'warning'}
    />
  );
}

/** Shown while a referral has no specialist: it cannot be answered until someone is assigned. */
export function UnassignedBadge() {
  return <StatusBadge label="Unassigned" tone="info" icon="person-add-outline" />;
}

/** The badges of one referral in a wrapping row: status, priority (when not routine) and Unassigned. */
export function ReferralBadges({ referral, showRoutine = false }: {
  referral: Referral;
  /** Also show the priority badge for a routine referral. Default false: only urgency stands out in a list. */
  showRoutine?: boolean;
}) {
  return (
    <View style={styles.row}>
      <ReferralStatusBadge status={referral.status} />
      {showRoutine || referral.priority !== 'ROUTINE' ? <ReferralPriorityBadge priority={referral.priority} /> : null}
      {referral.specialistType === 'UNASSIGNED' ? <UnassignedBadge /> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.xs, maxWidth: '100%' },
});
