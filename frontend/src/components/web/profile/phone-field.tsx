import { type ReactElement, useRef } from 'react';
import { type Control, Controller, type FieldValues, type Path } from 'react-hook-form';
import { StyleSheet, View } from 'react-native';
import { PhoneInput } from '@/components/shared/phone-input';
import { colors, FormField, IS_WEB, useUiTheme, webOnly } from '@/components/web/ui';

// The shared phone input (country code + number) as a form field of the web primitives: same label, hint and
// error wiring as TextField, usable controlled or with react-hook-form (`control` + `name`).

export interface PhoneFieldProps {
  label:     string;
  /** The stored or typed phone number, for example "+263772123456"; '' when there is none. */
  value:     string;
  onChange:  (value: string) => void;
  error?:    string | null;
  hint?:     string;
  required?: boolean;
}

export type BoundPhoneFieldProps<T extends FieldValues> = Omit<PhoneFieldProps, 'value' | 'onChange'> & {
  control: Control<T>;
  name:    Path<T>;
};

function PhoneFieldBase({ label, value, onChange, error, hint, required }: PhoneFieldProps) {
  const { accent } = useUiTheme();

  // The shared input reads `value` once, when it mounts, and from then on reports "<dial code> <number>". It
  // also reports once straight after mounting, before anything was typed: the stored number reformatted, or
  // the dial code alone when there is no number. That first report is not an edit, so it is mapped back to the
  // value the field started with; the form then stays unchanged until the person really changes the number,
  // and goes back to unchanged if they restore it.
  const initial = useRef(value);
  const echo = useRef<string | null>(null);
  const handleChange = (next: string) => {
    if (echo.current === null) echo.current = next;
    onChange(next === echo.current ? initial.current : next);
  };

  return (
    <FormField label={label} error={error} hint={hint} required={required}>
      {a11y => (
        <View
          style={[styles.frame, a11y.invalid && styles.frameInvalid]}
          {...(IS_WEB ? null : { accessibilityLabel: label })}
          // The country selector and the number box form one labelled group; the hint and the error describe it.
          {...webOnly({ role: 'group', 'aria-labelledby': a11y.labelId, 'aria-describedby': a11y.describedBy })}
        >
          <PhoneInput value={initial.current} onChange={handleChange} accentColor={accent} />
        </View>
      )}
    </FormField>
  );
}

/**
 * A phone number field. Mount it with the value to edit and remount it (it lives inside a form that is
 * mounted per edit) to start from another value: the country selector is derived from the first value only.
 */
export function PhoneField<T extends FieldValues = FieldValues>(
  props: PhoneFieldProps | BoundPhoneFieldProps<T>,
): ReactElement {
  if (!('control' in props)) return <PhoneFieldBase {...props} />;
  const { control, name, ...rest } = props;
  return (
    <Controller
      control={control}
      name={name}
      render={({ field, fieldState }) => (
        <PhoneFieldBase
          {...rest}
          value={field.value == null ? '' : String(field.value)}
          onChange={field.onChange}
          error={rest.error ?? fieldState.error?.message}
        />
      )}
    />
  );
}

const styles = StyleSheet.create({
  // A one-pixel frame that turns red when the field is invalid (the shared input draws its own border).
  frame:        { borderWidth: 1, borderColor: 'transparent', borderRadius: 13, minWidth: 0, maxWidth: '100%' },
  frameInvalid: { borderColor: colors.danger },
});
