import { useId, useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import {
  Button, colors, CONTROL_HEIGHT, decorative, fontSize, FormField, IconButton, IS_WEB, radius, space, StatusBadge,
  useUiTheme, webOnly,
} from '@/components/web/ui';
import { type IcdSearchItem, ICD_SEARCH_MIN_CHARS, useIcdSearch } from '@/hooks/use-terminology';
import {
  ICD_CODES_MAX, ICD_DESCRIPTION_MAX, ICD_QUERY_MAX, type IcdCodeRow, isWellFormedIcdCode, normaliseIcdCode,
} from '@/lib/validation/medical-record';

export interface IcdCodeFieldProps {
  /** The codes on the record, in the order they were added. */
  value:     IcdCodeRow[];
  onChange:  (rows: IcdCodeRow[]) => void;
  /** A message about the list as a whole (too many codes, a duplicate). */
  error?:    string | null;
  /** A message per row, by index (a code the server refused). */
  rowErrors?: (string | null | undefined)[];
  disabled?: boolean;
  onBlur?:   () => void;
  /**
   * Called when Escape closed the suggestions, so a dialog around the field can ignore the close request that
   * the same key press sends it.
   */
  onEscapeConsumed?: () => void;
}

interface KeyEvent { nativeEvent: { key: string }; preventDefault?: () => void }

/**
 * ICD-10 codes of a record. Typing searches the catalogue (code or description) after a short pause; a
 * suggestion is added with its catalogue description. A code that is not in the catalogue can still be added
 * when it is well formed: it is marked "Unverified" and may be given a description by hand.
 * Keyboard: Down and Up move through the suggestions, Enter adds the highlighted one (or the typed code),
 * Escape closes the list.
 */
export function IcdCodeField({ value, onChange, error, rowErrors, disabled = false, onBlur, onEscapeConsumed }: IcdCodeFieldProps) {
  const { accent, accentBg } = useUiTheme();
  const listId = useId();
  const [text, setText] = useState('');
  const [focused, setFocused] = useState(false);
  const [active, setActive] = useState(-1);
  const [dismissed, setDismissed] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const full = value.length >= ICD_CODES_MAX;
  const search = useIcdSearch(text, { enabled: !disabled && !full });
  const typedCode = normaliseIcdCode(text);
  const added = new Set(value.map(row => normaliseIcdCode(row.code)));
  const suggestions = search.results;
  const exact = suggestions.find(s => normaliseIcdCode(s.code) === typedCode);
  // A well-formed code the catalogue does not list may be added by hand once the search has answered (or
  // failed: the server checks every code again when the record is saved).
  const canAddTyped = !full && isWellFormedIcdCode(text) && !exact && !added.has(typedCode) && !search.searching;
  const open = !dismissed && !disabled && text.trim().length >= ICD_SEARCH_MIN_CHARS
    && (search.searching || !!search.error || search.settledQuery !== '');

  const clearInput = () => { setText(''); setActive(-1); setDismissed(false); };

  const add = (row: IcdCodeRow) => {
    const code = normaliseIcdCode(row.code);
    if (added.has(code)) { setNotice(`${code} is already on this record.`); clearInput(); return; }
    if (full) return;
    onChange([...value, { ...row, code }]);
    setNotice(`${code} added.`);
    clearInput();
  };

  const pick = (item: IcdSearchItem) => add({ code: item.code, description: item.description, verified: true });
  const addTyped = () => add({ code: typedCode, description: '', verified: false });

  const submit = () => {
    if (active >= 0 && suggestions[active]) { pick(suggestions[active]); return; }
    if (exact) { pick(exact); return; }
    if (canAddTyped) addTyped();
  };

  const onKeyPress = (event: KeyEvent) => {
    const key = event.nativeEvent.key;
    if (key === 'ArrowDown' && suggestions.length > 0) {
      event.preventDefault?.();
      setDismissed(false);
      setActive(i => (i + 1) % suggestions.length);
    } else if (key === 'ArrowUp' && suggestions.length > 0) {
      event.preventDefault?.();
      setActive(i => (i <= 0 ? suggestions.length - 1 : i - 1));
    } else if (key === 'Escape' && open) {
      setDismissed(true);
      setActive(-1);
      onEscapeConsumed?.();
    }
  };

  const remove = (index: number) => {
    const row = value[index];
    onChange(value.filter((_, i) => i !== index));
    if (row) setNotice(`${row.code} removed.`);
  };

  const describe = (index: number, description: string) => {
    onChange(value.map((row, i) => (i === index ? { ...row, description } : row)));
  };

  const optionId = (index: number) => `${listId}-option-${index}`;
  const status = !open ? ''
    : search.searching ? 'Searching codes'
      : search.error ? search.error
        : suggestions.length === 0 ? 'No matching codes in the catalogue'
          : `${suggestions.length} ${suggestions.length === 1 ? 'suggestion' : 'suggestions'}${search.total > suggestions.length ? `, ${search.total} matches in all: keep typing to narrow them` : ''}`;

  return (
    <FormField
      label="ICD-10 codes"
      error={error}
      hint={`Search by code or description, for example "J06" or "cough". Up to ${ICD_CODES_MAX} codes.`}
    >
      {a11y => (
        <View style={styles.wrap}>
          <TextInput
            value={text}
            onChangeText={next => { setText(next); setActive(-1); setDismissed(false); setNotice(null); }}
            onFocus={() => setFocused(true)}
            onBlur={() => { setFocused(false); onBlur?.(); }}
            onKeyPress={onKeyPress}
            onSubmitEditing={submit}
            // Enter adds a code and keeps the cursor in the box for the next one.
            submitBehavior="submit"
            blurOnSubmit={false}
            editable={!disabled && !full}
            maxLength={ICD_QUERY_MAX}
            autoCapitalize="none"
            autoCorrect={false}
            placeholder={full ? `Limit of ${ICD_CODES_MAX} codes reached` : 'Search ICD-10 codes'}
            placeholderTextColor={colors.placeholder}
            style={[
              styles.input,
              a11y.invalid && styles.inputInvalid,
              focused && { borderColor: accent },
              (disabled || full) && styles.inputDisabled,
            ]}
            {...(IS_WEB
              ? {
                  role: 'combobox' as const,
                  'aria-labelledby':  a11y.labelId,
                  'aria-describedby': a11y.describedBy,
                  'aria-invalid':     a11y.invalid || undefined,
                  'aria-expanded':    open,
                }
              : { accessibilityLabel: 'ICD-10 codes, search' })}
            {...webOnly({
              'aria-autocomplete':     'list',
              'aria-controls':         open ? listId : undefined,
              'aria-activedescendant': open && active >= 0 ? optionId(active) : undefined,
            })}
          />

          {/* Announces what the search found and what was added or removed. */}
          <Text style={styles.srStatus} role="status" aria-live="polite">{notice ?? status}</Text>

          {open ? (
            <View style={styles.popup}>
              {search.searching ? (
                <View style={styles.popupRow}>
                  <View {...decorative}><ActivityIndicator size="small" color={accent} /></View>
                  <Text style={styles.popupNote}>Searching codes…</Text>
                </View>
              ) : search.error ? (
                <View style={styles.popupRow}>
                  <Text style={[styles.popupNote, styles.popupError]}>{search.error}</Text>
                  <Button label="Retry" variant="secondary" size="sm" onPress={search.retry} />
                </View>
              ) : suggestions.length === 0 ? (
                <View style={styles.popupRow}>
                  <Text style={styles.popupNote}>No matching codes in the catalogue.</Text>
                </View>
              ) : (
                <View nativeID={listId} aria-label="ICD-10 suggestions" {...webOnly({ role: 'listbox' })}>
                  {suggestions.map((item, index) => {
                    const already = added.has(normaliseIcdCode(item.code));
                    const current = index === active;
                    return (
                      <Pressable
                        key={item.code}
                        nativeID={optionId(index)}
                        role="option"
                        aria-selected={current}
                        aria-disabled={already}
                        disabled={already}
                        // The input keeps the focus; options are reached with the arrow keys or the pointer.
                        tabIndex={-1}
                        onPress={() => pick(item)}
                        style={({ pressed }) => [
                          styles.option,
                          // The option reached with the arrow keys: a tint plus an accent bar, so the cue does not
                          // rest on a pale fill alone (non-text contrast 3:1).
                          current && { backgroundColor: accentBg, borderLeftColor: accent, ...styles.optionActive },
                          pressed && styles.optionPressed,
                          already && styles.optionAdded,
                        ]}
                      >
                        <Text style={styles.optionCode}>{item.code}</Text>
                        <Text style={styles.optionText}>{item.description}</Text>
                        {already ? <Text style={styles.optionNote}>Added</Text> : null}
                      </Pressable>
                    );
                  })}
                </View>
              )}
              {canAddTyped ? (
                <View style={[styles.popupRow, styles.popupFooter]}>
                  <Text style={styles.popupNote}>
                    {search.error
                      ? `${typedCode} could not be looked up. You can still add it; it is checked when the record is saved.`
                      : `${typedCode} is not in the catalogue. You can still add it; it is saved as unverified.`}
                  </Text>
                  <Button
                    label={`Add ${typedCode}`}
                    accessibilityLabel={`Add ${typedCode} as an unverified code`}
                    icon="add"
                    variant="secondary"
                    size="sm"
                    onPress={addTyped}
                  />
                </View>
              ) : null}
            </View>
          ) : null}

          {value.length > 0 ? (
            <View style={styles.rows} role="list" aria-label={`ICD-10 codes on this record: ${value.length}`}>
              {value.map((row, index) => {
                const rowError = rowErrors?.[index]?.trim() || null;
                const unverified = row.verified === false;
                return (
                  <View key={`${row.code}-${index}`} role="listitem" style={[styles.row, !!rowError && styles.rowInvalid]}>
                    <View style={styles.rowMain}>
                      <View style={styles.rowHead}>
                        <Text style={styles.rowCode}>{row.code}</Text>
                        {unverified ? <StatusBadge label="Unverified" tone="warning" icon="help-circle-outline" /> : null}
                      </View>
                      {unverified ? (
                        <TextInput
                          value={row.description}
                          onChangeText={next => describe(index, next)}
                          editable={!disabled}
                          maxLength={ICD_DESCRIPTION_MAX}
                          placeholder="Description (optional)"
                          placeholderTextColor={colors.placeholder}
                          accessibilityLabel={`Description of ${row.code}`}
                          style={[styles.input, disabled && styles.inputDisabled]}
                        />
                      ) : (
                        <Text style={styles.rowText}>{row.description || 'No description'}</Text>
                      )}
                      {rowError ? <Text style={styles.rowError} accessibilityRole="alert">{rowError}</Text> : null}
                    </View>
                    {!disabled ? (
                      <IconButton label={`Remove ${row.code}`} icon="close" size="sm" onPress={() => remove(index)} />
                    ) : null}
                  </View>
                );
              })}
            </View>
          ) : null}
        </View>
      )}
    </FormField>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: space.sm, minWidth: 0 },
  input: {
    minHeight: CONTROL_HEIGHT, width: '100%', minWidth: 0, paddingHorizontal: space.md, paddingVertical: space.sm,
    borderWidth: 1, borderColor: colors.borderInput, borderRadius: radius.md,
    backgroundColor: colors.surface, color: colors.text, fontSize: fontSize.md,
  },
  inputInvalid:  { borderColor: colors.danger },
  inputDisabled: { backgroundColor: colors.bgSubtle, color: colors.disabledText },

  // Visually hidden, still read by screen readers.
  srStatus: { position: 'absolute', width: 1, height: 1, overflow: 'hidden', opacity: 0 },

  popup:       { borderWidth: 1, borderColor: colors.borderInput, borderRadius: radius.md, backgroundColor: colors.surface, overflow: 'hidden' },
  popupRow:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, paddingHorizontal: space.md, paddingVertical: space.sm },
  popupFooter: { borderTopWidth: 1, borderTopColor: colors.border, backgroundColor: colors.bg },
  popupNote:   { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0, fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  popupError:  { color: colors.danger },

  option: {
    minHeight: CONTROL_HEIGHT, flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', columnGap: space.md, rowGap: 2,
    paddingHorizontal: space.md, paddingVertical: space.sm, borderBottomWidth: 1, borderBottomColor: colors.border,
  },
  optionActive:  { borderLeftWidth: 4, paddingLeft: space.md - 4 },
  optionPressed: { opacity: 0.85 },
  optionAdded:   { backgroundColor: colors.bgSubtle },
  optionCode:    { minWidth: 56, fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  optionText:    { flexGrow: 1, flexShrink: 1, flexBasis: 160, minWidth: 0, fontSize: fontSize.md, color: colors.text },
  optionNote:    { fontSize: fontSize.xs, fontWeight: '600', color: colors.textMuted },

  rows: { gap: space.sm, minWidth: 0 },
  row: {
    flexDirection: 'row', alignItems: 'flex-start', gap: space.sm, padding: space.sm, paddingLeft: space.md,
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.bg,
  },
  rowInvalid: { borderColor: colors.danger },
  rowMain:    { flex: 1, minWidth: 0, gap: space.xs },
  rowHead:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm },
  rowCode:    { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  rowText:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  rowError:   { fontSize: fontSize.xs, fontWeight: '500', color: colors.danger, lineHeight: 17 },
});
