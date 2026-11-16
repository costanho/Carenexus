import { StyleSheet, Text, View } from 'react-native';
import {
  Button, colors, decorative, fontSize, type IoniconsName, LoadingView, radius, space, StatusBadge, statusLabel,
  statusTone, useUiTheme,
} from '@/components/web/ui';
import type { Consultation, ConsultationAppointmentType } from '@/hooks/use-consultations';
import { useMedicalRecordById } from '@/hooks/use-medical-records';
import {
  ExpandToggle, formatRecordDay, formatRecordMoment, RecordBadges, RecordCard, type RecordCardActions,
  RecordCardFooter, RecordCardHeader, RecordTextBlock, useExpanded,
} from './card-parts';
import { IcdCodeList, MEDICAL_RECORD_TYPE_LABELS } from './medical-record-card';

const VISIT_TYPE_LABELS: Record<ConsultationAppointmentType, { label: string; icon: IoniconsName }> = {
  IN_PERSON: { label: 'In person',  icon: 'business-outline' },
  VIDEO:     { label: 'Video call', icon: 'videocam-outline' },
  PHONE:     { label: 'Phone call', icon: 'call-outline' },
};

/** "JB" for "Dr. James Banda": the title is skipped. */
function initials(name: string): string {
  const parts = name.trim().replace(/^dr(\.\s*|\s+)/i, '').split(/\s+/).filter(Boolean);
  if (parts.length === 0) return 'D';
  if (parts.length === 1) return parts[0][0].toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/**
 * The medical record filed for a consultation. It is read separately from the consultation, so it can be missing
 * (deleted since) or not readable by this person; both are said in words.
 */
function LinkedMedicalRecord({ recordId, onOpen }: { recordId: number; onOpen?: (recordId: number) => void }) {
  const { data, error, errorStatus, refetch } = useMedicalRecordById(recordId);

  let body;
  if (data) {
    const type = MEDICAL_RECORD_TYPE_LABELS[data.record_type] ?? MEDICAL_RECORD_TYPE_LABELS.NOTE;
    const codes = data.icd_codes && data.icd_codes.length > 0 ? data.icd_codes : null;
    body = (
      <>
        <Text style={styles.linkedTitle}>{data.title || type.label}</Text>
        <RecordBadges>
          <StatusBadge label={type.label} tone="neutral" icon={type.icon} />
          {data.edited ? <StatusBadge label="Edited" tone="neutral" icon="pencil-outline" /> : null}
        </RecordBadges>
        <Text style={styles.linkedMeta}>Filed {formatRecordMoment(data.created_at)} by {data.doctor_name}</Text>
        {codes ? <IcdCodeList codes={codes} /> : null}
        {onOpen ? (
          <Button
            label="Open in Medical Records"
            icon="folder-open-outline"
            variant="secondary"
            size="sm"
            onPress={() => onOpen(recordId)}
          />
        ) : null}
      </>
    );
  } else if (error) {
    body = errorStatus === 404 ? (
      <Text style={styles.linkedMeta}>The medical record for this consultation is no longer available.</Text>
    ) : errorStatus === 403 ? (
      <Text style={styles.linkedMeta}>You don't have access to the medical record for this consultation.</Text>
    ) : (
      <View style={styles.linkedError} accessibilityRole="alert">
        <Text style={styles.linkedMeta}>{error}</Text>
        <Button label="Retry" icon="refresh-outline" variant="secondary" size="sm" onPress={refetch} />
      </View>
    );
  } else {
    // Also covers the moment before the account is known, when nothing has been requested yet.
    body = <LoadingView compact label="Loading the medical record…" />;
  }

  return (
    <View style={styles.linked}>
      <Text style={styles.linkedLabel}>Medical record</Text>
      {body}
    </View>
  );
}

export interface ConsultationCardProps {
  item:             Consultation;
  /** Extra controls for this consultation, drawn in the card footer. */
  actions?:         RecordCardActions<Consultation>;
  /** Open the full record at once (a consultation opened from a link). */
  defaultExpanded?: boolean;
  /** Opens the linked medical record in Medical Records; without it the record is summarised but not linked. */
  onOpenRecord?:    (recordId: number) => void;
}

/**
 * One finalised consultation: doctor, date, reason for the visit, follow-up, and the diagnosis, plan, notes and
 * linked medical record on demand.
 */
export function ConsultationCard({ item, actions, defaultExpanded = false, onOpenRecord }: ConsultationCardProps) {
  const [expanded, toggle] = useExpanded(defaultExpanded);
  const { accent, accentText } = useUiTheme();
  const visit = item.appointment_type ? VISIT_TYPE_LABELS[item.appointment_type] : null;
  const when = formatRecordMoment(item.completed_at ?? item.created_at);
  const hasDetail = !!(
    item.diagnosis || item.treatment_plan || item.follow_up_instructions || item.clinical_notes
    || item.history_of_present_illness || item.medical_record_id !== null
  );

  return (
    <RecordCard>
      <RecordCardHeader
        lead={(
          <View style={[styles.avatar, { backgroundColor: accent }]} {...decorative}>
            <Text style={[styles.avatarText, { color: accentText }]}>{initials(item.doctor_name)}</Text>
          </View>
        )}
        title={item.doctor_name}
        subtitle={item.doctor_specialization}
        aside={(
          <>
            <Text style={styles.date}>{when}</Text>
            {item.duration_minutes != null ? <Text style={styles.duration}>{item.duration_minutes} min</Text> : null}
          </>
        )}
      />

      <RecordBadges>
        <StatusBadge label={statusLabel(item.status)} tone={statusTone('consultation', item.status)} />
        {visit ? <StatusBadge label={visit.label} tone="neutral" icon={visit.icon} /> : null}
        {item.follow_up_required === 1 ? (
          <StatusBadge
            label={item.follow_up_date ? `Follow-up: ${formatRecordDay(item.follow_up_date)}` : 'Follow-up required'}
            tone="warning"
            icon="calendar-outline"
          />
        ) : null}
      </RecordBadges>

      {item.chief_complaint ? (
        <RecordTextBlock
          label="Reason for visit"
          icon="document-text-outline"
          text={item.chief_complaint}
          lines={expanded ? undefined : 2}
          tinted
        />
      ) : null}

      {expanded ? (
        <View style={styles.sections}>
          {item.diagnosis ? <RecordTextBlock label="Diagnosis" icon="medal-outline" text={item.diagnosis} /> : null}
          {item.treatment_plan ? <RecordTextBlock label="Treatment plan" icon="list-outline" text={item.treatment_plan} /> : null}
          {item.follow_up_instructions ? (
            <RecordTextBlock label="Follow-up instructions" icon="calendar-outline" text={item.follow_up_instructions} />
          ) : null}
          {item.clinical_notes ? <RecordTextBlock label="Clinical notes" icon="create-outline" text={item.clinical_notes} /> : null}
          {item.history_of_present_illness ? (
            <RecordTextBlock label="History of present illness" icon="time-outline" text={item.history_of_present_illness} />
          ) : null}
          {item.medical_record_id !== null ? (
            <LinkedMedicalRecord recordId={item.medical_record_id} onOpen={onOpenRecord} />
          ) : null}
        </View>
      ) : null}

      <RecordCardFooter
        toggle={hasDetail ? (
          <ExpandToggle
            expanded={expanded}
            onToggle={toggle}
            showLabel="Show full record"
            hideLabel="Hide full record"
            subject={`consultation with ${item.doctor_name}, ${when}`}
          />
        ) : undefined}
      >
        {actions?.(item)}
      </RecordCardFooter>
    </RecordCard>
  );
}

const styles = StyleSheet.create({
  avatar:     { width: 40, height: 40, borderRadius: radius.pill, alignItems: 'center', justifyContent: 'center' },
  avatarText: { fontSize: fontSize.md, fontWeight: '700' },
  date:       { fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  duration:   { fontSize: fontSize.xs, color: colors.textMuted },
  sections:   { gap: space.md },

  linked:      { gap: space.sm, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, padding: space.md, minWidth: 0 },
  linkedLabel: { fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted, textTransform: 'uppercase', letterSpacing: 0.5 },
  linkedTitle: { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  linkedMeta:  { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  linkedError: { gap: space.sm, alignItems: 'flex-start' },
});
