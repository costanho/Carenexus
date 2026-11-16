import { StyleSheet, Text, View } from 'react-native';
import { ariaLevel, Button, Card, colors, fontSize, space, useUiTheme } from '@/components/web/ui';
import { referralDateLabel, specialistSummary } from '@/lib/referrals/format';
import type { DoctorReferralTab } from '@/lib/referrals/params';
import type { Referral } from '@/lib/referrals/types';
import { ReferralActionButtons, type ReferralDialogKind } from './referral-actions';
import { ReferralBadges } from './referral-badges';

/** One referral in a doctor's Sent or Received list: who, to or from whom, where it stands, and what can be done. */
export function DoctorReferralCard({ referral, perspective, selected = false, onOpen, onAction }: {
  referral: Referral;
  /** `sent`: the doctor issued it (shows the specialist). `received`: addressed to the doctor (shows the referrer). */
  perspective: DoctorReferralTab;
  /** The referral whose details are open. */
  selected?: boolean;
  onOpen:   (referral: Referral) => void;
  onAction: (kind: ReferralDialogKind, referral: Referral) => void;
}) {
  const { accent } = useUiTheme();
  const counterpart = perspective === 'sent'
    ? `To: ${specialistSummary(referral)}`
    : `From: ${referral.referringDoctorName}`;
  const declined = referral.status === 'DECLINED' ? referral.declineReason?.trim() || null : null;

  return (
    <Card style={selected ? { borderColor: accent } : undefined}>
      <View style={styles.card}>
        <View style={styles.head}>
          <View style={styles.titles}>
            <Text style={styles.title} accessibilityRole="header" {...ariaLevel(2)}>{referral.patientName}</Text>
            <Text style={styles.meta}>{counterpart}</Text>
            <Text style={styles.meta}>{`Issued ${referralDateLabel(referral)}`}</Text>
          </View>
          <ReferralBadges referral={referral} />
        </View>

        <Text style={styles.reason} numberOfLines={2}>{referral.reason}</Text>
        {declined ? <Text style={styles.meta} numberOfLines={2}>{`Declined: ${declined}`}</Text> : null}

        <View style={styles.actions}>
          <Button
            label="View details" icon="document-text-outline" variant="secondary" size="sm"
            onPress={() => onOpen(referral)}
            accessibilityLabel={`View details of the referral for ${referral.patientName}, issued ${referralDateLabel(referral)}`}
          />
          <ReferralActionButtons referral={referral} onAction={onAction} />
        </View>
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  card:    { gap: space.sm, minWidth: 0 },
  head:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.sm },
  titles:  { flexGrow: 1, flexShrink: 1, flexBasis: 220, minWidth: 0, gap: 2 },
  title:   { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  meta:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  reason:  { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, marginTop: space.xs },
});
