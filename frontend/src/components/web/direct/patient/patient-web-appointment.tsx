import { Ionicons } from '@expo/vector-icons';
import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import {
  ScrollView, StyleSheet, Text, TextInput,
  TouchableOpacity, useWindowDimensions, View,
} from 'react-native';
import { z } from 'zod';
import { useCurrentUser } from '@/hooks/use-current-user';
import { useDoctors } from '@/hooks/use-doctors';
import { api } from '@/lib/auth/auth.interceptor';

const TEAL   = '#0D9488';
const INDIGO = '#4F46E5';
const NAVY   = '#0D1B2E';
const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const RED    = '#EF4444';
const GREEN  = '#10B981';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
type ApptType = 'IN_PERSON' | 'VIDEO' | 'PHONE';

const appointmentSchema = z.object({
  doctor_id:        z.string().min(1, 'Please select a doctor'),
  scheduled_date:   z.string().min(1, 'Please select a date'),
  scheduled_time:   z.string().min(1, 'Please select a time'),
  duration_minutes: z.string().min(1, 'Please select a duration'),
  type:             z.enum(['IN_PERSON', 'VIDEO', 'PHONE']),
  facility_id:      z.string().optional(),
  reason_for_visit: z.string().min(5, 'Please describe the reason for your visit (min 5 characters)'),
  notes:            z.string().optional(),
});

type AppointmentFormData = z.infer<typeof appointmentSchema>;


const FACILITIES = [
  { id: '1', name: 'CareNexus Main Clinic'  },
  { id: '2', name: 'Parirenyatwa Hospital'  },
  { id: '3', name: 'Avenues Clinic'         },
];

const DURATIONS = [
  { value: '15', label: '15 min' },
  { value: '30', label: '30 min' },
  { value: '45', label: '45 min' },
  { value: '60', label: '60 min' },
];

const APPT_TYPES: { value: ApptType; label: string; icon: IoniconsName; color: string; bg: string; desc: string }[] = [
  { value: 'IN_PERSON', label: 'In Person',  icon: 'business-outline',  color: TEAL,   bg: '#E6F4F1', desc: 'Visit the clinic'     },
  { value: 'VIDEO',     label: 'Video Call', icon: 'videocam-outline',  color: INDIGO, bg: '#EEF2FF', desc: 'Consult from anywhere' },
  { value: 'PHONE',     label: 'Phone Call', icon: 'call-outline',      color: GREEN,  bg: '#F0FDF4', desc: 'Audio consultation'    },
];

function FieldError({ message }: { message?: string }) {
  if (!message) return null;
  return (
    <View style={styles.fieldError}>
      <Ionicons name="alert-circle-outline" size={13} color={RED} />
      <Text style={styles.fieldErrorText}>{message}</Text>
    </View>
  );
}

export function PatientWebAppointment({ onBack }: { onBack?: () => void }) {
  const { width } = useWindowDimensions();
  const { user } = useCurrentUser();
  const { doctors, loading: doctorsLoading } = useDoctors();
  const narrow = width < 900;

  const [loading, setLoading]         = useState(false);
  const [success, setSuccess]         = useState(false);
  const [authError, setAuthError]     = useState('');
  const [doctorSearch, setDoctorSearch] = useState('');
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
      <View style={styles.successScreen}>
        <View style={styles.successCard}>
          <View style={styles.successIcon}>
            <Ionicons name="checkmark-circle" size={56} color={TEAL} />
          </View>
          <Text style={styles.successTitle}>Appointment Booked!</Text>
          <Text style={styles.successSub}>
            Your appointment with {selectedDoctor?.full_name ?? 'your doctor'} has been scheduled.
            You will receive a confirmation shortly.
          </Text>
          <TouchableOpacity style={styles.successBtn} onPress={onBack} activeOpacity={0.85}>
            <Text style={styles.successBtnText}>Back to Dashboard</Text>
          </TouchableOpacity>
        </View>
      </View>
    );
  }

  return (
    <View style={styles.screen}>
      {/* ── Form pane ── */}
      <ScrollView
        style={[styles.formPane, narrow && styles.formPaneFull]}
        contentContainerStyle={styles.formScroll}
        showsVerticalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
      >
        {/* Header */}
        <View style={styles.pageHeader}>
<View>
            <Text style={styles.pageTitle}>Book Appointment</Text>
            <Text style={styles.pageSub}>Schedule a visit with your healthcare provider</Text>
          </View>
        </View>

        {/* Appointment type */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Appointment Type</Text>
          <Controller control={control} name="type"
            render={({ field: { onChange, value } }) => (
              <View style={styles.typeRow}>
                {APPT_TYPES.map(t => {
                  const on = value === t.value;
                  return (
                    <TouchableOpacity
                      key={t.value}
                      style={[styles.typeCard, on && { borderColor: t.color, backgroundColor: t.bg }]}
                      onPress={() => onChange(t.value)}
                      activeOpacity={0.8}
                    >
                      {on && (
                        <View style={[styles.typeCheck, { backgroundColor: t.color }]}>
                          <Ionicons name="checkmark" size={10} color={WHITE} />
                        </View>
                      )}
                      <Ionicons name={t.icon} size={22} color={on ? t.color : GRAY} />
                      <Text style={[styles.typeLabel, on && { color: t.color, fontWeight: '700' }]}>{t.label}</Text>
                      <Text style={[styles.typeDesc, on && { color: t.color }]}>{t.desc}</Text>
                    </TouchableOpacity>
                  );
                })}
              </View>
            )}
          />
          <FieldError message={errors.type?.message} />
        </View>

        {/* Doctor */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Select Doctor</Text>

          {/* Search */}
          <View style={[styles.inputRow, styles.doctorSearchRow]}>
            <Ionicons name="search-outline" size={16} color={GRAY} style={styles.inputIcon} />
            <TextInput
              style={[styles.input, { fontSize: 13 }]}
              placeholder="Search by name or specialization…"
              placeholderTextColor={GRAY}
              value={doctorSearch}
              onChangeText={t => { setDoctorSearch(t); setDoctorsExpanded(false); }}
              autoCorrect={false}
            />
            {doctorSearch.length > 0 && (
              <TouchableOpacity onPress={() => setDoctorSearch('')} activeOpacity={0.7}>
                <Ionicons name="close-circle" size={16} color={GRAY} />
              </TouchableOpacity>
            )}
          </View>

          {doctorsLoading ? (
            <Text style={{ color: GRAY, fontSize: 13, marginTop: 8 }}>Loading doctors…</Text>
          ) : filteredDoctors.length === 0 ? (
            <Text style={{ color: GRAY, fontSize: 13, marginTop: 8 }}>No doctors match your search.</Text>
          ) : (
            <>
              <Controller control={control} name="doctor_id"
                render={({ field: { onChange, value } }) => (
                  <View style={[styles.doctorGrid, { marginTop: 10 }]}>
                    {visibleDoctors.map(d => {
                      const id = String(d.doctor_id);
                      const on = value === id;
                      return (
                        <TouchableOpacity
                          key={id}
                          style={[styles.doctorCard, on && styles.doctorCardActive]}
                          onPress={() => onChange(id)}
                          activeOpacity={0.8}
                        >
                          <View style={[styles.doctorAvatar, on && { borderColor: TEAL }]}>
                            <Text style={styles.doctorAvatarText}>{d.first_name[0]}</Text>
                          </View>
                          <View style={styles.doctorInfo}>
                            <Text style={[styles.doctorName, on && { color: TEAL }]}>{d.full_name}</Text>
                            <Text style={styles.doctorSpec}>{d.specialization ?? ''}</Text>
                          </View>
                          {on && <Ionicons name="checkmark-circle" size={20} color={TEAL} />}
                        </TouchableOpacity>
                      );
                    })}
                  </View>
                )}
              />
              {filteredDoctors.length > 3 && (
                <TouchableOpacity
                  style={styles.expandBtn}
                  onPress={() => setDoctorsExpanded(e => !e)}
                  activeOpacity={0.7}
                >
                  <Text style={styles.expandBtnText}>
                    {doctorsExpanded
                      ? 'Show less'
                      : `Show ${filteredDoctors.length - 3} more doctor${filteredDoctors.length - 3 > 1 ? 's' : ''}`}
                  </Text>
                  <Ionicons name={doctorsExpanded ? 'chevron-up' : 'chevron-down'} size={14} color={TEAL} />
                </TouchableOpacity>
              )}
            </>
          )}
          <FieldError message={errors.doctor_id?.message} />
        </View>

        {/* Date & Time */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Date & Time</Text>
          <View style={styles.twoCol}>
            <View style={{ flex: 1 }}>
              <Text style={styles.label}>Date</Text>
              <Controller control={control} name="scheduled_date"
                render={({ field: { onChange, value } }) => (
                  <View style={[styles.inputRow, !!errors.scheduled_date && styles.inputRowError]}>
                    <Ionicons name="calendar-outline" size={17} color={GRAY} style={styles.inputIcon} />
                    <TextInput
                      style={styles.input}
                      placeholder="YYYY-MM-DD"
                      placeholderTextColor={GRAY}
                      value={value}
                      onChangeText={onChange}
                      autoCorrect={false}
                    />
                  </View>
                )}
              />
              <FieldError message={errors.scheduled_date?.message} />
            </View>

            <View style={{ flex: 1 }}>
              <Text style={styles.label}>Time</Text>
              <Controller control={control} name="scheduled_time"
                render={({ field: { onChange, value } }) => (
                  <View style={[styles.inputRow, !!errors.scheduled_time && styles.inputRowError]}>
                    <Ionicons name="time-outline" size={17} color={GRAY} style={styles.inputIcon} />
                    <TextInput
                      style={styles.input}
                      placeholder="HH:MM (e.g. 14:30)"
                      placeholderTextColor={GRAY}
                      value={value}
                      onChangeText={onChange}
                      autoCorrect={false}
                    />
                  </View>
                )}
              />
              <FieldError message={errors.scheduled_time?.message} />
            </View>
          </View>
        </View>

        {/* Duration */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Duration</Text>
          <Controller control={control} name="duration_minutes"
            render={({ field: { onChange, value } }) => (
              <View style={styles.durationRow}>
                {DURATIONS.map(d => {
                  const on = value === d.value;
                  return (
                    <TouchableOpacity
                      key={d.value}
                      style={[styles.durationBtn, on && styles.durationBtnActive]}
                      onPress={() => onChange(d.value)}
                      activeOpacity={0.8}
                    >
                      <Text style={[styles.durationLabel, on && styles.durationLabelActive]}>{d.label}</Text>
                    </TouchableOpacity>
                  );
                })}
              </View>
            )}
          />
        </View>

        {/* Facility — only for IN_PERSON */}
        {watchedType === 'IN_PERSON' && (
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>Facility <Text style={styles.optional}>(optional)</Text></Text>
            <Controller control={control} name="facility_id"
              render={({ field: { onChange, value } }) => (
                <View style={styles.facilityList}>
                  {FACILITIES.map(f => {
                    const on = value === f.id;
                    return (
                      <TouchableOpacity
                        key={f.id}
                        style={[styles.facilityItem, on && styles.facilityItemActive]}
                        onPress={() => onChange(on ? '' : f.id)}
                        activeOpacity={0.8}
                      >
                        <Ionicons name="business-outline" size={16} color={on ? TEAL : GRAY} />
                        <Text style={[styles.facilityName, on && { color: TEAL, fontWeight: '600' }]}>{f.name}</Text>
                        {on && <Ionicons name="checkmark-circle" size={16} color={TEAL} />}
                      </TouchableOpacity>
                    );
                  })}
                </View>
              )}
            />
          </View>
        )}

        {/* Reason for visit */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Reason for Visit</Text>
          <Controller control={control} name="reason_for_visit"
            render={({ field: { onChange, value } }) => (
              <TextInput
                style={[styles.textarea, !!errors.reason_for_visit && styles.inputRowError]}
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
          <FieldError message={errors.reason_for_visit?.message} />
        </View>

        {/* Notes */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Additional Notes <Text style={styles.optional}>(optional)</Text></Text>
          <Controller control={control} name="notes"
            render={({ field: { onChange, value } }) => (
              <TextInput
                style={styles.textarea}
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
          <View style={styles.errorBanner}>
            <Ionicons name="alert-circle-outline" size={15} color={RED} />
            <Text style={styles.errorText}>{authError}</Text>
          </View>
        )}

        {/* Submit */}
        <TouchableOpacity
          style={[styles.submitBtn, { opacity: loading ? 0.7 : 1 }]}
          onPress={handleSubmit(onSubmit)}
          activeOpacity={0.85}
          disabled={loading}
        >
          <Ionicons name="calendar-outline" size={18} color={WHITE} />
          <Text style={styles.submitText}>{loading ? 'Booking…' : 'Book Appointment'}</Text>
        </TouchableOpacity>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  screen:       { flex: 1, flexDirection: 'row', backgroundColor: BG },

  formPane:     { flex: 1, backgroundColor: WHITE, borderRightWidth: 1, borderRightColor: BORDER },
  formPaneFull: { flex: 1, borderRightWidth: 0 },
  formScroll:   { flexGrow: 1, padding: 40, maxWidth: 680, alignSelf: 'center', width: '100%' },

  pageHeader: { flexDirection: 'row', alignItems: 'center', gap: 14, marginBottom: 32 },
  pageTitle:  { fontSize: 22, fontWeight: '800', color: TEXT },
  pageSub:    { fontSize: 13, color: GRAY, marginTop: 2 },

  section:      { marginBottom: 28 },
  sectionTitle: { fontSize: 14, fontWeight: '700', color: TEXT, marginBottom: 12 },
  optional:     { fontWeight: '400', color: GRAY },

  typeRow:  { flexDirection: 'row', gap: 10 },
  typeCard: {
    flex: 1, alignItems: 'center', gap: 5, paddingVertical: 14,
    borderWidth: 1.5, borderColor: BORDER, borderRadius: 12, position: 'relative',
  },
  typeCheck:  { position: 'absolute', top: -7, right: -7, width: 18, height: 18, borderRadius: 9, alignItems: 'center', justifyContent: 'center' },
  typeLabel:  { fontSize: 12, fontWeight: '600', color: GRAY },
  typeDesc:   { fontSize: 10, color: GRAY, textAlign: 'center' },

  doctorSearchRow:  { marginBottom: 0 },
  doctorGrid:       { gap: 8 },
  expandBtn:        { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6, paddingVertical: 10, marginTop: 6, borderWidth: 1, borderColor: TEAL, borderRadius: 10, backgroundColor: '#E6F4F1' },
  expandBtnText:    { fontSize: 13, fontWeight: '600', color: TEAL },
  doctorCard:       { flexDirection: 'row', alignItems: 'center', gap: 12, padding: 12, borderWidth: 1.5, borderColor: BORDER, borderRadius: 12, backgroundColor: WHITE },
  doctorCardActive: { borderColor: TEAL, backgroundColor: '#E6F4F1' },
  doctorAvatar:     { width: 40, height: 40, borderRadius: 20, backgroundColor: '#E6F4F1', alignItems: 'center', justifyContent: 'center', borderWidth: 2, borderColor: BORDER },
  doctorAvatarText: { fontSize: 16, fontWeight: '700', color: TEAL },
  doctorInfo:       { flex: 1 },
  doctorName:       { fontSize: 14, fontWeight: '600', color: TEXT },
  doctorSpec:       { fontSize: 12, color: GRAY, marginTop: 2 },

  twoCol: { flexDirection: 'row', gap: 12 },
  label:  { fontSize: 13, fontWeight: '600', color: TEXT, marginBottom: 8 },
  inputRow: {
    flexDirection: 'row', alignItems: 'center',
    borderWidth: 1.5, borderColor: BORDER, borderRadius: 12,
    paddingHorizontal: 14, paddingVertical: 13, backgroundColor: BG, marginBottom: 4,
  },
  inputRowError: { borderColor: RED },
  inputIcon:     { marginRight: 10 },
  input:         { flex: 1, fontSize: 14, color: TEXT, outlineStyle: 'none' } as any,

  durationRow:        { flexDirection: 'row', gap: 8 },
  durationBtn:        { flex: 1, paddingVertical: 10, borderWidth: 1.5, borderColor: BORDER, borderRadius: 10, alignItems: 'center' },
  durationBtnActive:  { borderColor: TEAL, backgroundColor: '#E6F4F1' },
  durationLabel:      { fontSize: 13, fontWeight: '600', color: GRAY },
  durationLabelActive:{ color: TEAL },

  facilityList:     { gap: 8 },
  facilityItem:     { flexDirection: 'row', alignItems: 'center', gap: 10, padding: 12, borderWidth: 1.5, borderColor: BORDER, borderRadius: 12 },
  facilityItemActive:{ borderColor: TEAL, backgroundColor: '#E6F4F1' },
  facilityName:     { flex: 1, fontSize: 14, color: TEXT },

  textarea: {
    borderWidth: 1.5, borderColor: BORDER, borderRadius: 12,
    padding: 14, fontSize: 14, color: TEXT, backgroundColor: BG,
    minHeight: 100, outlineStyle: 'none',
  } as any,

  fieldError:     { flexDirection: 'row', alignItems: 'center', gap: 4, marginTop: 4 },
  fieldErrorText: { fontSize: 12, color: RED },

  errorBanner: { flexDirection: 'row', alignItems: 'center', gap: 8, padding: 12, backgroundColor: '#FEF2F2', borderRadius: 10, marginBottom: 12 },
  errorText:   { flex: 1, fontSize: 13, color: RED },

  submitBtn:  { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8, backgroundColor: TEAL, borderRadius: 12, paddingVertical: 16, marginBottom: 20 },
  submitText: { fontSize: 16, fontWeight: '700', color: WHITE },

  successScreen: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: BG },
  successCard:   { backgroundColor: WHITE, borderRadius: 20, padding: 48, alignItems: 'center', maxWidth: 440, margin: 24, shadowColor: '#000', shadowOpacity: 0.08, shadowRadius: 20 } as any,
  successIcon:   { marginBottom: 20 },
  successTitle:  { fontSize: 24, fontWeight: '800', color: TEXT, marginBottom: 12 },
  successSub:    { fontSize: 14, color: GRAY, textAlign: 'center', lineHeight: 22, marginBottom: 28 },
  successBtn:    { backgroundColor: TEAL, borderRadius: 12, paddingVertical: 14, paddingHorizontal: 32 },
  successBtnText:{ fontSize: 15, fontWeight: '700', color: WHITE },

  summaryPane:   { width: 340, backgroundColor: NAVY, overflow: 'hidden' },
  summaryInner:  { flex: 1, padding: 36, gap: 20 },
  summaryHeader: { flexDirection: 'row', alignItems: 'center', gap: 10, marginBottom: 4 },
  summaryBrand:  { fontSize: 20, fontWeight: '800', color: WHITE },
  summaryTitle:  { fontSize: 22, fontWeight: '800', color: WHITE, lineHeight: 30 },

  summaryCard:     { backgroundColor: 'rgba(255,255,255,0.08)', borderRadius: 14, padding: 16, gap: 14 },
  summaryRow:      { flexDirection: 'row', alignItems: 'flex-start', gap: 10 },
  summaryRowIcon:  { width: 28, height: 28, borderRadius: 8, backgroundColor: 'rgba(13,148,136,0.2)', alignItems: 'center', justifyContent: 'center' },
  summaryRowText:  { flex: 1 },
  summaryRowLabel: { fontSize: 11, color: 'rgba(255,255,255,0.5)', fontWeight: '600', textTransform: 'uppercase', letterSpacing: 0.5 },
  summaryRowValue: { fontSize: 14, color: WHITE, fontWeight: '600', marginTop: 2 },

  infoBox:   { flexDirection: 'row', alignItems: 'flex-start', gap: 10, backgroundColor: 'rgba(13,148,136,0.15)', borderRadius: 10, padding: 12 },
  infoText:  { flex: 1, fontSize: 12, color: 'rgba(255,255,255,0.75)', lineHeight: 18 },

  featureList: { gap: 12 },
  featureRow:  { flexDirection: 'row', alignItems: 'center', gap: 10 },
  featureIconBg:{ width: 28, height: 28, borderRadius: 8, backgroundColor: 'rgba(255,255,255,0.1)', alignItems: 'center', justifyContent: 'center' },
  featureText: { fontSize: 13, color: 'rgba(255,255,255,0.8)', fontWeight: '500' },
});

export default PatientWebAppointment;
