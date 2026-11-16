import { StyleSheet, View } from 'react-native';
import { SelectField, type SelectOption } from '@/components/web/ui';
import type { CorrectionStatus } from '@/lib/corrections/types';

export type CorrectionStatusFilter = CorrectionStatus | 'ALL';

const OPTIONS: readonly SelectOption<CorrectionStatusFilter>[] = [
  { value: 'ALL',      label: 'All requests' },
  { value: 'OPEN',     label: 'Open' },
  { value: 'ACCEPTED', label: 'Accepted' },
  { value: 'REJECTED', label: 'Rejected' },
];

/** The status choice above a list of correction requests. The value goes to the server as the `status` filter. */
export function CorrectionStatusSelect({ value, onChange }: {
  value:    CorrectionStatusFilter;
  onChange: (value: CorrectionStatusFilter) => void;
}) {
  return (
    <View style={styles.wrap}>
      <SelectField<CorrectionStatusFilter>
        label="Show"
        value={value}
        onChange={next => onChange(next || 'ALL')}
        options={OPTIONS}
      />
    </View>
  );
}

/** The `status` query value of a filter choice: null lists every status. */
export function statusOfFilter(filter: CorrectionStatusFilter): CorrectionStatus | null {
  return filter === 'ALL' ? null : filter;
}

/** A request id from a URL param or a prop; null when it is not a positive whole number. */
export function toRequestId(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null;
  const id = typeof value === 'number' ? value : Number(String(value).trim());
  return Number.isInteger(id) && id > 0 ? id : null;
}

const styles = StyleSheet.create({
  wrap: { width: 220, maxWidth: '100%' },
});
