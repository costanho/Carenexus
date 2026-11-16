import { StyleSheet, Text, View } from 'react-native';
import { colors, fontSize, InfoRow } from '@/components/web/ui';
import { referralDateLabel, specialistDetail, specialistName } from '@/lib/referrals/format';
import type { Referral } from '@/lib/referrals/types';

// The recorded facts of a referral as label and value rows. Only what the server sent is shown: a row whose
// value was never recorded (no summary, no note) is left out rather than shown empty.

function Paragraph({ text }: { text: string }) {
  return <Text style={styles.paragraph}>{text}</Text>;
}

export function ReferralFacts({ referral, audience }: {
  referral: Referral;
  /** `doctor` adds the patient and the consultation reference; patients and caregivers already know whose it is. */
  audience: 'doctor' | 'reader';
}) {
  const name = specialistName(referral);
  const detail = specialistDetail(referral);
  const contact = referral.specialistType === 'EXTERNAL' ? referral.externalSpecialistContact?.trim() || null : null;
  const summary = referral.clinicalSummary?.trim() || null;
  const declineReason = referral.status === 'DECLINED' ? referral.declineReason?.trim() || null : null;
  const outcomeNote = referral.outcomeNote?.trim() || null;
  const recorder = referral.outcomeRecordedBySystemId ? referral.outcomeRecordedByName?.trim() || null : null;

  return (
    <View style={styles.rows}>
      {audience === 'doctor' ? <InfoRow label="Patient" value={referral.patientName} /> : null}
      <InfoRow label="Referred by" value={referral.referringDoctorName} />
      <InfoRow label="Specialist">
        {name ? (
          <View style={styles.stack}>
            <Text style={styles.value}>{name}</Text>
            {detail ? <Text style={styles.sub}>{detail}</Text> : null}
            {referral.specialistType === 'EXTERNAL' ? <Text style={styles.sub}>Outside CareNexus</Text> : null}
            {contact ? <Text style={styles.sub}>{`Contact: ${contact}`}</Text> : null}
          </View>
        ) : (
          <Text style={[styles.value, styles.muted]}>Not assigned yet</Text>
        )}
      </InfoRow>
      <InfoRow label="Issued on" value={referralDateLabel(referral)} />
      <InfoRow label="Reason"><Paragraph text={referral.reason} /></InfoRow>
      {summary ? <InfoRow label="Clinical summary"><Paragraph text={summary} /></InfoRow> : null}
      {audience === 'doctor' && referral.consultationId != null ? (
        <InfoRow label="Consultation" value={`Issued from consultation ${referral.consultationId}`} />
      ) : null}
      {declineReason ? <InfoRow label="Reason for declining"><Paragraph text={declineReason} /></InfoRow> : null}
      {outcomeNote ? <InfoRow label="Outcome note"><Paragraph text={outcomeNote} /></InfoRow> : null}
      {recorder ? <InfoRow label="Outcome recorded by" value={`${recorder}, on the specialist's behalf`} /> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  rows:      { minWidth: 0 },
  stack:     { gap: 1, minWidth: 0 },
  value:     { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  muted:     { color: colors.textSubtle },
  sub:       { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  // Clinical text keeps the line breaks its author typed.
  paragraph: { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
});
