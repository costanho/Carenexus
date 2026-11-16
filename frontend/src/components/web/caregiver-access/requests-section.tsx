import { StyleSheet, Text, View } from 'react-native';
import { ariaLevel, Card, colors, EmptyView, fontSize, PagedList, type PagedState, space } from '@/components/web/ui';
import type { CaregiverAccess, CaregiverAccessRequest } from '@/lib/caregiver-access/types';
import { AccessRequestCard } from './access-request-card';

export interface RequestsSectionProps {
  /** The pending requests (`useAccessRequests()`). */
  requests:    PagedState<CaregiverAccessRequest>;
  /** The patient's current grants, to spot a request from a caregiver who already has access. */
  grants:      CaregiverAccess[];
  online:      boolean;
  /** `requestId` a notification link pointed at, when there is one. */
  highlightId?: number | null;
  onApproved:  (request: CaregiverAccessRequest) => void;
  onDeclined:  (request: CaregiverAccessRequest) => void;
  onStale:     (message: string) => void;
  onEditGrant: (accessId: number) => void;
}

/**
 * The Requests section of My Proxies: caregivers who asked for access and wait for the patient's answer.
 * Shown whether or not the page was opened from a notification; loading, failed (with Retry) and empty states
 * come from `PagedList`.
 */
export function RequestsSection({
  requests, grants, online, highlightId, onApproved, onDeclined, onStale, onEditGrant,
}: RequestsSectionProps) {
  const nothingWaiting = !requests.loading && !requests.error && requests.items.length === 0;
  return (
    <View style={styles.section}>
      <View style={styles.titles}>
        <Text style={styles.title} accessibilityRole="header" {...ariaLevel(2)}>
          {requests.total > 0 ? `Access requests (${requests.total})` : 'Access requests'}
        </Text>
        <Text style={styles.sub}>
          Caregivers who asked to see your health information. Nothing is shared until you approve, and you choose
          exactly what each person gets.
        </Text>
      </View>
      {nothingWaiting ? (
        <Card>
          <EmptyView
            compact
            icon="mail-open-outline"
            title="No requests waiting"
            sub="When a caregiver asks for access to your information, their request appears here."
          />
        </Card>
      ) : (
        <PagedList
          state={requests}
          keyOf={request => request.requestId}
          label="Access requests waiting for your answer"
          loadMoreLabel="Load more requests"
          empty={{ icon: 'mail-open-outline', title: 'No requests waiting' }}
          renderItem={request => (
            <AccessRequestCard
              request={request}
              existingGrant={grants.find(g => g.caregiverSystemId === request.caregiverSystemId) ?? null}
              online={online}
              highlighted={highlightId != null && request.requestId === highlightId}
              onApproved={onApproved}
              onDeclined={onDeclined}
              onStale={onStale}
              onEditGrant={onEditGrant}
            />
          )}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  section: { gap: space.md, minWidth: 0 },
  titles:  { gap: space.xs },
  title:   { fontSize: fontSize.xl, fontWeight: '700', color: colors.text },
  sub:     { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
