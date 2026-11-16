import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, Button, Card, colors, fontSize, LoadingView, PagedList, space, useUiTheme,
} from '@/components/web/ui';
import { usePatientReferrals, useReferral } from '@/hooks/use-referrals';
import { referralDateLabel, referralStatusSentence, specialistSummary } from '@/lib/referrals/format';
import type { Referral } from '@/lib/referrals/types';
import { ReferralBadges } from './referral-badges';
import { ReferralFacts } from './referral-facts';
import { ReferralTimeline } from './referral-timeline';

// The read-only referral list of one patient: the patient's own screen and a caregiver's panel for a loved one
// both render it. Each referral shows the specialist, where it stands and its timeline; the recorded details
// open on request.

const UNAVAILABLE = 'This referral is no longer available to you.';

/** One referral as a patient or caregiver reads it. No actions: referrals are managed by the doctors. */
export function PatientReferralCard({ referral, highlighted = false, headingLevel = 2 }: {
  referral: Referral;
  /** The referral a notification link pointed at: outlined and opened. */
  highlighted?: boolean;
  headingLevel?: 2 | 3;
}) {
  const { accent } = useUiTheme();
  const [open, setOpen] = useState(highlighted);
  const title = referral.specialistType === 'UNASSIGNED'
    ? 'Referral to a specialist'
    : `Referral to ${specialistSummary(referral)}`;
  const sentence = referralStatusSentence(referral);

  return (
    <Card style={highlighted ? { borderColor: accent, borderWidth: 2 } : undefined}>
      <View style={styles.card}>
        <View style={styles.head}>
          <View style={styles.titles}>
            <Text style={styles.title} accessibilityRole="header" {...ariaLevel(headingLevel)}>{title}</Text>
            <Text style={styles.meta}>
              {`Issued ${referralDateLabel(referral)} by ${referral.referringDoctorName}`}
            </Text>
          </View>
          <ReferralBadges referral={referral} />
        </View>

        {sentence ? <Text style={styles.sentence}>{sentence}</Text> : null}

        <View style={styles.block}>
          <Text style={styles.label}>Reason</Text>
          <Text style={styles.reason} numberOfLines={open ? undefined : 3}>{referral.reason}</Text>
        </View>

        <View style={styles.block}>
          <Text style={styles.label}>Progress</Text>
          <ReferralTimeline referral={referral} />
        </View>

        {open ? (
          <View style={styles.details}>
            <ReferralFacts referral={referral} audience="reader" />
          </View>
        ) : null}

        <Button
          label={open ? 'Hide details' : 'Show details'}
          icon={open ? 'chevron-up' : 'chevron-down'}
          variant="ghost"
          size="sm"
          onPress={() => setOpen(v => !v)}
          accessibilityLabel={`${open ? 'Hide details' : 'Show details'} of the ${title.toLowerCase()} issued ${referralDateLabel(referral)}`}
        />
      </View>
    </Card>
  );
}

export interface PatientReferralListProps {
  /** Whose referrals: the signed-in patient's own id, or a loved one's for a caregiver. */
  patientSystemId: string;
  /** `patient` reads their own referrals; `caregiver` reads a loved one's. Only the wording differs. */
  audience: 'patient' | 'caregiver';
  /** The loved one's name, for the caregiver wording. */
  patientName?: string | null;
  /** A referral to bring forward (the `id` of a notification link). */
  highlightId?: number | null;
  /** Heading level of each referral's title. Default 2 (directly under the screen title). */
  headingLevel?: 2 | 3;
}

export function PatientReferralList({
  patientSystemId, audience, patientName, highlightId = null, headingLevel = 2,
}: PatientReferralListProps) {
  const list = usePatientReferrals(patientSystemId);
  const who = patientName?.trim() || 'your loved one';

  // A linked referral that is not on the pages loaded so far is fetched on its own and shown above the list.
  const inList = highlightId != null && list.items.some(r => r.referralId === highlightId);
  const linked = useReferral(highlightId, { enabled: highlightId != null && !list.loading && !list.error && !inList });
  const linkedReferral = !inList && linked.data && linked.data.patientSystemId === patientSystemId ? linked.data : null;
  const linkedGone = highlightId != null && !inList && !linked.loading && (
    linked.errorStatus === 403 || linked.errorStatus === 404
    || (linked.data != null && linked.data.patientSystemId !== patientSystemId)
  );
  const linkedFailed = !linkedGone && !linkedReferral && !!linked.error;

  const header = highlightId != null && !inList && !list.loading && !list.error ? (
    <View style={styles.linked}>
      {linked.loading ? <LoadingView compact label="Loading the referral you opened…" /> : null}
      {linkedGone ? <Banner tone="info" text={UNAVAILABLE} /> : null}
      {linkedFailed ? (
        <Banner tone="danger" text={linked.error ?? 'Could not load this referral.'} action={{ label: 'Retry', onPress: linked.refetch }} />
      ) : null}
      {linkedReferral ? <PatientReferralCard referral={linkedReferral} highlighted headingLevel={headingLevel} /> : null}
    </View>
  ) : null;

  return (
    <PagedList<Referral>
      state={list}
      keyOf={r => r.referralId}
      label={audience === 'patient' ? 'Your referrals' : `Referrals of ${who}`}
      header={header}
      renderItem={referral => (
        <PatientReferralCard
          // A new key when the highlight changes, so the card a link points at opens.
          key={`${referral.referralId}-${referral.referralId === highlightId ? 'linked' : 'row'}`}
          referral={referral}
          highlighted={referral.referralId === highlightId}
          headingLevel={headingLevel}
        />
      )}
      empty={audience === 'patient'
        ? {
            icon: 'swap-horizontal-outline',
            title: 'No referrals yet',
            sub: 'When a doctor refers you to a specialist, the referral and its progress appear here.',
          }
        : {
            icon: 'swap-horizontal-outline',
            title: `No referrals for ${who}`,
            sub: 'When a doctor refers them to a specialist, the referral and its progress appear here.',
          }}
      noAccessMessage={audience === 'caregiver'
        ? `Your access for ${who} does not include their referrals.`
        : undefined}
    />
  );
}

const styles = StyleSheet.create({
  card:     { gap: space.md, minWidth: 0 },
  head:     { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.sm },
  titles:   { flexGrow: 1, flexShrink: 1, flexBasis: 220, minWidth: 0, gap: 2 },
  title:    { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  meta:     { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  sentence: { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  block:    { gap: space.xs, minWidth: 0 },
  label:    { fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted, textTransform: 'uppercase', letterSpacing: 0.4 },
  reason:   { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  details:  { borderTopWidth: 1, borderTopColor: colors.border, paddingTop: space.sm, minWidth: 0 },
  linked:   { gap: space.sm, minWidth: 0 },
});
