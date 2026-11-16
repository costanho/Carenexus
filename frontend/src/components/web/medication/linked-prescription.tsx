import { type ReactNode, useRef } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { Button, colors, ErrorView, fontSize, LoadingView, NoAccessView, space } from '@/components/web/ui';
import { fetchPrescription } from '@/hooks/use-prescriptions';
import { useResource } from '@/hooks/use-resource';
import { PrescriptionCard } from './prescription-card';
import { REVEAL_TARGET_PROPS, useReveal } from './use-reveal';

export interface LinkedPrescriptionProps {
  /** The prescription a link named (`id` in the URL). */
  prescriptionId: number;
  /** Name of the patient a prescription belongs to, by PAT-... id (a caregiver has several loved ones). */
  patientNameOf?: (patientSystemId: string) => string | null | undefined;
  /** Removes the prescription from view (clears `id` from the URL). */
  onClose: () => void;
}

/**
 * The prescription a notification or link points at, shown at the top of the screen so it is found whatever page
 * of the list it is on; it is scrolled into view and focused when it has loaded. It is read by id; the server decides whether the viewer may see it.
 */
export function LinkedPrescription({ prescriptionId, patientNameOf, onClose }: LinkedPrescriptionProps) {
  const { data, loading, error, errorStatus, refetch } = useResource(
    () => fetchPrescription(prescriptionId),
    [prescriptionId],
    { errorMessage: 'This prescription could not be loaded.' },
  );
  const patientName = data && patientNameOf ? patientNameOf(data.patient_system_id)?.trim() : null;
  // Opened from a notification: bring the prescription into view and move focus to it once it has loaded (or
  // failed), so it is not left below the fold.
  const regionRef = useRef<View>(null);
  useReveal(regionRef, loading ? null : prescriptionId, 'nearest');

  let body: ReactNode;
  if (loading) body = <LoadingView compact label="Loading the prescription" />;
  else if (errorStatus === 403) body = <NoAccessView compact message="You don't have access to this prescription." />;
  else if (errorStatus === 404) body = <ErrorView compact message="This prescription could not be found. It may have been removed." />;
  else if (error || !data) body = <ErrorView compact message={error ?? 'This prescription could not be loaded.'} onRetry={refetch} />;
  else body = <PrescriptionCard item={data} highlighted />;

  return (
    <View ref={regionRef} style={styles.wrap} role="region" aria-label="Prescription from your link" {...REVEAL_TARGET_PROPS}>
      <View style={styles.head}>
        <Text style={styles.title}>
          {patientName ? `The prescription you opened, for ${patientName}` : 'The prescription you opened'}
        </Text>
        <Button label="Close" accessibilityLabel="Close the opened prescription" variant="ghost" size="sm" icon="close" onPress={onClose} />
      </View>
      {body}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap:  { gap: space.sm, minWidth: 0 },
  head:  { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  title: { flexShrink: 1, minWidth: 0, fontSize: fontSize.sm, fontWeight: '700', color: colors.textMuted },
});
