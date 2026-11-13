import type { IoniconsName, SelectOption } from '@/components/web/ui';
import type { RecordType } from '@/hooks/use-doctor-medical-records';
import { RECORD_TYPES } from '@/lib/validation/medical-record';

// Words, icons and colours of the eight record types. Every colour pair meets 4.5:1; a type is always shown
// with its label, never by colour alone.

export interface RecordTypeMeta {
  /** One record: "Lab result". */
  label:  string;
  /** The filter and the count: "Lab results". */
  plural: string;
  icon:   IoniconsName;
  fg:     string;
  bg:     string;
  border: string;
}

export const RECORD_TYPE_META: Record<RecordType, RecordTypeMeta> = {
  CONSULT:      { label: 'Consultation', plural: 'Consultations', icon: 'medical-outline',       fg: '#4338CA', bg: '#EEF2FF', border: '#C7D2FE' },
  LAB:          { label: 'Lab result',   plural: 'Lab results',   icon: 'flask-outline',         fg: '#0F766E', bg: '#F0FDFA', border: '#99F6E4' },
  IMAGING:      { label: 'Imaging',      plural: 'Imaging',       icon: 'image-outline',         fg: '#6D28D9', bg: '#F5F3FF', border: '#C4B5FD' },
  PRESCRIPTION: { label: 'Prescription', plural: 'Prescriptions', icon: 'medkit-outline',        fg: '#047857', bg: '#ECFDF5', border: '#A7F3D0' },
  REFERRAL:     { label: 'Referral',     plural: 'Referrals',     icon: 'git-compare-outline',   fg: '#1E3A5F', bg: '#EFF6FF', border: '#BFDBFE' },
  DISCHARGE:    { label: 'Discharge',    plural: 'Discharges',    icon: 'exit-outline',          fg: '#334155', bg: '#F1F5F9', border: '#CBD5E1' },
  NOTE:         { label: 'Note',         plural: 'Notes',         icon: 'document-text-outline', fg: '#92400E', bg: '#FFFBEB', border: '#FDE68A' },
  OPERATION:    { label: 'Operation',    plural: 'Operations',    icon: 'cut-outline',           fg: '#B91C1C', bg: '#FEF2F2', border: '#FECACA' },
};

const UNKNOWN_TYPE: RecordTypeMeta = {
  label: 'Record', plural: 'Records', icon: 'document-outline', fg: '#374151', bg: '#F3F4F6', border: '#D1D5DB',
};

/** Meta of a type; a value the app does not know (a type added on the server later) reads "Record". */
export function recordTypeMeta(type: string | null | undefined): RecordTypeMeta {
  return RECORD_TYPE_META[type as RecordType] ?? UNKNOWN_TYPE;
}

/** Options of the "Record type" select, in the order of the backend enum. */
export const RECORD_TYPE_OPTIONS: readonly SelectOption<RecordType>[] =
  RECORD_TYPES.map(type => ({ value: type, label: RECORD_TYPE_META[type].label }));
