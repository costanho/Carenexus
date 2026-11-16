// Shared web primitives (CONTRACTS 12.3). New screens import from '@/components/web/ui' instead of keeping
// per-file copies of buttons, states, badges and form inputs.

export {
  tokens, ROLE_THEME, DEFAULT_ROLE_THEME, STATUS_TONES, colors, space, radius, fontSize, CONTROL_HEIGHT, PHONE_BREAKPOINT,
  type AppRole, type RoleTheme, type StatusToneName, type ToneColors,
} from './tokens';
export { UiThemeProvider, useUiTheme } from './theme';
export {
  ariaHidden, ariaLevel, ariaPressed, accessibleName, decorative, describedBy, webOnly, IS_WEB, type WebKeyEvent,
} from './a11y';
export { Icon, type IoniconsName } from './icon';

export { Screen, type ScreenProps } from './screen';
export { LoadingView, ErrorView, EmptyView, NoAccessView, NO_ACCESS_MESSAGE, type EmptyAction } from './states';
export { PagedList, type PagedListProps } from './paged-list';
export { StatusBadge, statusTone, statusLabel, type StatusBadgeProps, type StatusDomain, type StatusTone } from './status-badge';
export { Banner, type BannerProps, type BannerTone } from './banner';
export { Button, IconButton, type ButtonProps, type ButtonVariant, type IconButtonProps } from './button';

export {
  FormField, FormActions, FormError, useFieldIds,
  type FieldA11y, type FormActionsProps, type FormFieldProps,
} from './form-field';
export { TextField, SelectField, DateField, TimeField, SwitchField, TagListField, type BoundFieldProps } from './fields';
export {
  isDateFieldValue,
  type DateFieldProps, type FieldCommonProps, type SelectFieldProps, type SelectOption, type SwitchFieldProps,
  type TagListFieldProps, type TextFieldProps, type TimeFieldProps,
} from './fields-base';

export { Modal, SidePanel, type DialogCloseReason, type DialogProps, type DialogSize } from './modal';
export { Tabs, type TabItem, type TabsProps } from './tabs';
export { Card, SectionCard, InfoRow } from './card';

// Kept beside the primitives so one import covers a typical screen.
export type { PagedState } from '@/hooks/use-paged';
