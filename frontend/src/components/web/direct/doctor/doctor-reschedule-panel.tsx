import { Ionicons } from '@expo/vector-icons';
import { useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { apiErrorMessage } from '@/hooks/use-appointments';
import { useCurrentUser } from '@/hooks/use-current-user';
import { rescheduleDoctorAppointment } from '@/hooks/use-doctor-appointment-actions';
import type { ScheduleEntry } from '@/hooks/use-doctor-schedule';
import { api } from '@/lib/auth/auth.interceptor';
import { CLINIC_TIME_ZONE } from '@/lib/calls/format';
import { CalendarPicker } from '../patient/patient-web-appointment';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const LGRAY  = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const BLUE   = '#4F46E5';
const RED    = '#B91C1C';

/** Same step as the patient booking time picker (:00, :15, :30, :45). */
const SLOT_STEP_MINUTES = 15;

// GET /api/appointments/booking-rules: the clinic rules the server enforces for patient bookings.
interface BookingRules {
  timeZone:       string;
  openHour:       number;
  closeHour:      number;
  allowWeekends:  boolean;
  maxAdvanceDays: number;
}

const DEFAULT_RULES: BookingRules = {
  timeZone: CLINIC_TIME_ZONE, openHour: 8, closeHour: 18, allowWeekends: true, maxAdvanceDays: 365,
};

interface BusySlot { start: string; end: string }

// ─── Clinic-time helpers (wall clock "YYYY-MM-DDTHH:mm" strings compare in time order) ───────────

function clinicNow(timeZone: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date());
  const get = (t: string) => parts.find(p => p.type === t)?.value ?? '00';
  return `${get('year')}-${get('month')}-${get('day')}T${get('hour')}:${get('minute')}`;
}

function addDays(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number);
  const x = new Date(Date.UTC(y, m - 1, d + days));
  return x.toISOString().slice(0, 10);
}

function addMinutes(local: string, minutes: number): string {
  const [date, time] = local.split('T');
  const [h, m] = time.split(':').map(Number);
  const total = h * 60 + m + minutes;
  const day = addDays(date, Math.floor(total / 1440));
  const rest = ((total % 1440) + 1440) % 1440;
  return `${day}T${String(Math.floor(rest / 60)).padStart(2, '0')}:${String(rest % 60).padStart(2, '0')}`;
}

function isWeekend(date: string): boolean {
  const [y, m, d] = date.split('-').map(Number);
  const dow = new Date(Date.UTC(y, m - 1, d)).getUTCDay();
  return dow === 0 || dow === 6;
}

/** "HH:mm" (24 h) as "h:mm AM/PM". */
export function formatClinicTime(time: string): string {
  const [h, m] = time.split(':').map(Number);
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return `${h12}:${String(m).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`;
}

export function formatClinicDate(date: string): string {
  const [y, m, d] = date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString('en-GB', {
    timeZone: 'UTC', weekday: 'short', day: 'numeric', month: 'short', year: 'numeric',
  });
}

/** Start times within clinic hours for which the appointment's duration still ends by closing time. */
function daySlots(rules: BookingRules, duration: number): string[] {
  const out: string[] = [];
  const closeMinutes = Math.min(rules.closeHour, 24) * 60;
  for (let t = rules.openHour * 60; t + duration <= closeMinutes; t += SLOT_STEP_MINUTES) {
    out.push(`${String(Math.floor(t / 60)).padStart(2, '0')}:${String(t % 60).padStart(2, '0')}`);
  }
  return out;
}

// ─── Component ────────────────────────────────────────────────────────────────

/**
 * Inline "choose a new date and time" panel for one of the doctor's upcoming appointments.
 * Dates come from the same calendar as patient booking; times are 15-minute slots inside the
 * clinic hours from /booking-rules, in clinic time (Africa/Harare). Slots that overlap another of
 * the doctor's active appointments (/doctor/{id}/busy) or have already passed are disabled. The
 * server re-checks everything (including the patient's own diary) and its message is shown inline.
 */
export function DoctorReschedulePanel({ entry, onDone, onClose }: {
  entry:   ScheduleEntry;
  onDone:  (date: string, time: string) => void;
  onClose: () => void;
}) {
  const { user } = useCurrentUser();
  const [rules,   setRules]   = useState<BookingRules>(DEFAULT_RULES);
  const [date,    setDate]    = useState('');
  const [time,    setTime]    = useState('');
  const [busy,    setBusy]    = useState<BusySlot[]>([]);
  const [loadingBusy, setLoadingBusy] = useState(false);
  const [saving,  setSaving]  = useState(false);
  const [error,   setError]   = useState('');

  const duration = entry.duration_minutes || 30;
  const current  = String(entry.scheduled_at).slice(0, 16);

  useEffect(() => {
    let cancelled = false;
    api.get<Partial<BookingRules>>('/api/appointments/booking-rules')
      .then(({ data }) => { if (!cancelled && data) setRules({ ...DEFAULT_RULES, ...data }); })
      .catch(() => { /* keep the defaults; the server still validates the new time */ });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    setTime('');
    setBusy([]);
    if (!date || !user?.system_id) return;
    let cancelled = false;
    setLoadingBusy(true);
    api.get<BusySlot[]>(`/api/appointments/doctor/${user.system_id}/busy`, { params: { date } })
      .then(({ data }) => { if (!cancelled) setBusy(Array.isArray(data) ? data : []); })
      .catch(() => { if (!cancelled) setBusy([]); })
      .finally(() => { if (!cancelled) setLoadingBusy(false); });
    return () => { cancelled = true; };
  }, [date, user?.system_id]);

  const now = clinicNow(rules.timeZone || CLINIC_TIME_ZONE);
  const today = now.slice(0, 10);
  const lastDay = addDays(today, rules.maxAdvanceDays);

  const dayProblem = !date ? null
    : date < today ? 'Please choose a date in the future.'
    : date > lastDay ? `Appointments can be booked at most ${rules.maxAdvanceDays} days in advance.`
    : !rules.allowWeekends && isWeekend(date) ? 'The clinic is closed on weekends. Please choose a weekday.'
    : null;

  const slots = useMemo(() => {
    if (!date || dayProblem) return [];
    return daySlots(rules, duration).map(t => {
      const start = `${date}T${t}`;
      const end   = addMinutes(start, duration);
      const isCurrent = start === current;
      const taken = busy.some(b => {
        const bs = String(b.start).slice(0, 16);
        const be = String(b.end).slice(0, 16);
        if (bs === current) return false; // the appointment being moved
        return start < be && bs < end;
      });
      return { time: t, past: start <= now, taken, isCurrent };
    });
  }, [date, dayProblem, rules, duration, busy, current, now]);

  async function handleSave() {
    setError('');
    if (!date || !time) { setError('Please choose a new date and time.'); return; }
    if (`${date}T${time}` <= clinicNow(rules.timeZone || CLINIC_TIME_ZONE)) {
      setError('This time has already passed. Please choose a later time.');
      return;
    }
    setSaving(true);
    try {
      await rescheduleDoctorAppointment(entry.appointment_id, date, time);
      onDone(date, time);
    } catch (e) {
      setError(apiErrorMessage(e, 'Could not reschedule the appointment. Please try again.'));
    } finally {
      setSaving(false);
    }
  }

  const available = slots.filter(s => !s.past && !s.taken && !s.isCurrent).length;

  return (
    <View style={styles.panel}>
      <Text style={styles.title} accessibilityRole="header">Choose a new date and time</Text>
      <Text style={styles.sub}>
        {entry.patient_name} · {duration} min · times are clinic time (CAT)
      </Text>

      <View style={styles.pickers}>
        <View style={styles.pickerCol}>
          <Text style={styles.label}>Date</Text>
          <View style={styles.pickerCard}>
            <CalendarPicker value={date} onChange={setDate} />
          </View>
        </View>

        <View style={styles.pickerCol}>
          <Text style={styles.label}>Time</Text>
          <View style={styles.pickerCard}>
            {!date ? (
              <Text style={styles.hint}>Choose a date to see available times.</Text>
            ) : dayProblem ? (
              <Text style={styles.hint}>{dayProblem}</Text>
            ) : loadingBusy ? (
              <ActivityIndicator color={BLUE} />
            ) : (
              <>
                <View style={styles.slotGrid} accessibilityRole="radiogroup" accessibilityLabel="Available times">
                  {slots.map(s => {
                    const disabled = s.past || s.taken || s.isCurrent;
                    const selected = time === s.time;
                    const why = s.isCurrent ? ', current time' : s.taken ? ', not available' : s.past ? ', passed' : '';
                    return (
                      <TouchableOpacity
                        key={s.time}
                        style={[styles.slot, selected && styles.slotSelected, disabled && styles.slotDisabled]}
                        disabled={disabled}
                        onPress={() => setTime(s.time)}
                        activeOpacity={0.7}
                        accessibilityRole="radio"
                        accessibilityLabel={`${formatClinicTime(s.time)}${why}`}
                        accessibilityState={{ disabled, checked: selected }}
                        aria-checked={selected}
                      >
                        <Text style={[styles.slotText, selected && styles.slotTextSelected, disabled && styles.slotTextDisabled]}>
                          {formatClinicTime(s.time)}
                        </Text>
                      </TouchableOpacity>
                    );
                  })}
                </View>
                {available === 0 && <Text style={styles.hint}>No free times on this day. Please choose another date.</Text>}
              </>
            )}
          </View>
        </View>
      </View>

      {!!date && !!time && (
        <Text style={styles.summary}>
          New time: {formatClinicDate(date)}, {formatClinicTime(time)} CAT
        </Text>
      )}

      {!!error && (
        <View style={styles.inlineError} accessibilityRole="alert">
          <Ionicons name="alert-circle-outline" size={14} color={RED} />
          <Text style={styles.inlineErrorText}>{error}</Text>
        </View>
      )}

      <View style={styles.actions}>
        <TouchableOpacity
          style={styles.cancelBtn}
          onPress={onClose}
          disabled={saving}
          activeOpacity={0.8}
          accessibilityRole="button"
        >
          <Text style={styles.cancelText}>Keep current time</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.saveBtn, (saving || !date || !time) && { opacity: 0.6 }]}
          onPress={handleSave}
          disabled={saving || !date || !time}
          activeOpacity={0.85}
          accessibilityRole="button"
          accessibilityState={{ disabled: saving || !date || !time, busy: saving }}
        >
          <Text style={styles.saveText}>{saving ? 'Saving…' : 'Confirm new time'}</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  panel:     { borderTopWidth: 1, borderTopColor: BORDER, padding: 16, gap: 10, backgroundColor: BG },
  title:     { fontSize: 14, fontWeight: '700', color: TEXT },
  sub:       { fontSize: 12, color: GRAY },
  pickers:   { flexDirection: 'row', flexWrap: 'wrap', gap: 16 },
  pickerCol: { flex: 1, minWidth: 240, gap: 6 },
  label:     { fontSize: 12, fontWeight: '600', color: GRAY },
  pickerCard: { backgroundColor: WHITE, borderWidth: 1, borderColor: BORDER, borderRadius: 12, padding: 12, gap: 10 },
  hint:      { fontSize: 12, color: GRAY },

  slotGrid:         { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  slot:             { minWidth: 76, alignItems: 'center', paddingHorizontal: 8, paddingVertical: 6, borderRadius: 8, borderWidth: 1, borderColor: BORDER, backgroundColor: WHITE },
  slotSelected:     { backgroundColor: BLUE, borderColor: BLUE },
  slotDisabled:     { backgroundColor: BG, borderColor: BG },
  slotText:         { fontSize: 12, fontWeight: '600', color: TEXT },
  slotTextSelected: { color: WHITE },
  slotTextDisabled: { color: LGRAY, textDecorationLine: 'line-through' },

  summary:         { fontSize: 13, fontWeight: '600', color: BLUE },
  inlineError:     { flexDirection: 'row', alignItems: 'center', gap: 6 },
  inlineErrorText: { flex: 1, fontSize: 12, color: RED },

  actions:    { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'flex-end', gap: 8 },
  cancelBtn:  { borderWidth: 1, borderColor: BORDER, borderRadius: 8, paddingHorizontal: 14, paddingVertical: 8, backgroundColor: WHITE },
  cancelText: { fontSize: 13, fontWeight: '600', color: GRAY },
  saveBtn:    { backgroundColor: BLUE, borderRadius: 8, paddingHorizontal: 14, paddingVertical: 8 },
  saveText:   { fontSize: 13, fontWeight: '600', color: WHITE },
});
