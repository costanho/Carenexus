import { useMemo } from 'react';
import { StyleSheet, View } from 'react-native';
import { SelectField, type SelectOption } from '@/components/web/ui';
import { DOCUMENT_TYPE_LABELS, DOCUMENT_TYPES, type DocumentType } from '@/lib/documents/types';

// The "Document type" filter above a document list. The empty choice lists every type.

export interface DocumentTypeFilterProps {
  /** The selected type, or null for all types. */
  value:     DocumentType | null;
  onChange:  (type: DocumentType | null) => void;
  /** The types to offer. Default all eight; a caregiver panel passes only the types the grant may read. */
  types?:    readonly DocumentType[];
  disabled?: boolean;
}

export function DocumentTypeFilter({ value, onChange, types = DOCUMENT_TYPES, disabled }: DocumentTypeFilterProps) {
  const options = useMemo<SelectOption<DocumentType>[]>(
    () => types.map(type => ({ value: type, label: DOCUMENT_TYPE_LABELS[type] })),
    [types],
  );
  return (
    <View style={styles.wrap}>
      <SelectField<DocumentType>
        label="Document type"
        value={value ?? ''}
        onChange={next => onChange(next ? next : null)}
        options={options}
        placeholder="All types"
        allowEmpty
        disabled={disabled}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  // A filter, not a form: it keeps a readable width instead of stretching across the page.
  wrap: { width: '100%', maxWidth: 280 },
});
