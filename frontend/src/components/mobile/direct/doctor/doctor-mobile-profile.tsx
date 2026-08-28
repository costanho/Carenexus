import { Ionicons } from '@expo/vector-icons';
import { Image, ActivityIndicator, ScrollView, Text, TouchableOpacity, View, useWindowDimensions } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useDoctorProfile } from '@/hooks/use-doctor-profile';

const NAVY   = '#0D1B2E';
const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const LGRAY  = '#9CA3AF';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const BLUE   = '#4F46E5';
const GREEN  = '#10B981';
const AMBER  = '#F59E0B';
const PURPLE = '#8B5CF6';
const RED    = '#EF4444';
const TEAL   = '#2DD4BF';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

const AVATAR_COLORS = ['#4F46E5', '#0D9488', '#F59E0B', '#EF4444', '#8B5CF6', '#10B981', '#0EA5E9'];
function avatarColor(name: string) {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) & 0xffff;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}
function initials(name: string) {
  return name.split(' ').filter(Boolean).map(w => w[0]).join('').slice(0, 2).toUpperCase();
}
function formatDate(iso: string): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric' });
}
function memberSince(iso: string): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-GB', { month: 'long', year: 'numeric' });
}

export default function DoctorMobileProfile() {
  const { profile, stats, loading, error, refetch } = useDoctorProfile();
  const insets = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();

  const s  = (n: number) => Math.round(n * (width  / 390));
  const sh = (n: number) => Math.round(n * (height / 844));

  if (loading) {
    return (
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
        <ActivityIndicator size="large" color={BLUE} />
      </View>
    );
  }

  if (error || !profile) {
    return (
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', gap: s(12), padding: s(24) }}>
        <Ionicons name="alert-circle-outline" size={s(48)} color={RED} />
        <Text style={{ fontSize: s(14), color: GRAY, textAlign: 'center' }}>{error ?? 'Profile not found'}</Text>
        <TouchableOpacity
          style={{ backgroundColor: BLUE, borderRadius: s(8), paddingHorizontal: s(20), paddingVertical: sh(10) }}
          onPress={refetch}
          activeOpacity={0.8}
        >
          <Text style={{ color: WHITE, fontWeight: '600', fontSize: s(14) }}>Retry</Text>
        </TouchableOpacity>
      </View>
    );
  }

  const ac = avatarColor(profile.full_name);

  // ─── Info Row helper ────────────────────────────────────────────────────────
  function InfoRow({ icon, label, value, mono }: { icon: IoniconsName; label: string; value: string; mono?: boolean }) {
    return (
      <View style={{
        flexDirection: 'row', alignItems: 'flex-start', gap: s(10),
        paddingVertical: sh(10),
        borderBottomWidth: 1, borderBottomColor: BG,
      }}>
        <View style={{
          width: s(30), height: s(30), borderRadius: s(8),
          backgroundColor: '#EEF2FF', alignItems: 'center', justifyContent: 'center',
          marginTop: 1,
        }}>
          <Ionicons name={icon} size={s(14)} color={BLUE} />
        </View>
        <View style={{ flex: 1 }}>
          <Text style={{ fontSize: s(10), color: LGRAY, textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 2 }}>
            {label}
          </Text>
          <Text style={{ fontSize: s(13), color: TEXT, fontWeight: '500', fontFamily: mono ? 'monospace' : undefined }}>
            {value}
          </Text>
        </View>
      </View>
    );
  }

  return (
    <View style={{ flex: 1, backgroundColor: BG }}>
      <ScrollView
        style={{ flex: 1 }}
        showsVerticalScrollIndicator={false}
        contentContainerStyle={{ paddingBottom: sh(40) }}
      >

        {/* Hero banner */}
        <View style={{
          backgroundColor: NAVY,
          paddingTop: insets.top + sh(20),
          paddingBottom: sh(28),
          paddingHorizontal: s(20),
          overflow: 'hidden',
        }}>
          {/* Decorative circle */}
          <View style={{
            position: 'absolute',
            top: -s(30), right: -s(30),
            width: s(160), height: s(160),
            borderRadius: s(80),
            backgroundColor: 'rgba(79,110,247,0.12)',
          }} />

          {/* Refresh button */}
          <TouchableOpacity
            style={{
              position: 'absolute',
              top: insets.top + sh(14),
              right: s(16),
              width: s(34), height: s(34), borderRadius: s(17),
              backgroundColor: 'rgba(255,255,255,0.1)',
              alignItems: 'center', justifyContent: 'center',
            }}
            onPress={refetch}
            activeOpacity={0.8}
          >
            <Ionicons name="refresh-outline" size={s(16)} color={WHITE} />
          </TouchableOpacity>

          {/* Avatar */}
          <View style={{ alignItems: 'center', marginBottom: sh(16) }}>
            <View style={{
              width: s(90), height: s(90),
              borderRadius: s(45),
              borderWidth: 3, borderColor: 'rgba(45,212,191,0.6)',
              overflow: 'hidden',
            }}>
              {profile.avatar_url ? (
                <Image source={{ uri: profile.avatar_url }} style={{ width: '100%', height: '100%' }} />
              ) : (
                <View style={{ flex: 1, backgroundColor: ac, alignItems: 'center', justifyContent: 'center' }}>
                  <Text style={{ color: WHITE, fontSize: s(28), fontWeight: '700' }}>{initials(profile.full_name)}</Text>
                </View>
              )}
            </View>
          </View>

          {/* Name + status */}
          <View style={{ alignItems: 'center', gap: sh(6) }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(8), flexWrap: 'wrap', justifyContent: 'center' }}>
              <Text style={{ fontSize: s(20), fontWeight: '700', color: WHITE, textAlign: 'center' }}>
                {profile.full_name}
              </Text>
              {!!profile.is_active && (
                <View style={{
                  flexDirection: 'row', alignItems: 'center', gap: s(4),
                  backgroundColor: 'rgba(16,185,129,0.2)',
                  borderRadius: s(20), paddingHorizontal: s(8), paddingVertical: sh(2),
                  borderWidth: 1, borderColor: 'rgba(16,185,129,0.4)',
                }}>
                  <View style={{ width: s(5), height: s(5), borderRadius: s(3), backgroundColor: GREEN }} />
                  <Text style={{ fontSize: s(10), fontWeight: '600', color: GREEN }}>Active</Text>
                </View>
              )}
            </View>

            <Text style={{ fontSize: s(13), color: '#8BA3BE', fontWeight: '500' }}>
              {profile.specialization}
            </Text>

            <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(6), flexWrap: 'wrap', justifyContent: 'center' }}>
              <Ionicons name="shield-checkmark-outline" size={s(12)} color="rgba(255,255,255,0.5)" />
              <Text style={{ fontSize: s(11), color: 'rgba(255,255,255,0.5)' }}>{profile.license_no}</Text>
              <Text style={{ fontSize: s(11), color: 'rgba(255,255,255,0.3)' }}>·</Text>
              <Ionicons name="calendar-outline" size={s(12)} color="rgba(255,255,255,0.5)" />
              <Text style={{ fontSize: s(11), color: 'rgba(255,255,255,0.5)' }}>Since {memberSince(profile.created_at)}</Text>
            </View>
          </View>
        </View>

        {/* Stats strip */}
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={{
            paddingHorizontal: s(16),
            paddingTop: sh(16),
            paddingBottom: sh(4),
            gap: s(10),
            alignItems: 'flex-start',
          }}
        >
          {([
            { icon: 'calendar-number-outline' as IoniconsName, iconBg: '#EEF2FF', iconColor: BLUE,   label: 'Appointments',  value: stats.appointments  },
            { icon: 'people-outline'           as IoniconsName, iconBg: '#D1FAE5', iconColor: GREEN,  label: 'Patients',      value: stats.patients      },
            { icon: 'videocam-outline'         as IoniconsName, iconBg: '#F5F3FF', iconColor: PURPLE, label: 'Consultations', value: stats.consultations },
            { icon: 'medkit-outline'           as IoniconsName, iconBg: '#FEF3C7', iconColor: AMBER,  label: 'Prescriptions', value: stats.prescriptions },
          ] as const).map(chip => (
            <View key={chip.label} style={{
              backgroundColor: WHITE, borderRadius: s(12), borderWidth: 1, borderColor: BORDER,
              paddingHorizontal: s(14), paddingVertical: sh(8),
              alignItems: 'center', gap: sh(4), minWidth: s(86), alignSelf: 'flex-start',
            }}>
              <View style={{
                width: s(32), height: s(32), borderRadius: s(9),
                backgroundColor: chip.iconBg, alignItems: 'center', justifyContent: 'center',
              }}>
                <Ionicons name={chip.icon} size={s(17)} color={chip.iconColor} />
              </View>
              <Text style={{ fontSize: s(22), fontWeight: '700', color: TEXT }}>{chip.value}</Text>
              <Text style={{ fontSize: s(10), color: GRAY, textAlign: 'center' }}>{chip.label}</Text>
            </View>
          ))}
        </ScrollView>

        {/* About */}
        {!!profile.bio && (
          <View style={{
            backgroundColor: WHITE, borderRadius: s(14), borderWidth: 1, borderColor: BORDER,
            marginHorizontal: s(16), marginTop: sh(14), padding: s(16), gap: sh(10),
          }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(10) }}>
              <View style={{ width: s(32), height: s(32), borderRadius: s(9), backgroundColor: '#EEF2FF', alignItems: 'center', justifyContent: 'center' }}>
                <Ionicons name="person-outline" size={s(15)} color={BLUE} />
              </View>
              <Text style={{ fontSize: s(14), fontWeight: '700', color: TEXT }}>About</Text>
            </View>
            <Text style={{ fontSize: s(13), color: GRAY, lineHeight: s(20) }}>{profile.bio}</Text>
          </View>
        )}

        {/* Contact Information */}
        <View style={{
          backgroundColor: WHITE, borderRadius: s(14), borderWidth: 1, borderColor: BORDER,
          marginHorizontal: s(16), marginTop: sh(14), padding: s(16), gap: sh(4),
        }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(10), marginBottom: sh(6) }}>
            <View style={{ width: s(32), height: s(32), borderRadius: s(9), backgroundColor: '#D1FAE5', alignItems: 'center', justifyContent: 'center' }}>
              <Ionicons name="call-outline" size={s(15)} color={GREEN} />
            </View>
            <Text style={{ fontSize: s(14), fontWeight: '700', color: TEXT }}>Contact Information</Text>
          </View>
          <InfoRow icon="mail-outline" label="Email Address" value={profile.email} />
          <InfoRow icon="call-outline" label="Phone Number"  value={profile.phone ?? 'Not provided'} />
        </View>

        {/* Professional Details */}
        <View style={{
          backgroundColor: WHITE, borderRadius: s(14), borderWidth: 1, borderColor: BORDER,
          marginHorizontal: s(16), marginTop: sh(14), padding: s(16), gap: sh(4),
        }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(10), marginBottom: sh(6) }}>
            <View style={{ width: s(32), height: s(32), borderRadius: s(9), backgroundColor: '#F5F3FF', alignItems: 'center', justifyContent: 'center' }}>
              <Ionicons name="ribbon-outline" size={s(15)} color={PURPLE} />
            </View>
            <Text style={{ fontSize: s(14), fontWeight: '700', color: TEXT }}>Professional Details</Text>
          </View>
          <InfoRow icon="briefcase-outline"        label="Specialization" value={profile.specialization}          />
          <InfoRow icon="shield-checkmark-outline" label="License Number" value={profile.license_no}  mono        />
          <InfoRow icon="calendar-outline"         label="Member Since"   value={memberSince(profile.created_at)} />
          <InfoRow icon="checkmark-circle-outline" label="Status"         value={profile.is_active ? 'Active' : 'Inactive'} />
        </View>

        {/* Account Details */}
        <View style={{
          backgroundColor: WHITE, borderRadius: s(14), borderWidth: 1, borderColor: BORDER,
          marginHorizontal: s(16), marginTop: sh(14), padding: s(16), gap: sh(4),
        }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: s(10), marginBottom: sh(6) }}>
            <View style={{ width: s(32), height: s(32), borderRadius: s(9), backgroundColor: '#FEF3C7', alignItems: 'center', justifyContent: 'center' }}>
              <Ionicons name="information-circle-outline" size={s(15)} color={AMBER} />
            </View>
            <Text style={{ fontSize: s(14), fontWeight: '700', color: TEXT }}>Account Details</Text>
          </View>
          <InfoRow icon="finger-print-outline" label="Doctor ID" value={`#${profile.doctor_id}`} mono />
          <InfoRow icon="person-outline"       label="User ID"   value={`#${profile.user_id}`}   mono />
          <InfoRow icon="time-outline"         label="Joined"    value={formatDate(profile.created_at)} />
        </View>

      </ScrollView>
    </View>
  );
}
