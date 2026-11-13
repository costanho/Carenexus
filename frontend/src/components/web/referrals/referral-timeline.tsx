import { StyleSheet, Text, View } from 'react-native';
import { colors, fontSize, Icon, type IoniconsName, space, STATUS_TONES } from '@/components/web/ui';
import { referralTimeline, type TimelineStepState } from '@/lib/referrals/format';
import type { Referral } from '@/lib/referrals/types';

// The status timeline of a referral: what happened and when, oldest first, ending with what it is waiting for
// while it is still open. The title of each step says what it is in words ("Accepted", "Waiting for the
// specialist to respond", "Cancelled"); the icon and colour only repeat it.

const STEP_ICON: Record<TimelineStepState, IoniconsName> = {
  done:    'checkmark-circle',
  current: 'time-outline',
  stopped: 'close-circle',
};

function stepColor(state: TimelineStepState): string {
  if (state === 'done') return STATUS_TONES.success.fg;
  if (state === 'stopped') return STATUS_TONES.neutral.fg;
  return STATUS_TONES.warning.fg;
}

export function ReferralTimeline({ referral }: { referral: Referral }) {
  const steps = referralTimeline(referral);
  return (
    <View role="list" aria-label="Referral timeline" style={styles.list}>
      {steps.map((step, index) => {
        const last = index === steps.length - 1;
        return (
          <View key={step.key} role="listitem" style={styles.step}>
            <View style={styles.rail}>
              <Icon name={STEP_ICON[step.state]} size={18} color={stepColor(step.state)} />
              {!last ? <View style={styles.line} /> : null}
            </View>
            <View style={[styles.body, !last && styles.bodyGap]}>
              <Text style={[styles.title, step.state === 'current' && styles.titleCurrent]}>{step.title}</Text>
              {step.when ? <Text style={styles.meta}>{step.when}</Text> : null}
              {step.detail ? <Text style={styles.meta}>{step.detail}</Text> : null}
            </View>
          </View>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  list:  { minWidth: 0 },
  step:  { flexDirection: 'row', gap: space.sm, minWidth: 0 },
  rail:  { alignItems: 'center', width: 18 },
  line:  { flexGrow: 1, width: 2, minHeight: space.sm, marginVertical: 2, backgroundColor: colors.border },
  body:  { flex: 1, minWidth: 0, gap: 1 },
  bodyGap: { paddingBottom: space.md },
  title: { fontSize: fontSize.md, fontWeight: '600', color: colors.text, lineHeight: 19 },
  titleCurrent: { color: STATUS_TONES.warning.fg },
  meta:  { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
