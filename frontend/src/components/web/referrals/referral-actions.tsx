import { StyleSheet, View } from 'react-native';
import { Button, space } from '@/components/web/ui';
import { canRespond, hasAction } from '@/lib/referrals/format';
import type { Referral } from '@/lib/referrals/types';

/**
 * The dialogs a doctor can open on a referral:
 * `accept`, `decline`, `complete` = the named specialist responds; `outcome` = the referring doctor records the
 * outcome on the specialist's behalf (no platform specialist); `assign` = assign or change the specialist;
 * `cancel` = withdraw the referral.
 */
export type ReferralDialogKind = 'accept' | 'decline' | 'complete' | 'outcome' | 'assign' | 'cancel';

/** True when the server lists at least one action for the caller. */
export function hasAnyAction(referral: Referral): boolean {
  return canRespond(referral) || hasAction(referral, 'ASSIGN_SPECIALIST') || hasAction(referral, 'CANCEL');
}

/**
 * The buttons for what the signed-in doctor may do with a referral now. A button exists only when the server
 * lists its action in `allowedActions`; nothing is shown disabled.
 */
export function ReferralActionButtons({ referral, onAction, size = 'sm' }: {
  referral: Referral;
  onAction: (kind: ReferralDialogKind, referral: Referral) => void;
  size?:    'sm' | 'md';
}) {
  if (!hasAnyAction(referral)) return null;
  const about = `the referral for ${referral.patientName}`;
  // On the specialist's behalf every outcome goes through one dialog that asks for the required note.
  const onBehalf = referral.outcomeNoteRequired && canRespond(referral);

  return (
    <View style={styles.row}>
      {onBehalf ? (
        <Button
          label="Record outcome" icon="create-outline" size={size}
          onPress={() => onAction('outcome', referral)}
          accessibilityLabel={`Record outcome of ${about}`}
        />
      ) : (
        <>
          {hasAction(referral, 'ACCEPT') ? (
            <Button
              label="Accept" icon="checkmark" size={size}
              onPress={() => onAction('accept', referral)}
              accessibilityLabel={`Accept ${about}`}
            />
          ) : null}
          {hasAction(referral, 'DECLINE') ? (
            <Button
              label="Decline" icon="close" variant="secondary" size={size}
              onPress={() => onAction('decline', referral)}
              accessibilityLabel={`Decline ${about}`}
            />
          ) : null}
          {hasAction(referral, 'COMPLETE') ? (
            <Button
              label="Mark as completed" icon="checkmark-done" size={size}
              onPress={() => onAction('complete', referral)}
              accessibilityLabel={`Mark as completed: ${about}`}
            />
          ) : null}
        </>
      )}
      {hasAction(referral, 'ASSIGN_SPECIALIST') ? (
        <Button
          label={referral.specialistType === 'UNASSIGNED' ? 'Assign specialist' : 'Change specialist'}
          icon="person-add-outline" variant="secondary" size={size}
          onPress={() => onAction('assign', referral)}
          accessibilityLabel={`${referral.specialistType === 'UNASSIGNED' ? 'Assign specialist' : 'Change specialist'} for ${about}`}
        />
      ) : null}
      {hasAction(referral, 'CANCEL') ? (
        <Button
          label="Cancel referral" icon="trash-outline" variant="ghost" size={size}
          onPress={() => onAction('cancel', referral)}
          accessibilityLabel={`Cancel referral for ${referral.patientName}`}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, maxWidth: '100%' },
});
