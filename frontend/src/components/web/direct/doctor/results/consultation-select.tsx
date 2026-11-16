import { useMemo } from 'react';
import { Banner, FormField, SelectField, type SelectOption, statusLabel } from '@/components/web/ui';
import { useResource } from '@/hooks/use-resource';
import { fetchPage } from '@/lib/api/paged';
import { formatClinicDate } from '@/lib/time/clinic';

// The optional "Consultation" field of the record forms: the chosen patient's consultations from the canonical
// paged list GET /api/consultations?patientSystemId= (api/be-consultations.md 2.2), newest first.

/** The fields of a consultation row this picker reads. */
interface ConsultationRow {
  consultationId: number;
  status:         string;
  createdAt:      string;
  chiefComplaint: string | null;
  diagnosis:      string | null;
}

/** Consultations offered; a patient with more narrows by recording from the consultation itself. */
const LIST_SIZE = 50;

function summary(row: ConsultationRow): string {
  const words = (row.diagnosis ?? row.chiefComplaint ?? '').replace(/\s+/g, ' ').trim();
  const short = words.length > 60 ? `${words.slice(0, 57).trimEnd()}…` : words;
  const date = formatClinicDate(row.createdAt, 'Undated');
  const state = row.status === 'COMPLETED' ? '' : ` (${statusLabel(row.status)})`;
  return `${date}${short ? `: ${short}` : ''}${state}`;
}

export interface ConsultationSelectProps {
  /** The patient whose consultations are listed; the field is disabled until one is chosen. */
  patientSystemId: string | null;
  /** The chosen consultation id as text, or '' for none. */
  value:    string;
  onChange: (value: string) => void;
  onBlur?:  () => void;
  error?:   string | null;
  disabled?: boolean;
}

export function ConsultationSelect({ patientSystemId, value, onChange, onBlur, error, disabled = false }: ConsultationSelectProps) {
  const patient = patientSystemId || '';
  const { data, loading, error: loadError, refetch } = useResource(
    () => fetchPage<ConsultationRow>('/api/consultations', 0, LIST_SIZE, { patientSystemId: patient }),
    [patient],
    { enabled: !!patient, errorMessage: 'Failed to load consultations.' },
  );

  const options = useMemo<SelectOption[]>(() => {
    const rows = (data?.content ?? []).filter(row => row.status !== 'CANCELLED' || String(row.consultationId) === value);
    const out = rows.map(row => ({ value: String(row.consultationId), label: summary(row) }));
    // A consultation handed over by a link (or one beyond the listed rows) stays selectable.
    if (value && !loading && !out.some(o => o.value === value)) out.unshift({ value, label: `Consultation #${value}` });
    return out;
  }, [data, value, loading]);

  if (patient && loadError && !data) {
    return (
      <FormField label="Consultation" error={error} hint="You can save the result without linking a consultation.">
        <Banner tone="danger" text={loadError} action={{ label: 'Retry', onPress: refetch }} />
      </FormField>
    );
  }

  const none = !!patient && !loading && options.length === 0;
  const more = (data?.totalElements ?? 0) > (data?.content.length ?? 0);
  return (
    <SelectField
      label="Consultation"
      value={value}
      onChange={onChange}
      onBlur={onBlur}
      options={options}
      allowEmpty={!!patient && !loading && !none}
      disabled={disabled || !patient || loading || none}
      placeholder={
        !patient ? 'Choose a patient first'
          : loading ? 'Loading…'
            : none ? 'No consultations with this patient'
              : 'Not linked to a consultation'
      }
      hint={more ? `Optional. Showing the ${LIST_SIZE} most recent consultations.` : 'Optional. Links the result to a visit.'}
      error={error}
    />
  );
}
