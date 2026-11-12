import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Card, colors, fontSize, Icon, radius, space, StatusBadge, statusLabel, statusTone, useUiTheme,
} from '@/components/web/ui';
import { tidyDoctorName } from '@/hooks/use-doctors';
import type { Prescription, PrescriptionRoute } from '@/hooks/use-prescriptions';
import { formatDateOnly } from '@/lib/ui/date-only';

const ROUTE_LABEL: Record<PrescriptionRoute, string> = {
  ORAL:       'By mouth (oral)',
  TOPICAL:    'On the skin (topical)',
  INJECTION:  'Injection',
  INHALED:    'Inhaled',
  SUBLINGUAL: 'Under the tongue (sublingual)',
  RECTAL:     'Rectal',
  IV:         'Into a vein (IV)',
};

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.detail}>
      <Text style={styles.detailLabel}>{label}</Text>
      <Text style={styles.detailValue}>{value}</Text>
    </View>
  );
}

export interface PrescriptionCardProps {
  item: Prescription;
  /** Draws the card in the accent colour, for the prescription a link pointed at. */
  highlighted?:  boolean;
  /** Heading level of the medication name. Default 3. */
  headingLevel?: 3 | 4;
}

/**
 * One prescription: medication, prescriber, how to take it and its dates. The status shown is the effective
 * one, so an active prescription past its expiry date reads "Expired".
 */
export function PrescriptionCard({ item, highlighted = false, headingLevel = 3 }: PrescriptionCardProps) {
  const { accent, accentBg } = useUiTheme();
  const status = item.effective_status;
  const doctor = tidyDoctorName(item.doctor_name);
  const prescriber = [doctor, item.doctor_specialization].filter(Boolean).join(', ');
  const route = ROUTE_LABEL[item.route] ?? statusLabel(item.route);

  return (
    <Card style={[styles.card, highlighted && { borderColor: accent, borderWidth: 2 }]}>
      <View style={styles.head}>
        <View style={styles.titles}>
          <Text style={styles.name} accessibilityRole="header" {...ariaLevel(headingLevel)}>
            {item.medication_name || 'Medication'}
          </Text>
          {item.generic_name ? <Text style={styles.generic}>{item.generic_name}</Text> : null}
          {prescriber ? <Text style={styles.prescriber}>Prescribed by {prescriber}</Text> : null}
        </View>
        <StatusBadge label={statusLabel(status)} tone={statusTone('prescription', status)} />
      </View>

      <View style={styles.details}>
        <Detail label="Dosage" value={item.dosage || 'Not provided'} />
        <Detail label="How often" value={item.frequency || 'Not provided'} />
        <Detail label="How to take" value={route} />
        {item.quantity ? <Detail label="Quantity" value={item.quantity} /> : null}
        <Detail label="Refills" value={String(item.refills_allowed)} />
        <Detail label="Prescribed" value={formatDateOnly(item.prescribed_date)} />
        {item.expiry_date ? (
          <Detail label={status === 'EXPIRED' ? 'Expired after' : 'Valid through'} value={formatDateOnly(item.expiry_date)} />
        ) : null}
      </View>

      {item.special_instructions ? (
        <View style={[styles.instructions, { backgroundColor: accentBg }]}>
          <Icon name="information-circle-outline" size={16} color={accent} style={styles.instructionsIcon} />
          <View style={styles.instructionsBody}>
            <Text style={styles.detailLabel}>Instructions</Text>
            <Text style={styles.instructionsText}>{item.special_instructions}</Text>
          </View>
        </View>
      ) : null}
    </Card>
  );
}

const styles = StyleSheet.create({
  card:       { gap: space.md },
  head:       { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.sm },
  titles:     { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, gap: 2 },
  name:       { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  generic:    { fontSize: fontSize.sm, color: colors.textMuted, fontStyle: 'italic' },
  prescriber: { fontSize: fontSize.sm, color: colors.textMuted },

  details:     { flexDirection: 'row', flexWrap: 'wrap', columnGap: space.xl, rowGap: space.md },
  detail:      { minWidth: 120, maxWidth: '100%', gap: 2 },
  detailLabel: { fontSize: fontSize.xs, fontWeight: '600', color: colors.textMuted },
  detailValue: { fontSize: fontSize.md, color: colors.text },

  instructions:     { flexDirection: 'row', alignItems: 'flex-start', gap: space.sm, padding: space.md, borderRadius: radius.md },
  instructionsIcon: { marginTop: 2 },
  instructionsBody: { flex: 1, minWidth: 0, gap: 2 },
  instructionsText: { fontSize: fontSize.md, color: colors.text, lineHeight: 20 },
});
