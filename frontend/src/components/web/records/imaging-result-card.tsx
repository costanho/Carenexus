import { StyleSheet, View } from 'react-native';
import { type IoniconsName, space, StatusBadge, statusTone } from '@/components/web/ui';
import type { ImagingResultRecord, ImagingType } from '@/hooks/use-imaging-results';
import {
  doctorByline, ExpandToggle, formatRecordDay, formatRecordMoment, RecordBadges, RecordCard, type RecordCardActions,
  RecordCardFooter, RecordCardHeader, RecordMeta, RecordTextBlock, useExpanded,
} from './card-parts';
import { RESULT_STATUS_LABELS } from './lab-result-card';

export const IMAGING_TYPE_LABELS: Record<ImagingType, { label: string; icon: IoniconsName }> = {
  XRAY:        { label: 'X-ray',       icon: 'scan-outline' },
  MRI:         { label: 'MRI',         icon: 'radio-outline' },
  CT_SCAN:     { label: 'CT scan',     icon: 'layers-outline' },
  ULTRASOUND:  { label: 'Ultrasound',  icon: 'pulse-outline' },
  PET:         { label: 'PET scan',    icon: 'flash-outline' },
  MAMMOGRAPHY: { label: 'Mammography', icon: 'body-outline' },
  DEXA:        { label: 'DEXA scan',   icon: 'fitness-outline' },
  ECHO:        { label: 'Echo',        icon: 'heart-outline' },
};

/** An impression longer than this is cut to a preview until the card is opened. */
const LONG_IMPRESSION = 160;

/** "Chest — PA and lateral" is stored in one field: the part before the dash is the title, the rest a subtitle. */
function splitBodyPart(raw: string | null, fallback: string): { title: string; subtitle: string | null } {
  if (!raw) return { title: fallback, subtitle: null };
  const parts = raw.split(' — ');
  return { title: parts[0].trim() || fallback, subtitle: parts.length > 1 ? parts.slice(1).join(' — ').trim() : null };
}

export interface ImagingResultCardProps<T extends ImagingResultRecord = ImagingResultRecord> {
  item:             T;
  /** Extra controls for this result (attachment, correction request), drawn in the card footer. */
  actions?:         RecordCardActions<T>;
  /** Open the full report at once (a result opened from a link). */
  defaultExpanded?: boolean;
}

/** One imaging study: status, dates, the impression, and the findings and radiologist report on demand. */
export function ImagingResultCard<T extends ImagingResultRecord = ImagingResultRecord>({
  item, actions, defaultExpanded = false,
}: ImagingResultCardProps<T>) {
  const [expanded, toggle] = useExpanded(defaultExpanded);
  const type = IMAGING_TYPE_LABELS[item.imaging_type] ?? IMAGING_TYPE_LABELS.XRAY;
  const isCritical = item.status === 'CRITICAL';
  const { title, subtitle } = splitBodyPart(item.body_part, type.label);
  const longImpression = !!item.impression && item.impression.length > LONG_IMPRESSION;
  const hasDetail = !!(item.findings || item.radiologist_report) || longImpression;

  return (
    <RecordCard criticalText={isCritical ? 'Critical: requires immediate radiologist review' : null}>
      <RecordCardHeader
        icon={type.icon}
        title={title}
        subtitle={subtitle}
        byline={doctorByline(item.doctor_name, item.doctor_specialization)}
        aside={<StatusBadge label={RESULT_STATUS_LABELS[item.status] ?? item.status} tone={statusTone('imaging', item.status)} />}
      />

      <RecordBadges>
        <StatusBadge label={type.label} tone="neutral" icon={type.icon} />
      </RecordBadges>

      <RecordMeta
        items={[
          { icon: 'calendar-outline', label: 'Image date', value: formatRecordDay(item.image_date) },
          item.reviewed_at ? { icon: 'checkmark-circle-outline', label: 'Reviewed', value: formatRecordMoment(item.reviewed_at) } : null,
          item.reviewer_name ? { icon: 'person-circle-outline', label: 'Reviewed by', value: item.reviewer_name } : null,
        ]}
      />

      {item.impression ? (
        <RecordTextBlock
          label="Impression"
          icon="document-text-outline"
          text={item.impression}
          lines={expanded ? undefined : 3}
          tinted
        />
      ) : null}

      {item.review_notes ? (
        <RecordTextBlock label="Doctor's note" icon="chatbox-ellipses-outline" text={item.review_notes} tinted />
      ) : null}

      {expanded && (item.findings || item.radiologist_report) ? (
        <View style={styles.sections}>
          {item.findings ? <RecordTextBlock label="Findings" icon="search-outline" text={item.findings} /> : null}
          {item.radiologist_report ? (
            <RecordTextBlock label="Radiologist report" icon="document-outline" text={item.radiologist_report} />
          ) : null}
        </View>
      ) : null}

      <RecordCardFooter
        toggle={hasDetail ? (
          <ExpandToggle
            expanded={expanded}
            onToggle={toggle}
            showLabel="View full report"
            hideLabel="Hide full report"
            subject={`${type.label}, ${title}`}
          />
        ) : undefined}
      >
        {actions?.(item)}
      </RecordCardFooter>
    </RecordCard>
  );
}

const styles = StyleSheet.create({
  sections: { gap: space.md },
});
