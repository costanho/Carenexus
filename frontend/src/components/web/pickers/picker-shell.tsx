import { useMemo, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { Banner, FormField, SelectField, space, TextField } from '@/components/web/ui';

// Shared body of the pickers: a labelled select fed by a list hook, with the loading, failed and empty states
// and a search box once the list is long.

/** Lists longer than this get a search box above the select. */
const SEARCH_THRESHOLD = 12;

export interface PickerOption<I> {
  /** The system id that becomes the picker's value. */
  id:    string;
  label: string;
  item:  I;
}

/** Props shared by PatientPicker, DoctorPicker and DependentPicker. */
export interface PickerProps<I> {
  /** The selected system id, or null when nothing is selected. */
  value:     string | null | undefined;
  /** Called with the chosen system id and its row; `(null, null)` when the choice is cleared. */
  onChange:  (systemId: string | null, item: I | null) => void;
  disabled?: boolean;
  /** Narrows the list, for example to people the action applies to. */
  filter?:   (item: I) => boolean;
  /** Visible label. Each picker has a default ("Patient", "Doctor", "Loved one"). */
  label?:    string;
  hint?:     string;
  error?:    string | null;
  required?: boolean;
  /** Text of the empty choice. */
  placeholder?: string;
  /** Lets the person clear the choice. */
  allowEmpty?:  boolean;
  testID?:   string;
}

interface ShellProps<I> extends Omit<PickerProps<I>, 'filter' | 'label'> {
  label:     string;
  options:   PickerOption<I>[];
  loading:   boolean;
  loadError: string | null;
  onRetry:   () => void;
  /** Shown in the disabled select when there is nobody to choose. */
  emptyText: string;
  /** Label of the search box, for example "Search patients". */
  searchLabel: string;
}

export function PickerShell<I>({
  label, value, onChange, options: allOptions, loading, loadError, onRetry, emptyText, searchLabel,
  disabled = false, hint, error, required, placeholder, allowEmpty = false, testID,
}: ShellProps<I>) {
  const [query, setQuery] = useState('');
  // One option per system id (a person can appear in the source list more than once).
  const options = useMemo(() => {
    const seen = new Set<string>();
    return allOptions.filter(o => (seen.has(o.id) ? false : (seen.add(o.id), true)));
  }, [allOptions]);
  const searchable = options.length > SEARCH_THRESHOLD;

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!searchable || !q) return options;
    // The current choice stays in the list so the select never shows a different person than the value.
    return options.filter(o => o.id === value || o.label.toLowerCase().includes(q));
  }, [options, query, searchable, value]);

  if (loadError && options.length === 0) {
    return (
      <FormField label={label} required={required} error={error}>
        <Banner tone="danger" text={loadError} action={{ label: 'Retry', onPress: onRetry }} />
      </FormField>
    );
  }

  const nobody = !loading && options.length === 0;
  const noMatch = searchable && visible.length === 0;
  const byId = new Map(options.map(o => [o.id, o] as const));

  return (
    <View style={styles.wrap}>
      {searchable ? (
        <TextField
          label={searchLabel}
          value={query}
          onChange={setQuery}
          placeholder="Type a name"
          disabled={disabled}
          hint={noMatch ? 'No names match. Clear the search to see everyone.' : undefined}
        />
      ) : null}
      <SelectField
        label={label}
        value={value ?? ''}
        onChange={id => onChange(id || null, id ? byId.get(id)?.item ?? null : null)}
        options={visible.map(o => ({ value: o.id, label: o.label }))}
        placeholder={loading ? 'Loading…' : nobody ? emptyText : placeholder ?? `Select ${label.toLowerCase()}`}
        allowEmpty={allowEmpty && !loading && !nobody}
        disabled={disabled || loading || nobody}
        required={required}
        hint={hint}
        error={error}
        testID={testID}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: space.sm, minWidth: 0, maxWidth: '100%' },
});
