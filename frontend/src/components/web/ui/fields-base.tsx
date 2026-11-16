import { type CSSProperties, type Ref, useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, type TextInputProps, View } from 'react-native';
import { dateOnlyParts } from '@/lib/ui/date-only';
import { accessibleName, decorative, describedBy, IS_WEB, webOnly, type WebKeyEvent } from './a11y';
import { Button, IconButton } from './button';
import { type FieldA11y, FormField, useFieldIds } from './form-field';
import { Icon } from './icon';
import { useUiTheme } from './theme';
import { colors, CONTROL_HEIGHT, fontSize, radius, space } from './tokens';

// The controlled form inputs. Each one draws its own label, hint and error through FormField and takes
// `value` + `onChange(value)`. `fields.tsx` wraps them so the same components also accept `control` + `name`.

/** Props every field shares. */
export interface FieldCommonProps {
  label:     string;
  error?:    string | null;
  hint?:     string;
  required?: boolean;
  disabled?: boolean;
  /** Called when the control loses focus (react-hook-form uses it to mark the field as touched). */
  onBlur?:   () => void;
  testID?:   string;
}

/** A ref to the focusable element, so a form library can move focus to the first invalid field. */
type FocusRef = Ref<any>;

function controlA11y(a11y: FieldA11y, label: string, disabled: boolean): object {
  // Web ties the control to the visible label by id; native has no id references, so it gets the label text.
  return IS_WEB
    ? {
        'aria-labelledby':  a11y.labelId,
        'aria-describedby': a11y.describedBy,
        'aria-invalid':     a11y.invalid || undefined,
        'aria-required':    a11y.required || undefined,
        'aria-disabled':    disabled || undefined,
      }
    : { accessibilityLabel: label, accessibilityState: { disabled } };
}

// ─── TextField ────────────────────────────────────────────────────────────────

export interface TextFieldProps extends FieldCommonProps {
  value:        string;
  onChange:     (text: string) => void;
  placeholder?: string;
  /** A text area that grows to `rows` lines. */
  multiline?:   boolean;
  rows?:        number;
  /** Longest value accepted; mirror the backend column length. */
  maxLength?:   number;
  /** Shows "12/500" under the field when `maxLength` is set. */
  showCount?:   boolean;
  /** Hides the characters (passwords, codes). */
  secure?:      boolean;
  inputMode?:      TextInputProps['inputMode'];
  autoComplete?:   TextInputProps['autoComplete'];
  autoCapitalize?: TextInputProps['autoCapitalize'];
  /** Called when Enter is pressed in a single-line field. */
  onSubmit?:    () => void;
  inputRef?:    FocusRef;
}

export function TextFieldBase({
  label, value, onChange, error, hint, required, disabled = false, onBlur, testID,
  placeholder, multiline = false, rows = 4, maxLength, showCount = false, secure = false,
  inputMode, autoComplete, autoCapitalize, onSubmit, inputRef,
}: TextFieldProps) {
  const { accent } = useUiTheme();
  const [focused, setFocused] = useState(false);
  const count = showCount && maxLength ? `${value.length}/${maxLength}` : null;
  return (
    <FormField label={label} error={error} hint={hint} required={required}>
      {a11y => (
        <>
          <TextInput
            ref={inputRef}
            value={value}
            onChangeText={onChange}
            onFocus={() => setFocused(true)}
            onBlur={() => { setFocused(false); onBlur?.(); }}
            placeholder={placeholder}
            placeholderTextColor={colors.placeholder}
            editable={!disabled}
            multiline={multiline}
            numberOfLines={multiline ? rows : undefined}
            maxLength={maxLength}
            secureTextEntry={secure}
            inputMode={inputMode}
            autoComplete={autoComplete}
            autoCapitalize={autoCapitalize}
            onSubmitEditing={multiline ? undefined : onSubmit}
            testID={testID}
            style={[
              styles.input,
              multiline && { minHeight: Math.max(2, rows) * 20 + 18, textAlignVertical: 'top' },
              a11y.invalid && styles.inputInvalid,
              focused && { borderColor: accent },
              disabled && styles.inputDisabled,
            ]}
            {...controlA11y(a11y, label, disabled)}
          />
          {count ? <Text style={styles.count} {...decorative}>{count}</Text> : null}
        </>
      )}
    </FormField>
  );
}

// ─── Native browser controls (web) ────────────────────────────────────────────

function domControlStyle(invalid: boolean, disabled: boolean): CSSProperties {
  return {
    boxSizing: 'border-box', width: '100%', minWidth: 0, minHeight: CONTROL_HEIGHT, margin: 0, padding: '8px 12px',
    border: `1px solid ${invalid ? colors.danger : colors.borderInput}`, borderRadius: radius.md,
    backgroundColor: disabled ? colors.bgSubtle : colors.surface,
    color: disabled ? colors.disabledText : colors.text,
    fontSize: fontSize.md, fontFamily: 'inherit',
  };
}

function domA11y(a11y: FieldA11y) {
  return {
    'aria-labelledby':  a11y.labelId,
    'aria-describedby': a11y.describedBy,
    'aria-invalid':     a11y.invalid || undefined,
    'aria-required':    a11y.required || undefined,
  } as const;
}

// ─── SelectField ──────────────────────────────────────────────────────────────

export interface SelectOption<V extends string = string> {
  value:     V;
  label:     string;
  disabled?: boolean;
}

export interface SelectFieldProps<V extends string = string> extends FieldCommonProps {
  /** The selected option's value; '' or null when nothing is selected. */
  value:    V | '' | null | undefined;
  onChange: (value: V) => void;
  options:  readonly SelectOption<V>[];
  /** Text of the empty choice shown while nothing is selected. Default "Select an option". */
  placeholder?: string;
  /** Lets the person go back to the empty choice; `onChange` is then called with ''. */
  allowEmpty?:  boolean;
  inputRef?:    FocusRef;
}

export function SelectFieldBase<V extends string = string>({
  label, value, onChange, options, error, hint, required, disabled = false, onBlur, testID,
  placeholder = 'Select an option', allowEmpty = false, inputRef,
}: SelectFieldProps<V>) {
  const { accent, accentBg } = useUiTheme();
  const current = value ?? '';
  const known = options.some(o => o.value === current);

  if (IS_WEB) {
    return (
      <FormField label={label} error={error} hint={hint} required={required}>
        {a11y => (
          <select
            ref={inputRef}
            value={known ? current : ''}
            onChange={e => onChange(e.target.value as V)}
            onBlur={onBlur}
            disabled={disabled}
            data-testid={testID}
            style={domControlStyle(a11y.invalid, disabled)}
            {...domA11y(a11y)}
          >
            {(allowEmpty || !known) && <option value="" disabled={!allowEmpty}>{placeholder}</option>}
            {options.map(o => <option key={o.value} value={o.value} disabled={o.disabled}>{o.label}</option>)}
          </select>
        )}
      </FormField>
    );
  }

  // Native: the options as a radio group (no extra picker dependency).
  return (
    <FormField label={label} error={error} hint={hint} required={required}>
      <View accessibilityRole="radiogroup" accessibilityLabel={label} style={styles.radioGroup}>
        {options.map(o => {
          const selected = o.value === current;
          const off = disabled || !!o.disabled;
          return (
            <Pressable
              key={o.value}
              accessibilityRole="radio"
              accessibilityState={{ checked: selected, disabled: off }}
              disabled={off}
              onPress={() => onChange(o.value)}
              style={[styles.radio, selected && { borderColor: accent, backgroundColor: accentBg }, off && styles.inputDisabled]}
            >
              <Icon name={selected ? 'radio-button-on' : 'radio-button-off'} size={18} color={selected ? accent : colors.textMuted} />
              <Text style={styles.radioLabel}>{o.label}</Text>
            </Pressable>
          );
        })}
      </View>
    </FormField>
  );
}

// ─── DateField and TimeField ──────────────────────────────────────────────────

export interface DateFieldProps extends FieldCommonProps {
  /** A calendar date "YYYY-MM-DD" (no time, no zone), or '' when empty. */
  value:    string;
  onChange: (value: string) => void;
  /** Earliest and latest selectable dates, "YYYY-MM-DD". */
  min?:     string;
  max?:     string;
  inputRef?: FocusRef;
}

/** True for '' or a real "YYYY-MM-DD" date. Use in schemas for fields edited with DateField. */
export function isDateFieldValue(value: string): boolean {
  return value === '' || (/^\d{4}-\d{2}-\d{2}$/.test(value) && dateOnlyParts(value) !== null);
}

export function DateFieldBase({
  label, value, onChange, error, hint, required, disabled = false, onBlur, testID, min, max, inputRef,
}: DateFieldProps) {
  if (IS_WEB) {
    return (
      <FormField label={label} error={error} hint={hint} required={required}>
        {a11y => (
          <input
            ref={inputRef}
            type="date"
            value={value}
            min={min}
            max={max}
            onChange={e => onChange(e.target.value)}
            onBlur={onBlur}
            disabled={disabled}
            data-testid={testID}
            style={domControlStyle(a11y.invalid, disabled)}
            {...domA11y(a11y)}
          />
        )}
      </FormField>
    );
  }
  return (
    <TextFieldBase
      label={label} value={value} onChange={onChange} error={error} hint={hint ?? 'Format: YYYY-MM-DD'}
      required={required} disabled={disabled} onBlur={onBlur} testID={testID}
      placeholder="YYYY-MM-DD" maxLength={10} inputMode="numeric" inputRef={inputRef}
    />
  );
}

export interface TimeFieldProps extends FieldCommonProps {
  /** A 24-hour wall-clock time "HH:mm", or '' when empty. */
  value:    string;
  onChange: (value: string) => void;
  min?:     string;
  max?:     string;
  /** Minutes between selectable times (15 for quarter hours). Default 1. */
  stepMinutes?: number;
  inputRef?: FocusRef;
}

export function TimeFieldBase({
  label, value, onChange, error, hint, required, disabled = false, onBlur, testID, min, max, stepMinutes = 1, inputRef,
}: TimeFieldProps) {
  if (IS_WEB) {
    return (
      <FormField label={label} error={error} hint={hint} required={required}>
        {a11y => (
          <input
            ref={inputRef}
            type="time"
            value={value.slice(0, 5)}
            min={min}
            max={max}
            step={Math.max(1, Math.round(stepMinutes)) * 60}
            // Browsers answer "HH:mm" (or "HH:mm:ss" for sub-minute steps); the field's value is always "HH:mm".
            onChange={e => onChange(e.target.value.slice(0, 5))}
            onBlur={onBlur}
            disabled={disabled}
            data-testid={testID}
            style={domControlStyle(a11y.invalid, disabled)}
            {...domA11y(a11y)}
          />
        )}
      </FormField>
    );
  }
  return (
    <TextFieldBase
      label={label} value={value} onChange={onChange} error={error} hint={hint ?? 'Format: HH:mm (24-hour)'}
      required={required} disabled={disabled} onBlur={onBlur} testID={testID}
      placeholder="HH:mm" maxLength={5} inputMode="numeric" inputRef={inputRef}
    />
  );
}

// ─── SwitchField ──────────────────────────────────────────────────────────────

export interface SwitchFieldProps extends Omit<FieldCommonProps, 'required'> {
  value:    boolean;
  onChange: (value: boolean) => void;
  /** A sentence under the label explaining what the switch does. */
  description?: string;
  inputRef?: FocusRef;
}

/**
 * An on/off setting. The whole row is one control with role "switch" and aria-checked, named by its visible
 * label; Space and Enter toggle it. The state is shown by the thumb position and the words On / Off, not by
 * colour alone.
 */
export function SwitchFieldBase({
  label, value, onChange, description, error, hint, disabled = false, onBlur, testID, inputRef,
}: SwitchFieldProps) {
  const { accent } = useUiTheme();
  const ids = useFieldIds();
  const note = description ?? hint;
  const toggle = () => { if (!disabled) onChange(!value); };
  const onKeyDown = (event: WebKeyEvent) => {
    if (event.key === ' ' || event.key === 'Spacebar') { event.preventDefault(); toggle(); }
  };
  return (
    <View style={styles.switchWrap}>
      <Pressable
        ref={inputRef}
        role="switch"
        aria-checked={value}
        accessibilityState={{ checked: value, disabled }}
        disabled={disabled}
        onPress={toggle}
        onBlur={onBlur}
        testID={testID}
        style={[styles.switchRow, disabled && styles.switchDisabled]}
        {...(IS_WEB
          ? { 'aria-labelledby': ids.labelId, 'aria-describedby': describedBy(note && ids.hintId, error?.trim() && ids.errorId) }
          : { accessibilityLabel: accessibleName(label, note) })}
        {...webOnly({ onKeyDown })}
      >
        <View style={styles.switchText}>
          <Text id={ids.labelId} style={styles.switchLabel}>{label}</Text>
          {note ? <Text id={ids.hintId} style={styles.switchNote}>{note}</Text> : null}
        </View>
        <View style={styles.switchState} {...decorative}>
          <Text style={styles.switchWord}>{value ? 'On' : 'Off'}</Text>
          <View style={[styles.track, value ? { backgroundColor: accent, borderColor: accent } : styles.trackOff]}>
            <View style={[styles.thumb, value ? styles.thumbOn : styles.thumbOff]} />
          </View>
        </View>
      </Pressable>
      {error?.trim() ? (
        <View style={styles.switchError} accessibilityRole="alert">
          <Icon name="alert-circle-outline" size={14} color={colors.danger} />
          <Text id={ids.errorId} style={styles.switchErrorText}>{error}</Text>
        </View>
      ) : null}
    </View>
  );
}

// ─── TagListField ─────────────────────────────────────────────────────────────

export interface TagListFieldProps extends FieldCommonProps {
  /** The entries, in the order they were added. */
  value:    string[];
  onChange: (value: string[]) => void;
  placeholder?: string;
  /** Most entries allowed; the input is disabled once reached. */
  maxItems?:  number;
  /** Longest single entry. */
  maxLength?: number;
  /** Text of the add button. Default "Add". */
  addLabel?:  string;
  inputRef?:  FocusRef;
}

/**
 * A list of short text entries (allergies, conditions, tags). Type an entry and press Enter or the add button;
 * several entries can be pasted separated by commas. Text still in the box when focus leaves it is added too, so
 * nothing typed is lost on Save. Each entry has its own Remove button.
 */
export function TagListFieldBase({
  label, value, onChange, error, hint, required, disabled = false, onBlur, testID,
  placeholder, maxItems, maxLength = 100, addLabel = 'Add', inputRef,
}: TagListFieldProps) {
  const { accent, accentBg } = useUiTheme();
  const [draft, setDraft] = useState('');
  const [focused, setFocused] = useState(false);
  const full = maxItems !== undefined && value.length >= maxItems;

  const add = () => {
    const seen = new Set(value.map(v => v.toLowerCase()));
    const next = value.slice();
    for (const part of draft.split(',')) {
      const entry = part.trim().replace(/\s+/g, ' ').slice(0, maxLength);
      if (!entry || seen.has(entry.toLowerCase())) continue;
      if (maxItems !== undefined && next.length >= maxItems) break;
      seen.add(entry.toLowerCase());
      next.push(entry);
    }
    if (next.length !== value.length) onChange(next);
    setDraft('');
  };

  return (
    <FormField label={label} error={error} hint={hint} required={required}>
      {a11y => (
        <>
          <View style={styles.tagInputRow}>
            <TextInput
              ref={inputRef}
              value={draft}
              onChangeText={setDraft}
              onFocus={() => setFocused(true)}
              // Text left in the box when the person moves on (Tab, or a click on Save) is an entry they meant to
              // add: commit it instead of dropping it from the form. A click on Add blurs first, so the entry is
              // added here and the press that follows finds an empty draft.
              onBlur={() => { setFocused(false); add(); onBlur?.(); }}
              onSubmitEditing={add}
              // Enter adds the entry and keeps the cursor in the input for the next one.
              submitBehavior="submit"
              blurOnSubmit={false}
              placeholder={full ? 'Limit reached' : placeholder}
              placeholderTextColor={colors.placeholder}
              editable={!disabled && !full}
              testID={testID}
              style={[
                styles.input, styles.tagInput,
                a11y.invalid && styles.inputInvalid,
                focused && { borderColor: accent },
                (disabled || full) && styles.inputDisabled,
              ]}
              {...controlA11y(a11y, label, disabled || full)}
            />
            <Button
              label={addLabel}
              accessibilityLabel={`${addLabel} to ${label}`}
              icon="add"
              variant="secondary"
              onPress={add}
              disabled={disabled || full || !draft.trim()}
            />
          </View>
          {value.length > 0 ? (
            <View style={styles.tags} role="list" aria-label={`${label}: ${value.length} added`}>
              {value.map((tag, index) => (
                <View key={`${index}-${tag}`} role="listitem" style={[styles.tag, { backgroundColor: accentBg }]}>
                  <Text style={styles.tagText}>{tag}</Text>
                  {!disabled && (
                    <IconButton
                      label={`Remove ${tag}`}
                      icon="close"
                      size="sm"
                      onPress={() => onChange(value.filter((_, i) => i !== index))}
                      style={styles.tagRemove}
                    />
                  )}
                </View>
              ))}
            </View>
          ) : null}
        </>
      )}
    </FormField>
  );
}

const styles = StyleSheet.create({
  input: {
    minHeight: CONTROL_HEIGHT, width: '100%', minWidth: 0, paddingHorizontal: space.md, paddingVertical: space.sm,
    borderWidth: 1, borderColor: colors.borderInput, borderRadius: radius.md,
    backgroundColor: colors.surface, color: colors.text, fontSize: fontSize.md,
  },
  inputInvalid:  { borderColor: colors.danger },
  inputDisabled: { backgroundColor: colors.bgSubtle, color: colors.disabledText },
  count:         { alignSelf: 'flex-end', fontSize: fontSize.xs, color: colors.textSubtle },

  radioGroup: { gap: space.xs },
  radio: {
    minHeight: CONTROL_HEIGHT, flexDirection: 'row', alignItems: 'center', gap: space.sm, paddingHorizontal: space.md,
    borderWidth: 1, borderColor: colors.borderInput, borderRadius: radius.md, backgroundColor: colors.surface,
  },
  radioLabel: { flex: 1, minWidth: 0, fontSize: fontSize.md, color: colors.text },

  switchWrap:     { gap: space.xs, minWidth: 0, maxWidth: '100%' },
  switchRow:      { minHeight: CONTROL_HEIGHT, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space.md, paddingVertical: space.xs },
  switchDisabled: { opacity: 0.6 },
  switchText:     { flex: 1, minWidth: 0, gap: 2 },
  switchLabel:    { fontSize: fontSize.md, fontWeight: '600', color: colors.text },
  switchNote:     { fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 17 },
  switchState:    { flexDirection: 'row', alignItems: 'center', gap: space.sm, flexShrink: 0 },
  switchWord:     { minWidth: 22, textAlign: 'right', fontSize: fontSize.xs, fontWeight: '600', color: colors.textMuted },
  track:          { width: 44, height: 24, borderRadius: radius.pill, borderWidth: 1, justifyContent: 'center', paddingHorizontal: 2 },
  trackOff:       { backgroundColor: colors.surface, borderColor: colors.borderInput },
  thumb:          { width: 18, height: 18, borderRadius: radius.pill },
  thumbOn:        { alignSelf: 'flex-end', backgroundColor: '#FFFFFF' },
  thumbOff:       { alignSelf: 'flex-start', backgroundColor: colors.borderInput },
  switchError:     { flexDirection: 'row', alignItems: 'flex-start', gap: space.xs },
  switchErrorText: { flex: 1, minWidth: 0, fontSize: fontSize.xs, fontWeight: '500', color: colors.danger, lineHeight: 17 },

  tagInputRow: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  tagInput:    { flex: 1, width: undefined },
  tags:        { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs },
  tag: {
    flexDirection: 'row', alignItems: 'center', gap: 2, maxWidth: '100%',
    paddingLeft: space.md, paddingRight: 2, paddingVertical: 2, borderRadius: radius.pill,
  },
  tagText:   { flexShrink: 1, fontSize: fontSize.sm, color: colors.text },
  tagRemove: { borderRadius: radius.pill },
});
