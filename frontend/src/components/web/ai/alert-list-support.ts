import { useCallback } from 'react';
import type { AlertListState } from '@/hooks/use-ai-alerts';
import { useSectionNav } from '@/hooks/use-section-nav';
import type { AlertSourceLink } from '@/lib/ai/labels';
import type { DiseaseDetection } from '@/lib/ai/types';

/**
 * Source links of alert rows in the doctor dashboard. A link is offered only when the dashboard has the section
 * it points at, so a record type whose screen is not registered shows no link instead of a dead one.
 */
export function useAlertSourceNav(): {
  canOpenSource: (link: AlertSourceLink) => boolean;
  openSource:    (link: AlertSourceLink) => void;
} {
  const { role, hasSection, navigate } = useSectionNav();
  const canOpenSource = useCallback(
    (link: AlertSourceLink) => role === 'doctor' && hasSection(link.section),
    [role, hasSection],
  );
  const openSource = useCallback(
    (link: AlertSourceLink) => navigate(link.section, link.params),
    [navigate],
  );
  return { canOpenSource, openSource };
}

/**
 * After this doctor reviewed a detection: a review does not acknowledge the detection's alert on the server, so
 * the alert is acknowledged here when its row is loaded and offers it, and the list is loaded again because the
 * row's review status changed.
 */
export async function settleReviewedDetection(alerts: AlertListState, detection: DiseaseDetection): Promise<void> {
  const row = alerts.items.find(alert => alert.detectionId === detection.detectionId);
  if (row?.canAcknowledge) await alerts.acknowledge(row);
  alerts.refetch();
}
