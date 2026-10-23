import { useEffect, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { useCurrentUser } from './use-current-user';

export interface ImagingResult {
  imaging_id:            number;
  patient_id:            number;
  doctor_id:             number;
  consultation_id:       number | null;
  imaging_type:          'XRAY' | 'MRI' | 'CT_SCAN' | 'ULTRASOUND' | 'PET' | 'MAMMOGRAPHY' | 'DEXA' | 'ECHO';
  body_part:             string | null;
  radiologist_report:    string | null;
  findings:              string | null;
  impression:            string | null;
  file_url:              string | null;
  thumbnail_url:         string | null;
  status:                'PENDING' | 'COMPLETED' | 'REQUIRES_REVIEW' | 'CRITICAL';
  image_date:            string;
  reviewed_by:           number | null;
  reviewed_at:           string | null;
  created_at:            string;
  doctor_name:           string;
  doctor_specialization: string | null;
  doctor_avatar_url:     string | null;
  reviewer_name:         string | null;
  patient_name:          string;
}

interface State {
  results:  ImagingResult[];
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
}

export function useImagingResults(): State {
  const { user } = useCurrentUser();
  const [results, setResults] = useState<ImagingResult[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError]     = useState<string | null>(null);
  const [tick, setTick]       = useState(0);

  useEffect(() => {
    if (!user) return;
    setLoading(true);

    Promise.all([
      api.get<any[]>(`/api/imaging-results/patient/${user.system_id}`),
      api.get<any[]>('/api/doctors/profile/list/active'),
    ])
      .then(([imagingRes, doctorsRes]) => {
        const doctorMap = new Map<string, { name: string; specialization: string | null }>();
        doctorsRes.data.forEach((d: any) => {
          const systemId = d.doctorSystemId ?? d.doctor_system_id;
          if (!systemId) return;
          const name = `Dr. ${d.firstName ?? d.first_name ?? ''} ${d.lastName ?? d.last_name ?? ''}`.trim();
          doctorMap.set(systemId, { name, specialization: d.specialization ?? null });
        });

        const mapped: ImagingResult[] = imagingRes.data.map((r: any) => {
          const doctorSystemId = r.doctorSystemId ?? '';
          const doctor = doctorMap.get(doctorSystemId);
          return {
            imaging_id:            r.imagingId            ?? 0,
            patient_id:            0,
            doctor_id:             0,
            consultation_id:       r.consultationId       ?? null,
            imaging_type:          (r.imagingType         ?? 'XRAY') as ImagingResult['imaging_type'],
            body_part:             r.bodyPart             ?? null,
            radiologist_report:    r.radiologistReport    ?? null,
            findings:              r.findings             ?? null,
            impression:            r.impression           ?? null,
            file_url:              r.fileUrl              ?? null,
            thumbnail_url:         r.thumbnailUrl         ?? null,
            status:                (r.status              ?? 'PENDING') as ImagingResult['status'],
            image_date:            r.imageDate            ? String(r.imageDate) : '',
            reviewed_by:           null,
            reviewed_at:           r.reviewedAt           ? String(r.reviewedAt) : null,
            created_at:            r.createdAt            ? String(r.createdAt) : '',
            doctor_name:           doctor?.name           ?? (doctorSystemId || 'Unknown Doctor'),
            doctor_specialization: doctor?.specialization ?? null,
            doctor_avatar_url:     null,
            reviewer_name:         r.reviewedBy           ?? null,
            patient_name:          `${user.first_name} ${user.last_name}`.trim() || 'Unknown Patient',
          };
        });

        mapped.sort((a, b) => new Date(b.image_date).getTime() - new Date(a.image_date).getTime());
        setResults(mapped);
        setError(null);
      })
      .catch(() => setError('Failed to load imaging results.'))
      .finally(() => setLoading(false));
  }, [user?.system_id, tick]);

  return { results, loading, error, refetch: () => setTick(t => t + 1) };
}
