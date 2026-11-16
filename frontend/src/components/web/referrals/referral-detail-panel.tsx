import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, colors, type DialogCloseReason, ErrorView, fontSize, LoadingView, SidePanel, space,
} from '@/components/web/ui';
import type { Referral } from '@/lib/referrals/types';
import { hasAnyAction, ReferralActionButtons, type ReferralDialogKind } from './referral-actions';
import { ReferralBadges } from './referral-badges';
import { ReferralFacts } from './referral-facts';
import { ReferralTimeline } from './referral-timeline';

export interface ReferralDetailPanelProps {
  open: boolean;
  /** The referral to show; null while it is still loading or when loading failed. */
  referral: Referral | null;
  loading:  boolean;
  /** Why the referral could not be loaded, or null. */
  error:    string | null;
  onRetry:  () => void;
  onClose:  (reason: DialogCloseReason) => void;
  onAction: (kind: ReferralDialogKind, referral: Referral) => void;
}

/** A doctor's full view of one referral: every recorded fact, the timeline, and the actions the server allows. */
export function ReferralDetailPanel({ open, referral, loading, error, onRetry, onClose, onAction }: ReferralDetailPanelProps) {
  return (
    <SidePanel
      open={open}
      title="Referral details"
      onClose={onClose}
      footer={referral && hasAnyAction(referral)
        ? <ReferralActionButtons referral={referral} onAction={onAction} size="md" />
        : undefined}
    >
      {referral ? (
        <>
          {/* A refresh that failed keeps the last loaded details on screen and says so. */}
          {error ? <Banner tone="danger" text={error} action={{ label: 'Retry', onPress: onRetry }} /> : null}
          <ReferralBadges referral={referral} showRoutine />
          <ReferralFacts referral={referral} audience="doctor" />
          <View style={styles.section}>
            <Text style={styles.heading} accessibilityRole="header" {...ariaLevel(3)}>Progress</Text>
            <ReferralTimeline referral={referral} />
          </View>
        </>
      ) : loading ? (
        <LoadingView label="Loading referral…" />
      ) : (
        <ErrorView message={error ?? 'Could not load this referral.'} onRetry={onRetry} />
      )}
    </SidePanel>
  );
}

const styles = StyleSheet.create({
  section: { gap: space.sm, paddingTop: space.md, borderTopWidth: 1, borderTopColor: colors.border, minWidth: 0 },
  heading: { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
});
