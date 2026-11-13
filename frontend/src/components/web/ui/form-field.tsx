import { type ReactNode, useId } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { decorative, describedBy } from './a11y';
import { Button } from './button';
import { Icon, type IoniconsName } from './icon';
import { colors, fontSize, radius, space, STATUS_TONES } from './tokens';

/** What a control needs to be tied to its field's label, hint and error. */
export interface FieldA11y {
  /** Id of the visible label, for `aria-labelledby`. */
  labelId:      string;
  /** Ids of the hint and the error when shown, for `aria-describedby`. */
  describedBy?: string;
  invalid:      boolean;
  required:     boolean;
}

export interface FormFieldProps {
  label:     string;
  /** Validation message under the control; announced when it appears. */
  error?:    string | null;
  /** Help text under the control (format, limits). */
  hint?:     string;
  required?: boolean;
  /** The control, or a function that receives the ids to tie a custom control to the label, hint and error. */
  children:  ReactNode | ((a11y: FieldA11y) => ReactNode);
}

/** Stable ids for one field's label, hint and error. */
export function useFieldIds(): { labelId: string; hintId: string; errorId: string } {
  const base = useId();
  return { labelId: `${base}-label`, hintId: `${base}-hint`, errorId: `${base}-error` };
}

/**
 * Label, control, hint and error of one form field. The built-in fields (TextField, SelectField, ...) use it;
 * wrap a custom control in it to get the same layout and wiring.
 */
export function FormField({ label, error, hint, required = false, children }: FormFieldProps) {
  const { labelId, hintId, errorId } = useFieldIds();
  const message = error?.trim() ? error : null;
  const a11y: FieldA11y = {
    labelId,
    describedBy: describedBy(hint && hintId, message && errorId),
    invalid: !!message,
    required,
  };
  return (
    <View style={styles.field}>
      {/* The star sits beside the label, not inside it, so the element named by aria-labelledby holds the label text only;
          the required state is conveyed by aria-required on the control. */}
      <View style={styles.labelRow}>
        <Text id={labelId} style={styles.label}>{label}</Text>
        {required ? <Text style={[styles.label, styles.required]} {...decorative}>{' *'}</Text> : null}
      </View>
      {typeof children === 'function' ? children(a11y) : children}
      {hint ? <Text id={hintId} style={styles.hint}>{hint}</Text> : null}
      {message ? (
        <View style={styles.errorRow} accessibilityRole="alert">
          <Icon name="alert-circle-outline" size={14} color={colors.danger} style={styles.errorIcon} />
          <Text id={errorId} style={styles.error}>{message}</Text>
        </View>
      ) : null}
    </View>
  );
}

/** A form-level error (a server message that does not belong to one field). Renders nothing without a message. */
export function FormError({ message }: { message?: string | null }) {
  if (!message?.trim()) return null;
  const c = STATUS_TONES.danger;
  return (
    <View style={[styles.formError, { backgroundColor: c.bg, borderColor: c.border }]} accessibilityRole="alert">
      <Icon name="alert-circle-outline" size={18} color={c.fg} style={styles.errorIcon} />
      <Text style={[styles.formErrorText, { color: c.fg }]}>{message}</Text>
    </View>
  );
}

export interface FormActionsProps {
  submitLabel:  string;
  onSubmit:     () => void;
  /** Shows a Cancel button that calls it. */
  onCancel?:    () => void;
  cancelLabel?: string;
  /** A submit is in flight: the submit button shows a spinner and both buttons ignore presses. */
  submitting?:  boolean;
  /** The form cannot be submitted yet (nothing changed, a required choice is missing). */
  disabled?:    boolean;
  /** The submit deletes or revokes something. */
  destructive?: boolean;
  submitIcon?:  IoniconsName;
}

/** Cancel and submit buttons of a form or dialog. They wrap onto two lines when they do not fit side by side. */
export function FormActions({
  submitLabel, onSubmit, onCancel, cancelLabel = 'Cancel', submitting = false, disabled = false, destructive = false, submitIcon,
}: FormActionsProps) {
  return (
    <View style={styles.actions}>
      {onCancel ? <Button label={cancelLabel} variant="secondary" onPress={onCancel} disabled={submitting} /> : null}
      <Button
        label={submitLabel}
        icon={submitIcon}
        variant={destructive ? 'danger' : 'primary'}
        onPress={onSubmit}
        loading={submitting}
        disabled={disabled}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  field:    { gap: space.xs, minWidth: 0, maxWidth: '100%' },
  labelRow: { flexDirection: 'row', alignItems: 'baseline', flexWrap: 'wrap', minWidth: 0 },
  label:    { flexShrink: 1, minWidth: 0, fontSize: fontSize.sm, fontWeight: '600', color: colors.text },
  required: { color: colors.danger, fontWeight: '700' },
  hint:     { fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 17 },
  errorRow: { flexDirection: 'row', alignItems: 'flex-start', gap: space.xs },
  errorIcon: { flexShrink: 0, marginTop: 1 },
  error:    { flex: 1, minWidth: 0, fontSize: fontSize.xs, fontWeight: '500', color: colors.danger, lineHeight: 17 },

  formError:     { flexDirection: 'row', alignItems: 'flex-start', gap: space.sm, padding: space.md, borderWidth: 1, borderRadius: radius.md },
  formErrorText: { flex: 1, minWidth: 0, fontSize: fontSize.sm, fontWeight: '500', lineHeight: 19 },

  actions: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'flex-end', alignItems: 'center', gap: space.sm },
});
