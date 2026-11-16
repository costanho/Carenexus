import { StyleSheet, Text, View } from 'react-native';
import { Icon, type IoniconsName } from './icon';
import { fontSize, radius, space, STATUS_TONES, type StatusToneName } from './tokens';

export type StatusTone = StatusToneName;

/**
 * Areas whose status values have their own meaning. Any other string falls back to the general table, so a new
 * domain still gets sensible tones without a change here.
 */
export type StatusDomain =
  | 'appointment' | 'consultation' | 'call' | 'prescription' | 'medication' | 'lab' | 'imaging' | 'referral'
  | 'carePlan' | 'goal' | 'alert' | 'health' | 'document' | 'invoice' | 'payment' | 'claim'
  | 'access' | 'accessRequest' | 'account' | 'verification' | 'correction' | 'delivery' | 'job' | 'priority' | 'severity'
  | (string & {});

/** Meaning of a status wherever it appears, unless a domain overrides it below. */
const GENERAL: Record<string, StatusTone> = {
  // finished well
  COMPLETED: 'success', ACTIVE: 'success', APPROVED: 'success', ACCEPTED: 'success', PAID: 'success',
  SUCCEEDED: 'success', VERIFIED: 'success', CONFIRMED: 'success', DELIVERED: 'success', RESOLVED: 'success',
  ADMITTED: 'success', STABLE: 'success', NORMAL: 'success', ACHIEVED: 'success', READY: 'success', LIVE: 'success',
  // in motion
  SCHEDULED: 'info', RESCHEDULED: 'info', IN_PROGRESS: 'info', SENT: 'info', SUBMITTED: 'info', RUNNING: 'info',
  REQUESTED: 'info', OPEN: 'info', PREPARING: 'info', PROCESSING: 'info', APPEALED: 'info', ACKNOWLEDGED: 'info',
  // waiting on someone, or needs attention soon
  PENDING: 'warning', PENDING_VERIFICATION: 'warning', REQUIRES_REVIEW: 'warning', ON_HOLD: 'warning',
  PARTIAL: 'warning', MONITOR: 'warning', NEEDS_TIME_CONFIRMATION: 'warning', EXPIRING: 'warning',
  MEDIUM: 'warning', MODERATE: 'warning', HIGH: 'warning', URGENT: 'warning', ABNORMAL: 'warning',
  // went wrong
  FAILED: 'danger', REJECTED: 'danger', DECLINED: 'danger', DENIED: 'danger', OVERDUE: 'danger',
  NO_SHOW: 'danger', SEVERE: 'danger', REVOKED: 'danger', SUSPENDED: 'danger', LOCKED: 'danger',
  // highest clinical urgency
  CRITICAL: 'critical', EMERGENCY: 'critical',
  // over, without a judgement
  CANCELLED: 'neutral', EXPIRED: 'neutral', DRAFT: 'neutral', ENDED: 'neutral', DISCONTINUED: 'neutral',
  REFUNDED: 'neutral', INACTIVE: 'neutral', ARCHIVED: 'neutral', CLOSED: 'neutral', LEFT: 'neutral',
  REMOVED: 'neutral', WITHDRAWN: 'neutral', LOW: 'neutral', MILD: 'neutral', ROUTINE: 'neutral', DISMISSED: 'neutral',
};

/** Statuses that mean something different in one domain. */
const BY_DOMAIN: Record<string, Record<string, StatusTone>> = {
  // A finished call or visit is a good outcome, not a neutral one.
  call:         { ENDED: 'success', FAILED: 'danger' },
  consultation: { DRAFT: 'warning' },
  // An unpaid invoice that was sent is waiting on the patient.
  invoice:      { SENT: 'warning', DRAFT: 'neutral' },
  payment:      { PENDING: 'info' },
  claim:        { SUBMITTED: 'info', PENDING: 'warning', PARTIAL: 'warning' },
  // An open alert is waiting for someone to look at it.
  alert:        { OPEN: 'warning', ACTIVE: 'warning', NEW: 'warning' },
  access:       { EXPIRED: 'warning', PENDING: 'warning' },
  account:      { PENDING: 'warning', CLOSED: 'neutral', REJECTED: 'danger' },
  priority:     { HIGH: 'danger', URGENT: 'danger', MEDIUM: 'warning', NORMAL: 'neutral', LOW: 'neutral' },
  severity:     { HIGH: 'danger', SEVERE: 'danger', MODERATE: 'warning', MEDIUM: 'warning', MILD: 'neutral', LOW: 'neutral' },
  health:       { STABLE: 'success', MONITOR: 'warning', CRITICAL: 'critical' },
  delivery:     { PENDING: 'info', SENT: 'success', FAILED: 'danger' },
};

function normalise(status: string | null | undefined): string {
  return (status ?? '').trim().toUpperCase().replace(/[\s-]+/g, '_');
}

/** The tone for a status value of a domain: `statusTone('appointment', 'NO_SHOW')` is `'danger'`. Unknown values are neutral. */
export function statusTone(domain: StatusDomain, status: string | null | undefined): StatusTone {
  const key = normalise(status);
  if (!key) return 'neutral';
  return BY_DOMAIN[domain]?.[key] ?? GENERAL[key] ?? 'neutral';
}

/** "NO_SHOW" becomes "No show", "IN_PROGRESS" becomes "In progress". An empty value becomes "Unknown". */
export function statusLabel(status: string | null | undefined): string {
  const words = normalise(status).split('_').filter(Boolean).join(' ').toLowerCase();
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : 'Unknown';
}

/** Default icon per tone, so a badge differs by shape as well as colour. */
const TONE_ICON: Record<StatusTone, IoniconsName> = {
  neutral:  'ellipse-outline',
  info:     'time-outline',
  success:  'checkmark-circle-outline',
  warning:  'alert-circle-outline',
  danger:   'close-circle-outline',
  critical: 'warning',
};

export interface StatusBadgeProps {
  /** The status in words. Always shown: colour alone never carries the meaning. */
  label: string;
  tone:  StatusTone;
  /** Decorative icon. Each tone has a default; pass `null` for a text-only badge. */
  icon?: IoniconsName | null;
}

export function StatusBadge({ label, tone, icon }: StatusBadgeProps) {
  const c = STATUS_TONES[tone];
  const glyph = icon === null ? null : icon ?? TONE_ICON[tone];
  return (
    <View style={[styles.badge, { backgroundColor: c.bg, borderColor: c.border }]}>
      {glyph ? <Icon name={glyph} size={12} color={c.fg} /> : null}
      <Text style={[styles.text, { color: c.fg }]}>{label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  badge: {
    flexDirection: 'row', alignItems: 'center', gap: space.xs, alignSelf: 'flex-start', maxWidth: '100%',
    paddingHorizontal: space.sm, paddingVertical: 3, borderWidth: 1, borderRadius: radius.pill,
  },
  text: { fontSize: fontSize.xs, fontWeight: '700', flexShrink: 1 },
});
