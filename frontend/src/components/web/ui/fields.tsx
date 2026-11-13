import type { ReactElement } from 'react';
import { type Control, Controller, type FieldValues, type Path } from 'react-hook-form';
import {
  DateFieldBase, type DateFieldProps,
  SelectFieldBase, type SelectFieldProps,
  SwitchFieldBase, type SwitchFieldProps,
  TagListFieldBase, type TagListFieldProps,
  TextFieldBase, type TextFieldProps,
  TimeFieldBase, type TimeFieldProps,
} from './fields-base';

// The public form fields. Each accepts either form of props:
//
//   controlled         <TextField label="Reason" value={reason} onChange={setReason} error={error} />
//   react-hook-form    <TextField label="Reason" control={control} name="reason" />
//
// With `control` + `name` the value, change and blur handlers, the validation message and the focus target for the
// first invalid field all come from the form. An `error` passed explicitly still wins (for a server message).

/** Props of the react-hook-form variant of a field whose controlled props are `P`. */
export type BoundFieldProps<P, T extends FieldValues> = Omit<P, 'value' | 'onChange' | 'inputRef'> & {
  control: Control<T>;
  name:    Path<T>;
};

function isBound<P extends object, T extends FieldValues>(props: P | BoundFieldProps<P, T>): props is BoundFieldProps<P, T> {
  return 'control' in props && props.control != null;
}

function chain(first: () => void, second?: () => void): () => void {
  return () => { first(); second?.(); };
}

export function TextField<T extends FieldValues = FieldValues>(
  props: TextFieldProps | BoundFieldProps<TextFieldProps, T>,
): ReactElement {
  if (!isBound<TextFieldProps, T>(props)) return <TextFieldBase {...props} />;
  const { control, name, ...rest } = props;
  return (
    <Controller
      control={control}
      name={name}
      render={({ field, fieldState }) => (
        <TextFieldBase
          {...rest}
          value={field.value == null ? '' : String(field.value)}
          onChange={field.onChange}
          onBlur={chain(field.onBlur, rest.onBlur)}
          error={rest.error ?? fieldState.error?.message}
          inputRef={field.ref}
        />
      )}
    />
  );
}

export function SelectField<V extends string = string, T extends FieldValues = FieldValues>(
  props: SelectFieldProps<V> | BoundFieldProps<SelectFieldProps<V>, T>,
): ReactElement {
  if (!isBound<SelectFieldProps<V>, T>(props)) return <SelectFieldBase<V> {...props} />;
  const { control, name, ...rest } = props;
  return (
    <Controller
      control={control}
      name={name}
      render={({ field, fieldState }) => (
        <SelectFieldBase<V>
          {...rest}
          value={field.value == null ? '' : (String(field.value) as V)}
          onChange={field.onChange}
          onBlur={chain(field.onBlur, rest.onBlur)}
          error={rest.error ?? fieldState.error?.message}
          inputRef={field.ref}
        />
      )}
    />
  );
}

export function DateField<T extends FieldValues = FieldValues>(
  props: DateFieldProps | BoundFieldProps<DateFieldProps, T>,
): ReactElement {
  if (!isBound<DateFieldProps, T>(props)) return <DateFieldBase {...props} />;
  const { control, name, ...rest } = props;
  return (
    <Controller
      control={control}
      name={name}
      render={({ field, fieldState }) => (
        <DateFieldBase
          {...rest}
          value={field.value == null ? '' : String(field.value)}
          onChange={field.onChange}
          onBlur={chain(field.onBlur, rest.onBlur)}
          error={rest.error ?? fieldState.error?.message}
          inputRef={field.ref}
        />
      )}
    />
  );
}

export function TimeField<T extends FieldValues = FieldValues>(
  props: TimeFieldProps | BoundFieldProps<TimeFieldProps, T>,
): ReactElement {
  if (!isBound<TimeFieldProps, T>(props)) return <TimeFieldBase {...props} />;
  const { control, name, ...rest } = props;
  return (
    <Controller
      control={control}
      name={name}
      render={({ field, fieldState }) => (
        <TimeFieldBase
          {...rest}
          value={field.value == null ? '' : String(field.value)}
          onChange={field.onChange}
          onBlur={chain(field.onBlur, rest.onBlur)}
          error={rest.error ?? fieldState.error?.message}
          inputRef={field.ref}
        />
      )}
    />
  );
}

export function SwitchField<T extends FieldValues = FieldValues>(
  props: SwitchFieldProps | BoundFieldProps<SwitchFieldProps, T>,
): ReactElement {
  if (!isBound<SwitchFieldProps, T>(props)) return <SwitchFieldBase {...props} />;
  const { control, name, ...rest } = props;
  return (
    <Controller
      control={control}
      name={name}
      render={({ field, fieldState }) => (
        <SwitchFieldBase
          {...rest}
          value={field.value === true}
          onChange={field.onChange}
          onBlur={chain(field.onBlur, rest.onBlur)}
          error={rest.error ?? fieldState.error?.message}
          inputRef={field.ref}
        />
      )}
    />
  );
}

export function TagListField<T extends FieldValues = FieldValues>(
  props: TagListFieldProps | BoundFieldProps<TagListFieldProps, T>,
): ReactElement {
  if (!isBound<TagListFieldProps, T>(props)) return <TagListFieldBase {...props} />;
  const { control, name, ...rest } = props;
  return (
    <Controller
      control={control}
      name={name}
      render={({ field, fieldState }) => (
        <TagListFieldBase
          {...rest}
          value={Array.isArray(field.value) ? (field.value as unknown[]).map(String) : []}
          onChange={field.onChange}
          onBlur={chain(field.onBlur, rest.onBlur)}
          error={rest.error ?? fieldState.error?.message}
          inputRef={field.ref}
        />
      )}
    />
  );
}
