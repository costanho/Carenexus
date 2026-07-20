import { Ionicons } from '@expo/vector-icons';
import { zodResolver } from '@hookform/resolvers/zod';
import { useRouter } from 'expo-router';
import { useState } from 'react';
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

          {/* Search */}
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

        {/* Date & Time */}
        <View>
          <Text style={[styles.sectionTitle, { fontSize: s(12), marginBottom: s(10) }]}>Date & Time</Text>
          <View style={[styles.twoCol, { gap: s(10) }]}>
            <View style={{ flex: 1 }}>
              <Text style={[styles.label, { fontSize: s(12), marginBottom: s(6) }]}>Date</Text>
              <Controller control={control} name="scheduled_date"
                render={({ field: { onChange, value } }) => (
                  <View style={[styles.inputRow, { borderRadius: s(10), paddingHorizontal: s(12), paddingVertical: s(12) }, !!errors.scheduled_date && styles.inputRowError]}>
                    <Ionicons name="calendar-outline" size={s(16)} color={GRAY} style={{ marginRight: s(8) }} />
                    <TextInput
                      style={[styles.input, { fontSize: s(13) }]}
                      placeholder="YYYY-MM-DD"
                      placeholderTextColor={GRAY}
                      value={value}
                      onChangeText={onChange}
                      autoCorrect={false}
                    />
                  </View>
                )}
              />
              {!!errors.scheduled_date && <FieldError message={errors.scheduled_date.message} s={s} />}
            </View>
            <View style={{ flex: 1 }}>
              <Text style={[styles.label, { fontSize: s(12), marginBottom: s(6) }]}>Time</Text>
              <Controller control={control} name="scheduled_time"
                render={({ field: { onChange, value } }) => (
                  <View style={[styles.inputRow, { borderRadius: s(10), paddingHorizontal: s(12), paddingVertical: s(12) }, !!errors.scheduled_time && styles.inputRowError]}>
                    <Ionicons name="time-outline" size={s(16)} color={GRAY} style={{ marginRight: s(8) }} />
                    <TextInput
                      style={[styles.input, { fontSize: s(13) }]}
                      placeholder="HH:MM"
                      placeholderTextColor={GRAY}
                      value={value}
                      onChangeText={onChange}
                      autoCorrect={false}
                    />
                  </View>
                )}
              />
              {!!errors.scheduled_time && <FieldError message={errors.scheduled_time.message} s={s} />}
            </View>
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
