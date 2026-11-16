import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaHidden, ariaLevel, Button, Card, colors, fontSize, radius, space, StatusBadge, useUiTheme,
} from '@/components/web/ui';
import type { DoctorConsultation } from '@/hooks/use-doctor-consultations';
import { DisclosureButton } from '../disclosure-button';
import {
  appointmentTypeBadge, clinicDateTime, consultationDateLabel, consultationStatusBadge, followUpDateText,
} from './consultation-format';

function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).map(word => word[0]).join('').slice(0, 2).toUpperCase();
}

function Detail({ label, text }: { label: string; text: string }) {
  return (
    <View style={styles.detail}>
      <Text style={styles.detailLabel}>{label}</Text>
      <Text style={styles.detailText}>{text}</Text>
    </View>
  );
}

export interface ConsultationCardProps {
  consultation: DoctorConsultation;
  /** Opens the consultation: the editor for a draft or in-progress one, the read-only view otherwise. */
  onOpen: (consultationId: number) => void;
}

/** One consultation in the doctor's list: who, when, status, a preview of the notes and the way in. */
export function ConsultationCard({ consultation: c, onOpen }: ConsultationCardProps) {
  const { accent, accentText } = useUiTheme();
  const [expanded, setExpanded] = useState(false);
  const status = consultationStatusBadge(c.status);
  const type = appointmentTypeBadge(c.appointment_type);
  const editable = c.status === 'DRAFT' || c.status === 'IN_PROGRESS';

  const details: { label: string; text: string }[] = [];
  if (c.history_of_present_illness) details.push({ label: 'History of present illness', text: c.history_of_present_illness });
  if (c.physical_examination) details.push({ label: 'Physical examination', text: c.physical_examination });
  if (c.treatment_plan) details.push({ label: 'Treatment plan', text: c.treatment_plan });
  if (c.follow_up_instructions) details.push({ label: 'Follow-up instructions', text: c.follow_up_instructions });
  if (c.clinical_notes) details.push({ label: 'Clinical notes', text: c.clinical_notes });
  if (c.started_at) details.push({ label: 'Started', text: clinicDateTime(c.started_at) });
  if (c.completed_at) details.push({ label: 'Completed', text: clinicDateTime(c.completed_at) });

  const when = consultationDateLabel({ completedAt: c.completed_at, startedAt: c.started_at, createdAt: c.created_at });

  return (
    <Card style={styles.card}>
      <View style={styles.header}>
        <View style={[styles.avatar, { backgroundColor: accent }]} {...ariaHidden}>
          <Text style={[styles.avatarText, { color: accentText }]}>{initials(c.patient_name)}</Text>
        </View>
        <View style={styles.who}>
          {/* Each card is a level-2 section under the page heading, named by its patient. */}
          <Text style={styles.name} accessibilityRole="header" {...ariaLevel(2)}>{c.patient_name}</Text>
          <Text style={styles.meta}>
            {when}{c.duration_minutes ? ` · ${c.duration_minutes} min` : ''}
          </Text>
        </View>
        <View style={styles.badges}>
          <StatusBadge label={status.label} tone={status.tone} icon={status.icon} />
          {type ? <StatusBadge label={type.label} tone="neutral" icon={type.icon} /> : null}
          {c.follow_up_required ? (
            <StatusBadge
              label={c.follow_up_date ? `Follow-up ${followUpDateText(c.follow_up_date)}` : 'Follow-up'}
              tone="info"
              icon="arrow-forward-circle-outline"
            />
          ) : null}
        </View>
      </View>

      {c.chief_complaint ? (
        <View style={styles.preview}>
          <Text style={styles.detailLabel}>Chief complaint</Text>
          <Text style={styles.detailText} numberOfLines={expanded ? undefined : 2}>{c.chief_complaint}</Text>
        </View>
      ) : null}
      {c.diagnosis ? (
        <View style={styles.preview}>
          <Text style={styles.detailLabel}>Diagnosis</Text>
          <Text style={styles.detailText} numberOfLines={expanded ? undefined : 2}>{c.diagnosis}</Text>
        </View>
      ) : null}
      {!c.chief_complaint && !c.diagnosis ? (
        <Text style={styles.meta}>No chief complaint or diagnosis recorded yet.</Text>
      ) : null}

      {expanded ? details.map(d => <Detail key={d.label} label={d.label} text={d.text} />) : null}

      <View style={styles.actions}>
        {details.length > 0 ? (
          <DisclosureButton
            label={expanded ? 'Hide details' : 'Show details'}
            expanded={expanded}
            onPress={() => setExpanded(open => !open)}
            accessibilityLabel={`${expanded ? 'Hide details' : 'Show details'} of the consultation with ${c.patient_name}`}
          />
        ) : null}
        <Button
          label={editable ? 'Open' : 'View'}
          icon={editable ? 'create-outline' : 'eye-outline'}
          variant={editable ? 'primary' : 'secondary'}
          size="sm"
          onPress={() => onOpen(c.consultation_id)}
          accessibilityLabel={`${editable ? 'Open' : 'View'} ${status.label.toLowerCase()} consultation with ${c.patient_name}`}
          style={styles.open}
        />
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  card:        { gap: space.md },
  header:      { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.md },
  avatar:      { width: 40, height: 40, borderRadius: radius.pill, alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  avatarText:  { fontSize: fontSize.md, fontWeight: '700' },
  who:         { flexGrow: 1, flexShrink: 1, flexBasis: 140, minWidth: 0, gap: 2 },
  name:        { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  meta:        { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  badges:      { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs, maxWidth: '100%' },
  preview:     { gap: space.xs, backgroundColor: colors.bg, borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, padding: space.md, minWidth: 0 },
  detail:      { gap: space.xs, minWidth: 0 },
  detailLabel: { fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted },
  detailText:  { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
  actions:     { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
  open:        { marginLeft: 'auto' },
});
