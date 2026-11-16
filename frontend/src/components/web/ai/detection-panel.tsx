import { useState } from 'react';
import { EmptyView, ErrorView, LoadingView, NoAccessView, SidePanel } from '@/components/web/ui';
import { useDetection } from '@/hooks/use-disease-detections';
import { DETECTION_DISCLAIMER } from '@/lib/ai/labels';
import type { DiseaseDetection } from '@/lib/ai/types';
import { DetectionDetail } from './detection-detail';
import { ScreeningNotice } from './screening-notice';

export interface DetectionPanelProps {
  /** The detection to show; the panel is closed while it is null. */
  detectionId: number | null;
  onClose:     () => void;
  /**
   * Called after this doctor confirmed or rejected the detection, with the updated detection, so the list
   * behind the panel can refresh its row and acknowledge the alert.
   */
  onReviewed?: (detection: DiseaseDetection) => void;
  /** Called when the detection turned out to be reviewed by someone else, so the list can refresh. */
  onStale?:    (detection: DiseaseDetection) => void;
}

/**
 * The detection detail panel of the doctor dashboard: loads one detection and shows its findings, confidence
 * and recommendations, with Confirm and Reject when the signed-in doctor may review it.
 */
export function DetectionPanel({ detectionId, onClose, onReviewed, onStale }: DetectionPanelProps) {
  const open = detectionId !== null;
  const detection = useDetection(detectionId, { enabled: open });
  const [busy, setBusy] = useState(false);
  const data = detection.data;

  let body;
  if (detection.loading) {
    body = <LoadingView label="Loading screening result" />;
  } else if (!data && detection.errorStatus === 403) {
    body = <NoAccessView message={detection.error ?? undefined} />;
  } else if (!data && detection.errorStatus === 404) {
    body = (
      <EmptyView
        icon="document-text-outline"
        title="Screening result not found"
        sub="It may have been removed, or the link is out of date."
      />
    );
  } else if (!data) {
    body = <ErrorView message={detection.error ?? 'The screening result could not be loaded.'} onRetry={detection.refetch} />;
  } else {
    body = (
      <>
        <ScreeningNotice text={data.disclaimer || DETECTION_DISCLAIMER} />
        <DetectionDetail
          detection={data}
          viewer="clinician"
          onPendingChange={setBusy}
          onChanged={(next, reviewedNow) => {
            detection.setData(next);
            if (reviewedNow) onReviewed?.(next);
            else onStale?.(next);
          }}
        />
      </>
    );
  }

  return (
    <SidePanel
      open={open}
      title="Screening result"
      onClose={onClose}
      size="lg"
      // The panel holds the review form while the detection can still be reviewed.
      dismissOnBackdrop={!data?.canReview}
      busy={busy}
    >
      {body}
    </SidePanel>
  );
}
