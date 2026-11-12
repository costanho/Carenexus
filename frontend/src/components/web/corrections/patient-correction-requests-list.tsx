import { useCallback, useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { Banner, colors, fontSize, LoadingView, PagedList, space } from '@/components/web/ui';
import { useCorrectionRequest, usePatientCorrectionRequests } from '@/hooks/use-correction-requests';
import { useSectionNav } from '@/hooks/use-section-nav';
import { subscribeCorrectionsChanged } from '@/lib/corrections/events';
import { correctionRecordLink } from '@/lib/corrections/labels';
import type { CorrectionRequest } from '@/lib/corrections/types';
import { CorrectionRequestCard } from './correction-request-card';
import { type CorrectionStatusFilter, CorrectionStatusSelect, statusOfFilter, toRequestId } from './status-filter';

export interface PatientCorrectionRequestsListProps {
  /** Whose requests to list: the signed-in patient, or the dependent a caregiver is looking at. */
  patientSystemId: string;
  /** The request a notification link points at (`id` URL param). It is shown first and marked. */
  highlightId?:    number | string | null;
  /**
   * Opens the record a request is about. By default the list navigates inside the viewer's dashboard (the
   * section that shows that kind of record, with `id` and, for a caregiver, `patient`). Pass null for no link.
   */
  onOpenRecord?:   ((request: CorrectionRequest) => void) | null;
  /** Shown instead of the generic sentence when the server refuses the list (a caregiver without records access). */
  noAccessMessage?: string;
}

/**
 * The correction requests about one person with their status and, once resolved, the doctor's note. Serves the
 * patient's own Corrections tab and, with a dependent's id, the caregiver's.
 */
export function PatientCorrectionRequestsList({
  patientSystemId, highlightId, onOpenRecord, noAccessMessage,
}: PatientCorrectionRequestsListProps) {
  const nav = useSectionNav();
  const [filter, setFilter] = useState<CorrectionStatusFilter>('ALL');
  const requests = usePatientCorrectionRequests(patientSystemId, { status: statusOfFilter(filter) });

  const linkedId = toRequestId(highlightId);
  const onPage = linkedId !== null && requests.items.some(r => r.requestId === linkedId);
  // The linked request is fetched on its own only when the loaded rows do not contain it.
  const linked = useCorrectionRequest(linkedId, !requests.loading && !onPage && requests.errorStatus !== 403);
  const refetchLinked = linked.refetch;
  useEffect(() => subscribeCorrectionsChanged(() => refetchLinked()), [refetchLinked]);

  const openRecord = useCallback((request: CorrectionRequest) => {
    const link = correctionRecordLink(nav.role, request);
    if (link) nav.navigate(link.section, link.params);
  }, [nav]);
  const canOpen = (request: CorrectionRequest): boolean => {
    const link = correctionRecordLink(nav.role, request);
    return !!link && nav.hasSection(link.section);
  };
  const opener = (request: CorrectionRequest) => {
    if (onOpenRecord === null) return undefined;
    if (onOpenRecord) return onOpenRecord;
    return canOpen(request) ? openRecord : undefined;
  };

  const linkedRequest = linked.data && linked.data.patientSystemId === patientSystemId ? linked.data : null;

  const header = (
    <View style={styles.header}>
      <CorrectionStatusSelect value={filter} onChange={setFilter} />
      {linkedId !== null && !onPage ? (
        linked.loading ? <LoadingView compact label="Loading the linked request" />
          : linkedRequest ? (
            <View style={styles.linked}>
              <Text style={styles.linkedLabel}>Request from your notification</Text>
              <CorrectionRequestCard request={linkedRequest} highlighted onOpenRecord={opener(linkedRequest)} />
            </View>
          ) : linked.error || linked.data ? (
            <Banner tone="warning" text="The correction request from your notification is not available." />
          ) : null
      ) : null}
    </View>
  );

  return (
    <PagedList
      state={requests}
      label="Correction requests"
      keyOf={r => r.requestId}
      header={header}
      noAccessMessage={noAccessMessage}
      empty={{
        icon:  'create-outline',
        title: filter === 'ALL' ? 'No correction requests' : 'No requests with this status',
        sub:   filter === 'ALL'
          ? 'When a correction to a record is requested, the request and the doctor\'s answer appear here.'
          : 'Choose "All requests" to see every request.',
      }}
      renderItem={request => (
        <CorrectionRequestCard
          request={request}
          highlighted={request.requestId === linkedId}
          onOpenRecord={opener(request)}
        />
      )}
    />
  );
}

const styles = StyleSheet.create({
  header:      { gap: space.md, minWidth: 0 },
  linked:      { gap: space.xs, minWidth: 0 },
  linkedLabel: { fontSize: fontSize.sm, fontWeight: '600', color: colors.textMuted },
});
