import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Button, Card, colors, fontSize, Icon, radius, space, StatusBadge,
} from '@/components/web/ui';
import {
  type DoctorPrescription, getPrescriptionSchedule, isPrescriptionOpen,
} from '@/hooks/use-doctor-prescriptions';
import { useResource } from '@/hooks/use-resource';
import { CLINIC_ZONE_LABEL } from '@/lib/time/clinic';
import { DisclosureButton } from '../disclosure-button';
import {
  daysFromClinicToday, EXPIRING_SOON_DAYS, formatDay, refillsText, routeLabel, STATUS_ACTIONS, type StatusAction,
  statusBadge,
} from './labels';

export interface PrescriptionCardProps {
  rx: DoctorPrescription;
  /** Changes when a schedule was edited, so an open card reloads its dose times. */
  scheduleVersion: number;
  onEdit:          (rx: DoctorPrescription) => void;
  onEditSchedule:  (rx: DoctorPrescription) => void;
  onStatusAction:  (rx: DoctorPrescription, action: StatusAction) => void;
  /** Hides the edit and status buttons (a prescription another doctor wrote, opened from a link). */
  readOnly?:       boolean;
}

/** One label and value of the detail grid. The cell and its text shrink and wrap, so a long value never widens the card. */
function Cell({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.cell}>
      <Text style={styles.cellLabel}>{label}</Text>
      <Text style={styles.cellValue}>{value}</Text>
    </View>
  );
}

/** The daily dose times, loaded when the details are opened. */
function DoseTimes({ prescriptionId, version }: { prescriptionId: number; version: number }) {
  const plan = useResource(() => getPrescriptionSchedule(prescriptionId), [prescriptionId, version], {
    errorMessage: 'The dose times could not be loaded.',
  });
  return (
    <View style={styles.detailBlock}>
      <Text style={styles.detailLabel}>Dose times</Text>
      {plan.loading ? (
        <Text style={styles.detailMuted} role="status">Loading dose times…</Text>
      ) : plan.data ? (
        <Text style={styles.detailText}>
          {plan.data.times.length > 0
            ? `${plan.data.times.join(', ')} (${CLINIC_ZONE_LABEL})`
            : 'No dose times are set, so no doses are scheduled.'}
        </Text>
      ) : (
        <View style={styles.inlineError} accessibilityRole="alert">
          <Text style={styles.detailMuted}>{plan.error ?? 'The dose times could not be loaded.'}</Text>
          <Button label="Retry" accessibilityLabel="Retry loading dose times" variant="ghost" size="sm" onPress={plan.refetch} />
        </View>
      )}
    </View>
  );
}

export function PrescriptionCard({ rx, scheduleVersion, onEdit, onEditSchedule, onStatusAction, readOnly = false }: PrescriptionCardProps) {
  const [expanded, setExpanded] = useState(false);
  const badge = statusBadge(rx.effective_status);
  const open = isPrescriptionOpen(rx.effective_status);
  const daysLeft = daysFromClinicToday(rx.expiry_date);
  const expiringSoon = rx.effective_status === 'ACTIVE' && daysLeft !== null && daysLeft >= 0 && daysLeft <= EXPIRING_SOON_DAYS;
  // What the buttons act on, for assistive technology: "Edit, Amlodipine for Thomas Davis".
  const subject = `${rx.medication_name} for ${rx.patient_name}`;

  return (
    <Card style={styles.card}>
      <View style={styles.header}>
        <View style={styles.badges}>
          <StatusBadge label={badge.label} tone={badge.tone} icon={badge.icon} />
          {expiringSoon ? (
            <StatusBadge
              label={daysLeft === 0 ? 'Expires today' : `Expires in ${daysLeft} ${daysLeft === 1 ? 'day' : 'days'}`}
              tone="warning"
              icon="alarm-outline"
            />
          ) : null}
          <StatusBadge label={routeLabel(rx.route)} tone="neutral" icon={null} />
        </View>
        <Text style={styles.issued}>Issued {formatDay(rx.prescribed_date, 'on an unknown date')}</Text>
      </View>

      <View style={styles.medication}>
        <View style={styles.medicationIcon}>
          <Icon name="medkit-outline" size={20} color={colors.textMuted} />
        </View>
        <View style={styles.medicationText}>
          <Text style={styles.medicationName} accessibilityRole="header" {...ariaLevel(2)}>{rx.medication_name}</Text>
          {rx.generic_name ? <Text style={styles.genericName}>{rx.generic_name}</Text> : null}
        </View>
      </View>

      <View style={styles.grid}>
        <Cell label="Patient" value={rx.patient_name} />
        <Cell label="Dosage" value={rx.dosage} />
        <Cell label="Frequency" value={rx.frequency} />
        {rx.quantity ? <Cell label="Quantity" value={rx.quantity} /> : null}
        <Cell label="Refills" value={refillsText(rx.refills_allowed)} />
        <Cell label="Expires" value={formatDay(rx.expiry_date, 'No expiry date')} />
      </View>

      {expanded ? (
        <View style={styles.details}>
          <View style={styles.detailBlock}>
            <Text style={styles.detailLabel}>Instructions</Text>
            <Text style={rx.special_instructions ? styles.detailText : styles.detailMuted}>
              {rx.special_instructions || 'No instructions were given.'}
            </Text>
          </View>
          <DoseTimes prescriptionId={rx.prescription_id} version={scheduleVersion} />
          <View style={styles.detailBlock}>
            <Text style={styles.detailLabel}>Prescribed by</Text>
            <Text style={styles.detailText}>
              {rx.doctor_name}{rx.consultation_id ? ` · from consultation ${rx.consultation_id}` : ''}
            </Text>
          </View>
        </View>
      ) : null}

      <View style={styles.actions}>
        <DisclosureButton
          label={expanded ? 'Hide details' : 'Show details'}
          accessibilityLabel={`${expanded ? 'Hide details' : 'Show details'}, ${subject}`}
          expanded={expanded}
          onPress={() => setExpanded(e => !e)}
        />
        {open && !readOnly ? (
          <>
            <Button
              label="Edit" accessibilityLabel={`Edit, ${subject}`} icon="create-outline" variant="secondary" size="sm"
              onPress={() => onEdit(rx)}
            />
            <Button
              label="Edit schedule times" accessibilityLabel={`Edit schedule times, ${subject}`} icon="alarm-outline"
              variant="secondary" size="sm" onPress={() => onEditSchedule(rx)}
            />
            {rx.effective_status === 'ACTIVE' ? (
              <StatusButton action={STATUS_ACTIONS.hold} subject={subject} onPress={a => onStatusAction(rx, a)} />
            ) : (
              <StatusButton action={STATUS_ACTIONS.resume} subject={subject} onPress={a => onStatusAction(rx, a)} />
            )}
            <StatusButton action={STATUS_ACTIONS.complete} subject={subject} onPress={a => onStatusAction(rx, a)} />
            <StatusButton action={STATUS_ACTIONS.cancel} subject={subject} onPress={a => onStatusAction(rx, a)} />
          </>
        ) : null}
      </View>
    </Card>
  );
}

function StatusButton({ action, subject, onPress }: { action: StatusAction; subject: string; onPress: (action: StatusAction) => void }) {
  return (
    <Button
      label={action.label}
      accessibilityLabel={`${action.label}, ${subject}`}
      icon={action.icon}
      variant={action.destructive ? 'danger' : 'secondary'}
      size="sm"
      onPress={() => onPress(action)}
    />
  );
}

const styles = StyleSheet.create({
  card: { gap: space.md },

  header: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: space.sm },
  badges: { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs, minWidth: 0, maxWidth: '100%' },
  issued: { fontSize: fontSize.xs, color: colors.textMuted },

  medication:     { flexDirection: 'row', alignItems: 'center', gap: space.md, minWidth: 0 },
  medicationIcon: {
    width: 40, height: 40, flexShrink: 0, borderRadius: radius.md, backgroundColor: colors.bgSubtle,
    alignItems: 'center', justifyContent: 'center',
  },
  medicationText: { flex: 1, minWidth: 0, gap: 2 },
  medicationName: { fontSize: fontSize.lg, fontWeight: '700', color: colors.text, lineHeight: 22 },
  genericName:    { fontSize: fontSize.sm, color: colors.textMuted, fontStyle: 'italic' },

  // Cells take an equal share of a wide card and drop onto further lines on a narrow one. `minWidth: 0` and
  // `maxWidth: '100%'` let a cell shrink to the card, and its text wraps, so nothing overflows at 390 px.
  grid: {
    flexDirection: 'row', flexWrap: 'wrap', columnGap: space.lg, rowGap: space.md,
    padding: space.md, borderRadius: radius.md, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.bg,
  },
  cell:      { flexGrow: 1, flexShrink: 1, flexBasis: 130, minWidth: 0, maxWidth: '100%', gap: 2 },
  cellLabel: { fontSize: fontSize.xs, color: colors.textMuted },
  cellValue: { fontSize: fontSize.md, fontWeight: '500', color: colors.text, lineHeight: 20 },

  details:     { gap: space.md, paddingTop: space.md, borderTopWidth: 1, borderTopColor: colors.border },
  detailBlock: { gap: 2, minWidth: 0 },
  detailLabel: { fontSize: fontSize.xs, fontWeight: '600', color: colors.textMuted },
  detailText:  { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },
  detailMuted: { fontSize: fontSize.md, color: colors.textSubtle, lineHeight: 21 },
  inlineError: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },

  actions: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
});
