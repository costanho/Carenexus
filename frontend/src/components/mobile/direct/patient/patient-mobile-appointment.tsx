import { Ionicons } from '@expo/vector-icons';
import { zodResolver } from '@hookform/resolvers/zod';
import { useRouter } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import {
  KeyboardAvoidingView, Platform, ScrollView, StyleSheet,
  Text, TextInput, TouchableOpacity, useWindowDimensions, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { z } from 'zod';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useDoctors } from '@/hooks/use-doctors';
import { api } from '@/lib/auth/auth.interceptor';

const TEAL   = '#0D9488';
const INDIGO = '#4F46E5';
const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const GRAY2  = '#9CA3AF';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const RED    = '#EF4444';
const GREEN  = '#10B981';

const MONTH_NAMES = ['January','February','March','April','May','June','July','August','September','October','November','December'];
const DAY_LABELS  = ['Su','Mo','Tu','We','Th','Fr','Sa'];
const HOURS       = [1,2,3,4,5,6,7,8,9,10,11,12];
const MINUTES     = [0,15,30,45];

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type ApptType = 'IN_PERSON' | 'VIDEO' | 'PHONE';

const appointmentSchema = z.object({
  doctor_id:        z.string().min(1, 'Please select a doctor'),
  scheduled_date:   z.string().min(1, 'Please select a date'),
  scheduled_time:   z.string().min(1, 'Please select a time'),
  duration_minutes: z.string().min(1, 'Please select a duration'),
  type:             z.enum(['IN_PERSON', 'VIDEO', 'PHONE']),
  facility_id:      z.string().optional(),
  reason_for_visit: z.string().min(5, 'Please describe the reason (min 5 characters)'),
  notes:            z.string().optional(),
});

type AppointmentFormData = z.infer<typeof appointmentSchema>;

const FACILITIES = [
  { id: '1', name: 'CareNexus Main Clinic' },
  { id: '2', name: 'Parirenyatwa Hospital' },
  { id: '3', name: 'Avenues Clinic'        },
];

const DURATIONS = [
  { value: '15', label: '15 min' },
  { value: '30', label: '30 min' },
  { value: '45', label: '45 min' },
  { value: '60', label: '60 min' },
];

const APPT_TYPES: { value: ApptType; label: string; icon: IoniconsName; color: string; bg: string }[] = [
  { value: 'IN_PERSON', label: 'In Person',  icon: 'business-outline', color: TEAL,   bg: '#E6F4F1' },
  { value: 'VIDEO',     label: 'Video',      icon: 'videocam-outline', color: INDIGO, bg: '#EEF2FF' },
  { value: 'PHONE',     label: 'Phone',      icon: 'call-outline',     color: GREEN,  bg: '#F0FDF4' },
];

// ─── CalendarPicker ──────────────────────────────────────────────────────────

function CalendarPicker({
  value, onChange, s,
}: { value: string; onChange: (v: string) => void; s: (n: number) => number }) {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const todayRef = today.getTime();

  const [viewYear,  setViewYear]  = useState(today.getFullYear());
  const [viewMonth, setViewMonth] = useState(today.getMonth());

  const isPrevDisabled =
    viewYear < today.getFullYear() ||
    (viewYear === today.getFullYear() && viewMonth <= today.getMonth());

  function prevMonth() {
    if (isPrevDisabled) return;
    if (viewMonth === 0) { setViewYear(y => y - 1); setViewMonth(11); }
    else setViewMonth(m => m - 1);
  }
  function nextMonth() {
    if (viewMonth === 11) { setViewYear(y => y + 1); setViewMonth(0); }
    else setViewMonth(m => m + 1);
  }

  const firstDay = new Date(viewYear, viewMonth, 1).getDay();
  const daysInMonth = new Date(viewYear, viewMonth + 1, 0).getDate();
  const cells: (number | null)[] = [
    ...Array(firstDay).fill(null),
    ...Array.from({ length: daysInMonth }, (_, i) => i + 1),
  ];
  while (cells.length % 7 !== 0) cells.push(null);

  function selectDay(day: number) {
    const mm = String(viewMonth + 1).padStart(2, '0');
    const dd = String(day).padStart(2, '0');
    onChange(`${viewYear}-${mm}-${dd}`);
  }

  function isSelected(day: number) {
    const mm = String(viewMonth + 1).padStart(2, '0');
    const dd = String(day).padStart(2, '0');
    return value === `${viewYear}-${mm}-${dd}`;
  }
  function isToday(day: number) {
    return (
      viewYear  === today.getFullYear() &&
      viewMonth === today.getMonth() &&
      day       === today.getDate()
    );
  }
  function isPast(day: number) {
    return new Date(viewYear, viewMonth, day).getTime() < todayRef;
  }

  return (
    <View style={{ gap: s(10) }}>
      {/* Month navigation */}
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
        <TouchableOpacity
          onPress={prevMonth}
          disabled={isPrevDisabled}
          activeOpacity={0.7}
          style={[
            styles.calNavBtn,
            { width: s(32), height: s(32), borderRadius: s(8) },
            isPrevDisabled && { opacity: 0.3 },
          ]}
        >
          <Ionicons name="chevron-back" size={s(16)} color={TEXT} />
        </TouchableOpacity>
        <Text style={[styles.calMonthLabel, { fontSize: s(14) }]}>
          {MONTH_NAMES[viewMonth]} {viewYear}
        </Text>
        <TouchableOpacity
          onPress={nextMonth}
          activeOpacity={0.7}
          style={[styles.calNavBtn, { width: s(32), height: s(32), borderRadius: s(8) }]}
        >
          <Ionicons name="chevron-forward" size={s(16)} color={TEXT} />
        </TouchableOpacity>
      </View>

      {/* Day-of-week headers */}
      <View style={{ flexDirection: 'row' }}>
        {DAY_LABELS.map(d => (
          <View key={d} style={{ flex: 1, alignItems: 'center' }}>
            <Text style={[styles.calDayHeader, { fontSize: s(10) }]}>{d}</Text>
          </View>
        ))}
      </View>

      {/* Grid */}
      <View style={{ gap: s(4) }}>
        {Array.from({ length: cells.length / 7 }, (_, row) => (
          <View key={row} style={{ flexDirection: 'row' }}>
            {cells.slice(row * 7, row * 7 + 7).map((day, col) => {
              if (!day) return <View key={col} style={{ flex: 1 }} />;
              const past     = isPast(day);
              const selected = isSelected(day);
              const todayDay = isToday(day);
              return (
                <View key={col} style={{ flex: 1, alignItems: 'center' }}>
                  <TouchableOpacity
                    onPress={() => selectDay(day)}
                    disabled={past}
                    activeOpacity={0.75}
                    style={[
                      styles.calDayCircle,
                      { width: s(32), height: s(32), borderRadius: s(16) },
                      selected && { backgroundColor: TEAL },
                      !selected && todayDay && { borderWidth: 1.5, borderColor: TEAL },
                    ]}
                  >
                    <Text
                      style={[
                        styles.calDayText,
                        { fontSize: s(12) },
                        past     && { color: '#D1D5DB' },
                        todayDay && !selected && { color: TEAL, fontWeight: '700' },
                        selected && { color: WHITE, fontWeight: '700' },
                      ]}
                    >
                      {day}
                    </Text>
                  </TouchableOpacity>
                </View>
              );
            })}
          </View>
        ))}
      </View>

      {/* Selected date display */}
      {value ? (
        <View style={[styles.calSelectedRow, { borderRadius: s(8), padding: s(8), gap: s(6) }]}>
          <Ionicons name="calendar" size={s(13)} color={TEAL} />
          <Text style={[styles.calSelectedText, { fontSize: s(12) }]}>{value}</Text>
        </View>
      ) : (
        <Text style={[styles.calHint, { fontSize: s(11) }]}>Tap a date to select</Text>
      )}
    </View>
  );
}

// ─── TimePicker ──────────────────────────────────────────────────────────────

function TimePicker({
  value, onChange, s,
}: { value: string; onChange: (v: string) => void; s: (n: number) => number }) {
  const [hour,   setHour]   = useState(9);
  const [minute, setMinute] = useState(0);
  const [ampm,   setAmpm]   = useState<'AM'|'PM'>('AM');
  const synced = useRef(false);

  function parse24(v: string) {
    const [hStr, mStr] = v.split(':');
    let h = parseInt(hStr, 10);
    const m = parseInt(mStr, 10) || 0;
    const ap: 'AM'|'PM' = h < 12 ? 'AM' : 'PM';
    if (h === 0) h = 12;
    else if (h > 12) h -= 12;
    return { h, m, ap };
  }

  useEffect(() => {
    if (value && !synced.current) {
      const { h, m, ap } = parse24(value);
      setHour(h); setMinute(m); setAmpm(ap);
      synced.current = true;
    }
  }, [value]);

  function emit(h: number, m: number, ap: 'AM'|'PM') {
    let h24 = h;
    if (ap === 'AM' && h === 12) h24 = 0;
    else if (ap === 'PM' && h !== 12) h24 = h + 12;
    const hh = String(h24).padStart(2, '0');
    const mm = String(m).padStart(2, '0');
    onChange(`${hh}:${mm}`);
  }

  function pickHour(h: number) { setHour(h); emit(h, minute, ampm); }
  function pickMinute(m: number) { setMinute(m); emit(hour, m, ampm); }
  function pickAmpm(ap: 'AM'|'PM') { setAmpm(ap); emit(hour, minute, ap); }

  const displayHour   = String(hour).padStart(2, '0');
  const displayMinute = String(minute).padStart(2, '0');

  return (
    <View style={{ gap: s(12) }}>
      {/* AM / PM */}
      <View>
        <Text style={[styles.timeSubLabel, { fontSize: s(11), marginBottom: s(6) }]}>AM / PM</Text>
        <View style={{ flexDirection: 'row', gap: s(8) }}>
          {(['AM', 'PM'] as const).map(ap => (
            <TouchableOpacity
              key={ap}
              onPress={() => pickAmpm(ap)}
              activeOpacity={0.8}
              style={[
                styles.ampmBtn,
                { flex: 1, borderRadius: s(10), paddingVertical: s(12) },
                ampm === ap && styles.ampmBtnActive,
              ]}
            >
              <Text style={[styles.ampmText, { fontSize: s(14) }, ampm === ap && styles.ampmTextActive]}>
                {ap}
              </Text>
            </TouchableOpacity>
          ))}
        </View>
      </View>

      {/* Hour */}
      <View>
        <Text style={[styles.timeSubLabel, { fontSize: s(11), marginBottom: s(6) }]}>Hour</Text>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: s(8) }}>
          {HOURS.map(h => (
            <TouchableOpacity
              key={h}
              onPress={() => pickHour(h)}
              activeOpacity={0.75}
              style={[
                styles.hourBtn,
                { width: s(44), height: s(44), borderRadius: s(22) },
                hour === h && styles.hourBtnActive,
              ]}
            >
              <Text style={[styles.hourText, { fontSize: s(13) }, hour === h && styles.hourTextActive]}>
                {h}
              </Text>
            </TouchableOpacity>
          ))}
        </View>
      </View>

      {/* Minute */}
      <View>
        <Text style={[styles.timeSubLabel, { fontSize: s(11), marginBottom: s(6) }]}>Minute</Text>
        <View style={{ flexDirection: 'row', gap: s(8) }}>
          {MINUTES.map(m => (
            <TouchableOpacity
              key={m}
              onPress={() => pickMinute(m)}
              activeOpacity={0.75}
              style={[
                styles.minuteBtn,
                { flex: 1, borderRadius: s(8), paddingVertical: s(10) },
                minute === m && styles.minuteBtnActive,
              ]}
            >
              <Text style={[styles.minuteText, { fontSize: s(12) }, minute === m && styles.minuteTextActive]}>
                :{String(m).padStart(2, '0')}
              </Text>
            </TouchableOpacity>
          ))}
        </View>
      </View>

      {/* Preview */}
      {value ? (
        <View style={[styles.timePreview, { borderRadius: s(8), padding: s(8), gap: s(6) }]}>
          <Ionicons name="time" size={s(13)} color={TEAL} />
          <Text style={[styles.timePreviewText, { fontSize: s(12) }]}>
            {displayHour}:{displayMinute} {ampm}
          </Text>
        </View>
      ) : (
        <Text style={[styles.calHint, { fontSize: s(11) }]}>Select time above</Text>
      )}
    </View>
  );
}

// ─── Main Component ───────────────────────────────────────────────────────────

export function PatientMobileAppointment({ onBack }: { onBack?: () => void }) {
  const router  = useRouter();
  const insets  = useSafeAreaInsets();
  const goBack  = onBack ?? (() => router.back());
  const { width, height } = useWindowDimensions();
  const { user } = useCurrentUser();
  const { doctors, loading: doctorsLoading } = useDoctors();

  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));

  const [loading, setLoading]             = useState(false);
  const [success, setSuccess]             = useState(false);
  const [authError, setAuthError]         = useState('');
  const [doctorSearch, setDoctorSearch]   = useState('');
  const [doctorsExpanded, setDoctorsExpanded] = useState(false);

  const { control, handleSubmit, watch, formState: { errors } } = useForm<AppointmentFormData>({
    resolver: zodResolver(appointmentSchema),
    defaultValues: {
      doctor_id: '', scheduled_date: '', scheduled_time: '',
      duration_minutes: '30', type: 'IN_PERSON',
      facility_id: '', reason_for_visit: '', notes: '',
    },
  });

  const watchedType   = watch('type');
  const watchedDoctor = watch('doctor_id');
  const selectedDoctor = doctors.find(d => String(d.doctor_id) === watchedDoctor);

  const activeDoctors = doctors.filter(d => d.is_active === 1);
  const filteredDoctors = doctorSearch.trim()
    ? activeDoctors.filter(d =>
        d.full_name.toLowerCase().includes(doctorSearch.toLowerCase()) ||
        (d.specialization ?? '').toLowerCase().includes(doctorSearch.toLowerCase())
      )
    : activeDoctors;
  const visibleDoctors = doctorsExpanded ? filteredDoctors : filteredDoctors.slice(0, 3);

  async function onSubmit(data: AppointmentFormData) {
    setAuthError('');
    setLoading(true);
    try {
      await api.post('/api/appointments', {
        patient_id:       user?.user_id,
        doctor_id:        parseInt(data.doctor_id),
        facility_id:      data.facility_id ? parseInt(data.facility_id) : null,
        scheduled_at:     `${data.scheduled_date}T${data.scheduled_time}:00Z`,
        duration_minutes: parseInt(data.duration_minutes),
        type:             data.type,
        reason_for_visit: data.reason_for_visit,
        notes:            data.notes ?? null,
      });
      setSuccess(true);
    } catch {
      setAuthError('Failed to book appointment. Please try again.');
    } finally {
      setLoading(false);
    }
  }

  if (success) {
    return (
      <View style={[styles.successScreen, { paddingTop: insets.top }]}>
        <Ionicons name="checkmark-circle" size={s(72)} color={TEAL} />
        <Text style={[styles.successTitle, { fontSize: s(22) }]}>Booked!</Text>
        <Text style={[styles.successSub, { fontSize: s(13) }]}>
          Appointment with {selectedDoctor?.full_name ?? 'your doctor'} has been scheduled.
        </Text>
        <TouchableOpacity
          style={[styles.successBtn, { borderRadius: s(12), paddingVertical: s(14), paddingHorizontal: s(32) }]}
          onPress={goBack}
          activeOpacity={0.85}
        >
          <Text style={[styles.successBtnText, { fontSize: s(15) }]}>Back to Dashboard</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <KeyboardAvoidingView
      style={styles.screen}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      {/* Header */}
      <View style={[styles.header, {
        paddingTop:        insets.top + sh(12),
        paddingBottom:     sh(18),
        paddingHorizontal: s(16),
        gap:               s(10),
      }]}>
        <TouchableOpacity
          style={[styles.headerBack, { width: s(34), height: s(34), borderRadius: s(17) }]}
          onPress={goBack}
          activeOpacity={0.7}
        >
          <Ionicons name="arrow-back-outline" size={s(18)} color={WHITE} />
        </TouchableOpacity>
        <View style={{ flex: 1 }}>
          <Text style={[styles.headerTitle, { fontSize: s(18) }]}>Book Appointment</Text>
          <Text style={[styles.headerSub, { fontSize: s(11) }]}>Schedule with your healthcare provider</Text>
        </View>
        <View style={[styles.headerIcon, { width: s(36), height: s(36), borderRadius: s(18) }]}>
          <Ionicons name="calendar-outline" size={s(18)} color={TEAL} />
        </View>
      </View>

      <ScrollView
        style={styles.scroll}
        contentContainerStyle={[styles.scrollContent, {
          padding: s(16),
          paddingBottom: insets.bottom + sh(32),
          gap: s(20),
        }]}
        showsVerticalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
      >
        {/* Appointment type */}
        <View>
          <Text style={[styles.sectionTitle, { fontSize: s(12), marginBottom: s(10) }]}>Appointment Type</Text>
          <Controller control={control} name="type"
            render={({ field: { onChange, value } }) => (
              <View style={[styles.typeRow, { gap: s(8) }]}>
                {APPT_TYPES.map(t => {
                  const on = value === t.value;
                  return (
                    <TouchableOpacity
                      key={t.value}
                      style={[
                        styles.typeCard,
                        { borderRadius: s(10), paddingVertical: s(10), gap: s(4) },
                        on && { borderColor: t.color, backgroundColor: t.bg },
                      ]}
                      onPress={() => onChange(t.value)}
                      activeOpacity={0.8}
                    >
                      {on && (
                        <View style={[styles.typeCheck, { backgroundColor: t.color, width: s(16), height: s(16), borderRadius: s(8) }]}>
                          <Ionicons name="checkmark" size={s(9)} color={WHITE} />
                        </View>
                      )}
                      <Ionicons name={t.icon} size={s(20)} color={on ? t.color : GRAY} />
                      <Text style={[styles.typeLabel, { fontSize: s(11) }, on && { color: t.color, fontWeight: '700' }]}>{t.label}</Text>
                    </TouchableOpacity>
                  );
                })}
              </View>
            )}
          />
        </View>

        {/* Doctor */}
        <View>
          <Text style={[styles.sectionTitle, { fontSize: s(12), marginBottom: s(10) }]}>Select Doctor</Text>

          <View style={[styles.inputRow, { borderRadius: s(10), paddingHorizontal: s(12), paddingVertical: s(10), marginBottom: s(10) }]}>
            <Ionicons name="search-outline" size={s(15)} color={GRAY} style={{ marginRight: s(8) }} />
            <TextInput
              style={[styles.input, { fontSize: s(13) }]}
              placeholder="Search by name or specialization…"
              placeholderTextColor={GRAY}
              value={doctorSearch}
              onChangeText={t => { setDoctorSearch(t); setDoctorsExpanded(false); }}
              autoCorrect={false}
            />
            {doctorSearch.length > 0 && (
              <TouchableOpacity onPress={() => setDoctorSearch('')} activeOpacity={0.7}>
                <Ionicons name="close-circle" size={s(15)} color={GRAY} />
              </TouchableOpacity>
            )}
          </View>

          {doctorsLoading ? (
            <Text style={{ color: GRAY, fontSize: s(13) }}>Loading doctors…</Text>
          ) : filteredDoctors.length === 0 ? (
            <Text style={{ color: GRAY, fontSize: s(13) }}>No doctors match your search.</Text>
          ) : (
            <>
              <Controller control={control} name="doctor_id"
                render={({ field: { onChange, value } }) => (
                  <View style={{ gap: s(8) }}>
                    {visibleDoctors.map(d => {
                      const id = String(d.doctor_id);
                      const on = value === id;
                      return (
                        <TouchableOpacity
                          key={id}
                          style={[
                            styles.doctorCard,
                            { borderRadius: s(12), padding: s(12), gap: s(10) },
                            on && styles.doctorCardActive,
                          ]}
                          onPress={() => onChange(id)}
                          activeOpacity={0.8}
                        >
                          <View style={[styles.doctorAvatar, { width: s(40), height: s(40), borderRadius: s(20) }]}>
                            <Text style={[styles.doctorAvatarText, { fontSize: s(16) }]}>{d.first_name[0]}</Text>
                          </View>
                          <View style={{ flex: 1 }}>
                            <Text style={[styles.doctorName, { fontSize: s(13) }, on && { color: TEAL }]}>{d.full_name}</Text>
                            <Text style={[styles.doctorSpec, { fontSize: s(11) }]}>{d.specialization ?? ''}</Text>
                          </View>
                          {on && <Ionicons name="checkmark-circle" size={s(20)} color={TEAL} />}
                        </TouchableOpacity>
                      );
                    })}
                  </View>
                )}
              />
              {filteredDoctors.length > 3 && (
                <TouchableOpacity
                  style={[styles.expandBtn, { borderRadius: s(10), paddingVertical: s(10), marginTop: s(8), gap: s(6) }]}
                  onPress={() => setDoctorsExpanded(e => !e)}
                  activeOpacity={0.7}
                >
                  <Text style={[styles.expandBtnText, { fontSize: s(13) }]}>
                    {doctorsExpanded
                      ? 'Show less'
                      : `Show ${filteredDoctors.length - 3} more doctor${filteredDoctors.length - 3 > 1 ? 's' : ''}`}
                  </Text>
                  <Ionicons name={doctorsExpanded ? 'chevron-up' : 'chevron-down'} size={s(14)} color={TEAL} />
                </TouchableOpacity>
              )}
            </>
          )}
          {!!errors.doctor_id && <FieldError message={errors.doctor_id.message} s={s} />}
        </View>

        {/* Date & Time — stacked vertically on mobile */}
        <View style={{ gap: s(16) }}>
          <Text style={[styles.sectionTitle, { fontSize: s(12) }]}>Date &amp; Time</Text>

          {/* Calendar */}
          <View style={[styles.pickerCard, { borderRadius: s(12), padding: s(14) }]}>
            <View style={[styles.pickerCardHeader, { marginBottom: s(12), gap: s(8) }]}>
              <Ionicons name="calendar-outline" size={s(15)} color={TEAL} />
              <Text style={[styles.pickerCardTitle, { fontSize: s(13) }]}>Select Date</Text>
            </View>
            <Controller control={control} name="scheduled_date"
              render={({ field: { onChange, value } }) => (
                <CalendarPicker value={value} onChange={onChange} s={s} />
              )}
            />
            {!!errors.scheduled_date && <FieldError message={errors.scheduled_date.message} s={s} />}
          </View>

          {/* Time */}
          <View style={[styles.pickerCard, { borderRadius: s(12), padding: s(14) }]}>
            <View style={[styles.pickerCardHeader, { marginBottom: s(12), gap: s(8) }]}>
              <Ionicons name="time-outline" size={s(15)} color={TEAL} />
              <Text style={[styles.pickerCardTitle, { fontSize: s(13) }]}>Select Time</Text>
            </View>
            <Controller control={control} name="scheduled_time"
              render={({ field: { onChange, value } }) => (
                <TimePicker value={value} onChange={onChange} s={s} />
              )}
            />
            {!!errors.scheduled_time && <FieldError message={errors.scheduled_time.message} s={s} />}
          </View>
        </View>

        {/* Duration */}
        <View>
          <Text style={[styles.sectionTitle, { fontSize: s(12), marginBottom: s(10) }]}>Duration</Text>
          <Controller control={control} name="duration_minutes"
            render={({ field: { onChange, value } }) => (
              <View style={[styles.twoCol, { gap: s(8) }]}>
                {DURATIONS.map(d => {
                  const on = value === d.value;
                  return (
                    <TouchableOpacity
                      key={d.value}
                      style={[
                        styles.durationBtn,
                        { flex: 1, borderRadius: s(10), paddingVertical: s(10) },
                        on && styles.durationBtnActive,
                      ]}
                      onPress={() => onChange(d.value)}
                      activeOpacity={0.8}
                    >
                      <Text style={[styles.durationLabel, { fontSize: s(12) }, on && styles.durationLabelActive]}>{d.label}</Text>
                    </TouchableOpacity>
                  );
                })}
              </View>
            )}
          />
        </View>

        {/* Facility */}
        {watchedType === 'IN_PERSON' && (
          <View>
            <Text style={[styles.sectionTitle, { fontSize: s(12), marginBottom: s(10) }]}>
              Facility <Text style={{ fontWeight: '400', color: GRAY }}>(optional)</Text>
            </Text>
            <Controller control={control} name="facility_id"
              render={({ field: { onChange, value } }) => (
                <View style={{ gap: s(8) }}>
                  {FACILITIES.map(f => {
                    const on = value === f.id;
                    return (
                      <TouchableOpacity
                        key={f.id}
                        style={[
                          styles.facilityItem,
                          { borderRadius: s(10), padding: s(12), gap: s(10) },
                          on && styles.facilityItemActive,
                        ]}
                        onPress={() => onChange(on ? '' : f.id)}
                        activeOpacity={0.8}
                      >
                        <Ionicons name="business-outline" size={s(16)} color={on ? TEAL : GRAY} />
                        <Text style={[styles.facilityName, { fontSize: s(13), flex: 1 }, on && { color: TEAL, fontWeight: '600' }]}>{f.name}</Text>
                        {on && <Ionicons name="checkmark-circle" size={s(16)} color={TEAL} />}
                      </TouchableOpacity>
                    );
                  })}
                </View>
              )}
            />
          </View>
        )}

        {/* Reason */}
        <View>
          <Text style={[styles.sectionTitle, { fontSize: s(12), marginBottom: s(10) }]}>Reason for Visit</Text>
          <Controller control={control} name="reason_for_visit"
            render={({ field: { onChange, value } }) => (
              <TextInput
                style={[styles.textarea, { borderRadius: s(10), padding: s(12), fontSize: s(13) }, !!errors.reason_for_visit && styles.inputRowError]}
                placeholder="Describe your symptoms or reason for the appointment..."
                placeholderTextColor={GRAY}
                value={value}
                onChangeText={onChange}
                multiline
                numberOfLines={4}
                textAlignVertical="top"
                autoCorrect={false}
              />
            )}
          />
          {!!errors.reason_for_visit && <FieldError message={errors.reason_for_visit.message} s={s} />}
        </View>

        {/* Notes */}
        <View>
          <Text style={[styles.sectionTitle, { fontSize: s(12), marginBottom: s(10) }]}>
            Notes <Text style={{ fontWeight: '400', color: GRAY }}>(optional)</Text>
          </Text>
          <Controller control={control} name="notes"
            render={({ field: { onChange, value } }) => (
              <TextInput
                style={[styles.textarea, { borderRadius: s(10), padding: s(12), fontSize: s(13) }]}
                placeholder="Any additional information for the doctor..."
                placeholderTextColor={GRAY}
                value={value ?? ''}
                onChangeText={onChange}
                multiline
                numberOfLines={3}
                textAlignVertical="top"
                autoCorrect={false}
              />
            )}
          />
        </View>

        {/* Error */}
        {!!authError && (
          <View style={[styles.errorBanner, { borderRadius: s(8), padding: s(10), gap: s(8) }]}>
            <Ionicons name="alert-circle-outline" size={s(14)} color={RED} />
            <Text style={[styles.errorText, { fontSize: s(12), flex: 1 }]}>{authError}</Text>
          </View>
        )}

        {/* Submit */}
        <TouchableOpacity
          style={[styles.submitBtn, {
            borderRadius: s(12), paddingVertical: s(15), gap: s(8),
            opacity: loading ? 0.7 : 1,
          }]}
          onPress={handleSubmit(onSubmit)}
          activeOpacity={0.85}
          disabled={loading}
        >
          <Ionicons name="calendar-outline" size={s(18)} color={WHITE} />
          <Text style={[styles.submitText, { fontSize: s(15) }]}>
            {loading ? 'Booking…' : 'Book Appointment'}
          </Text>
        </TouchableOpacity>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

function FieldError({ message, s }: { message?: string; s: (n: number) => number }) {
  if (!message) return null;
  return (
    <View style={[styles.fieldError, { gap: s(4), marginTop: s(4) }]}>
      <Ionicons name="alert-circle-outline" size={s(12)} color={RED} />
      <Text style={[styles.fieldErrorText, { fontSize: s(11) }]}>{message}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  screen:  { flex: 1, backgroundColor: BG },

  header:      { backgroundColor: TEAL, flexDirection: 'row', alignItems: 'center' },
  headerBack:  { backgroundColor: 'rgba(255,255,255,0.2)', alignItems: 'center', justifyContent: 'center' },
  headerTitle: { fontWeight: '800', color: WHITE },
  headerSub:   { color: 'rgba(255,255,255,0.8)', marginTop: 2 },
  headerIcon:  { backgroundColor: WHITE, alignItems: 'center', justifyContent: 'center' },

  scroll:        { flex: 1 },
  scrollContent: { flexGrow: 1 },

  sectionTitle: { fontWeight: '700', color: TEXT },
  label:        { fontWeight: '600', color: TEXT },

  typeRow:  { flexDirection: 'row' },
  typeCard: { flex: 1, alignItems: 'center', borderWidth: 1.5, borderColor: BORDER, position: 'relative' },
  typeCheck:{ position: 'absolute', top: -6, right: -6, alignItems: 'center', justifyContent: 'center' },
  typeLabel:{ color: GRAY, textAlign: 'center' },

  expandBtn:        { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', borderWidth: 1, borderColor: TEAL, backgroundColor: '#E6F4F1' },
  expandBtnText:    { fontWeight: '600', color: TEAL },
  doctorCard:       { flexDirection: 'row', alignItems: 'center', borderWidth: 1.5, borderColor: BORDER, backgroundColor: WHITE },
  doctorCardActive: { borderColor: TEAL, backgroundColor: '#E6F4F1' },
  doctorAvatar:     { backgroundColor: '#E6F4F1', alignItems: 'center', justifyContent: 'center', borderWidth: 2, borderColor: BORDER },
  doctorAvatarText: { fontWeight: '700', color: TEAL },
  doctorName:       { fontWeight: '600', color: TEXT },
  doctorSpec:       { color: GRAY, marginTop: 2 },

  twoCol: { flexDirection: 'row' },

  inputRow:      { flexDirection: 'row', alignItems: 'center', borderWidth: 1.5, borderColor: BORDER, backgroundColor: WHITE },
  inputRowError: { borderColor: RED },
  input:         { flex: 1, color: TEXT },

  // Picker cards
  pickerCard:       { borderWidth: 1.5, borderColor: BORDER, backgroundColor: WHITE },
  pickerCardHeader: { flexDirection: 'row', alignItems: 'center' },
  pickerCardTitle:  { fontWeight: '700', color: TEXT },

  // Calendar
  calNavBtn:      { alignItems: 'center', justifyContent: 'center', backgroundColor: BG, borderWidth: 1, borderColor: BORDER },
  calMonthLabel:  { fontWeight: '700', color: TEXT },
  calDayHeader:   { fontWeight: '600', color: GRAY2 },
  calDayCircle:   { alignItems: 'center', justifyContent: 'center' },
  calDayText:     { color: TEXT },
  calSelectedRow: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#E6F4F1' },
  calSelectedText:{ color: TEAL, fontWeight: '600' },
  calHint:        { color: GRAY2, textAlign: 'center' },

  // Time
  timeSubLabel:     { fontWeight: '600', color: GRAY },
  ampmBtn:          { alignItems: 'center', justifyContent: 'center', borderWidth: 1.5, borderColor: BORDER, backgroundColor: WHITE },
  ampmBtnActive:    { borderColor: TEAL, backgroundColor: '#E6F4F1' },
  ampmText:         { fontWeight: '700', color: GRAY },
  ampmTextActive:   { color: TEAL },
  hourBtn:          { alignItems: 'center', justifyContent: 'center', borderWidth: 1.5, borderColor: BORDER, backgroundColor: WHITE },
  hourBtnActive:    { borderColor: TEAL, backgroundColor: TEAL },
  hourText:         { fontWeight: '600', color: TEXT },
  hourTextActive:   { color: WHITE, fontWeight: '700' },
  minuteBtn:        { alignItems: 'center', justifyContent: 'center', borderWidth: 1.5, borderColor: BORDER, backgroundColor: WHITE },
  minuteBtnActive:  { borderColor: TEAL, backgroundColor: '#E6F4F1' },
  minuteText:       { fontWeight: '600', color: GRAY },
  minuteTextActive: { color: TEAL, fontWeight: '700' },
  timePreview:      { flexDirection: 'row', alignItems: 'center', backgroundColor: '#E6F4F1' },
  timePreviewText:  { color: TEAL, fontWeight: '600' },

  durationBtn:         { alignItems: 'center', borderWidth: 1.5, borderColor: BORDER, backgroundColor: WHITE },
  durationBtnActive:   { borderColor: TEAL, backgroundColor: '#E6F4F1' },
  durationLabel:       { fontWeight: '600', color: GRAY },
  durationLabelActive: { color: TEAL },

  facilityItem:      { flexDirection: 'row', alignItems: 'center', borderWidth: 1.5, borderColor: BORDER, backgroundColor: WHITE },
  facilityItemActive:{ borderColor: TEAL, backgroundColor: '#E6F4F1' },
  facilityName:      { color: TEXT },

  textarea:      { borderWidth: 1.5, borderColor: BORDER, color: TEXT, backgroundColor: WHITE, minHeight: 90 },

  fieldError:     { flexDirection: 'row', alignItems: 'center' },
  fieldErrorText: { color: RED },

  errorBanner: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#FEF2F2' },
  errorText:   { color: RED },

  submitBtn:  { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', backgroundColor: TEAL },
  submitText: { fontWeight: '700', color: WHITE },

  successScreen: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: BG, padding: 32, gap: 16 },
  successTitle:  { fontWeight: '800', color: TEXT },
  successSub:    { color: GRAY, textAlign: 'center', lineHeight: 22 },
  successBtn:    { backgroundColor: TEAL, marginTop: 8 },
  successBtnText:{ fontWeight: '700', color: WHITE },
});

export default PatientMobileAppointment;
