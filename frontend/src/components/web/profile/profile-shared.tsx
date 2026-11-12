import { type ReactNode, useCallback, useEffect, useRef, useState } from 'react';
import type { Control, FieldValues, Path, UseFormHandleSubmit, UseFormSetError } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  Button, Card, colors, decorative, fontSize, InfoRow, IS_WEB, radius, SectionCard, space, Tabs, TextField, useUiTheme,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { useSectionNav } from '@/hooks/use-section-nav';
import type { ApiError } from '@/lib/api/errors';
import { applyFieldErrors } from '@/lib/api/form-errors';
import type { SectionParams } from '@/lib/nav/sections';
import { type AccountDetailsForm, PROFILE_LIMITS } from '@/lib/validation/profile';
import { PhoneField } from './phone-field';

// Pieces shared by the three profile screens (patient, doctor, caregiver): the Profile / Security tabs, the
// identity header, the edit-in-place section card and small helpers for the edit forms.

// ─── Tabs ─────────────────────────────────────────────────────────────────────

export type ProfileTab = 'profile' | 'security';

/**
 * The tab of a profile screen, kept in the URL (`?tab=security`) so a refresh, Back and links keep it.
 * The Security tab exists only when the screen was given a security panel; without one every value of `tab`
 * shows the profile.
 */
export function useProfileTab(
  params: SectionParams | undefined,
  hasSecurity: boolean,
): { tab: ProfileTab; setTab: (tab: ProfileTab) => void } {
  const nav = useSectionNav();
  const requested = (params ?? nav.params).tab;
  const tab: ProfileTab = hasSecurity && requested === 'security' ? 'security' : 'profile';
  const { setParams } = nav;
  const setTab = useCallback((next: ProfileTab) => {
    setParams({ tab: next === 'security' ? 'security' : undefined });
  }, [setParams]);
  return { tab, setTab };
}

const TABS: readonly { key: ProfileTab; label: string }[] = [
  { key: 'profile',  label: 'Profile' },
  { key: 'security', label: 'Security' },
];

/**
 * The body of a profile screen. With a `securityPanel` it shows the Profile and Security tabs; the profile
 * stays mounted (hidden) while Security is open, so an edit in progress is not thrown away by a look at the
 * other tab. Without a panel there are no tabs and only the profile is shown.
 */
export function ProfileTabsBody({ tab, onTabChange, securityPanel, children }: {
  tab:            ProfileTab;
  onTabChange:    (tab: ProfileTab) => void;
  /** Content of the Security tab (password, two-factor, sessions, email change). */
  securityPanel?: ReactNode;
  /** The profile itself. */
  children:       ReactNode;
}) {
  if (!securityPanel) return <View style={styles.stack}>{children}</View>;
  const onSecurity = tab === 'security';
  return (
    <>
      <Tabs tabs={TABS} active={tab} onChange={onTabChange} label="Profile sections" />
      <View style={[styles.stack, onSecurity && styles.hidden]} role="tabpanel" aria-label="Profile" aria-hidden={onSecurity}>
        {children}
      </View>
      {onSecurity ? (
        <View style={styles.stack} role="tabpanel" aria-label="Security">
          {securityPanel}
        </View>
      ) : null}
    </>
  );
}

// ─── Identity header ──────────────────────────────────────────────────────────

export function initialsOf(...names: (string | null | undefined)[]): string {
  const letters = names
    .flatMap(n => (n ?? '').trim().split(/\s+/))
    .filter(Boolean)
    // A leading title is not part of the initials ("Dr. James Banda" is JB).
    .filter(word => !/^dr\.?$/i.test(word))
    .map(word => word[0]);
  return letters.slice(0, 2).join('').toUpperCase();
}

/** The card at the top of a profile: initials, name, badges and a few facts (id, member since). */
export function ProfileIdentity({ name, initials, tagline, badges, facts }: {
  name:      string;
  initials:  string;
  /** One line under the name (a doctor's specialization, a caregiver's role). */
  tagline?:  string | null;
  /** Status badges shown under the name. */
  badges?:   ReactNode;
  facts?:    readonly { label: string; value: string }[];
}) {
  const { accent, accentText } = useUiTheme();
  return (
    <Card style={styles.identity}>
      <View style={[styles.avatar, { backgroundColor: accent }]} {...decorative}>
        <Text style={[styles.avatarText, { color: accentText }]}>{initials || '?'}</Text>
      </View>
      <View style={styles.identityMain}>
        <Text style={styles.identityName}>{name}</Text>
        {tagline ? <Text style={styles.identityTagline}>{tagline}</Text> : null}
        {badges ? <View style={styles.badgeRow}>{badges}</View> : null}
      </View>
      {facts && facts.length > 0 ? (
        <View style={styles.facts}>
          {facts.map(fact => (
            <View key={fact.label} style={styles.fact}>
              <Text style={styles.factLabel}>{fact.label}</Text>
              <Text style={styles.factValue}>{fact.value}</Text>
            </View>
          ))}
        </View>
      ) : null}
    </Card>
  );
}

// ─── Edit-in-place section ────────────────────────────────────────────────────

function focusFirstButton(container: unknown): void {
  if (!IS_WEB) return;
  const node = container as { querySelector?: (selector: string) => { focus?: () => void } | null } | null;
  node?.querySelector?.('[role="button"]')?.focus?.();
}

/**
 * A section card whose content switches between a read view and an edit form. The Edit button sits beside
 * the heading; when the form closes (saved or cancelled) keyboard focus goes back to it.
 */
export function EditableSection({ title, subtitle, editing, onEdit, editLabel, children }: {
  title:      string;
  subtitle?:  string;
  editing:    boolean;
  onEdit:     () => void;
  /** What is edited, for the Edit button's accessible name: "Edit personal information". */
  editLabel:  string;
  children:   ReactNode;
}) {
  const anchor = useRef<View>(null);
  const wasEditing = useRef(editing);
  useEffect(() => {
    if (wasEditing.current && !editing) focusFirstButton(anchor.current);
    wasEditing.current = editing;
  }, [editing]);

  return (
    <SectionCard
      title={title}
      subtitle={subtitle}
      style={styles.cell}
      action={editing ? undefined : (
        <View ref={anchor}>
          <Button label="Edit" accessibilityLabel={editLabel} icon="create-outline" variant="secondary" size="sm" onPress={onEdit} />
        </View>
      )}
    >
      {children}
    </SectionCard>
  );
}

/** Two columns of cards on a wide window, one column on a phone. Children take `profileCell` as their style. */
export function ProfileGrid({ children }: { children: ReactNode }) {
  return <View style={styles.grid}>{children}</View>;
}

/** Style for a card placed in a `ProfileGrid`. */
export const profileCell = { flexGrow: 1, flexShrink: 1, flexBasis: 320, minWidth: 0 } as const;

/** Layout of an edit form inside a section card. */
export function ProfileForm({ children }: { children: ReactNode }) {
  return <View style={styles.form}>{children}</View>;
}

// ─── Email row ────────────────────────────────────────────────────────────────

/**
 * The sign-in email, read-only: it changes only through the confirmed email-change flow of the Security tab.
 * `onOpenSecurity` is passed when the screen has that tab.
 */
export function EmailRow({ email, onOpenSecurity }: { email: string | null; onOpenSecurity?: () => void }) {
  return (
    <View style={styles.emailRow}>
      <InfoRow label="Email" value={email} emptyText="No sign-in email" />
      <View style={styles.emailNote}>
        <Text style={styles.note}>
          {onOpenSecurity
            ? 'This is the email you sign in with. Change it from the Security tab.'
            : 'This is the email you sign in with. It cannot be edited here.'}
        </Text>
        {onOpenSecurity ? (
          <Button
            label="Change email"
            accessibilityLabel="Change email in the Security tab"
            variant="ghost"
            size="sm"
            onPress={onOpenSecurity}
          />
        ) : null}
      </View>
    </View>
  );
}

// ─── Form helpers ─────────────────────────────────────────────────────────────

/**
 * Puts a failed save onto the form: field errors from the server go under their fields, and a phone number
 * that another account already uses (409) goes under the phone field. Returns the message for `FormError`, or
 * null when everything landed on a field.
 */
export function applyProfileErrors<T extends FieldValues>(
  error: ApiError,
  setError: UseFormSetError<T>,
  fields: readonly Path<T>[],
): string | null {
  const phone = fields.find(f => f === 'phone');
  if (phone && error.status === 409 && /phone/i.test(error.message)) {
    setError(phone, { type: 'server', message: error.message });
    return null;
  }
  return applyFieldErrors(error, setError, fields);
}

/**
 * Submit handling shared by the profile edit forms. Only the fields that differ from the stored profile are
 * sent; a form saved without a change closes without a request. `onDone(true)` follows a successful save,
 * `onDone(false)` a submit with nothing to save.
 */
export function useProfileSave<T extends FieldValues, C extends object>({ handleSubmit, setError, fields, changesOf, save, onDone }: {
  handleSubmit: UseFormHandleSubmit<T>;
  setError:     UseFormSetError<T>;
  /** The form's field names, to place the server's field errors. */
  fields:       readonly Path<T>[];
  changesOf:    (values: T) => C;
  save:         (changes: C) => Promise<unknown>;
  onDone:       (saved: boolean) => void;
}): { submit: () => void; pending: boolean; formError: string | null } {
  const [formError, setFormError] = useState<string | null>(null);
  const action = useAction(save, { errorMessage: 'Your changes could not be saved. Please try again.' });

  const run = handleSubmit(async values => {
    const changes = changesOf(values);
    setFormError(null);
    if (Object.keys(changes).length === 0) { onDone(false); return; }
    const result = await action.run(changes);
    if (result.ok) onDone(true);
    else if (result.error) setFormError(applyProfileErrors(result.error, setError, fields));
  });

  return { submit: () => { void run(); }, pending: action.pending, formError };
}

/** First name, last name and phone: the account fields every profile form starts with. */
export function NamePhoneFields<T extends AccountDetailsForm & FieldValues>({ control, onSubmit, phoneRequired = false }: {
  control:        Control<T>;
  /** Called when Enter is pressed in a name field. */
  onSubmit?:      () => void;
  /** True once a phone number is saved: the schema then refuses an empty value, so the field is marked required. */
  phoneRequired?: boolean;
}) {
  return (
    <>
      <TextField
        label="First name" control={control} name={'firstName' as Path<T>} required
        maxLength={PROFILE_LIMITS.firstName} autoComplete="given-name" autoCapitalize="words" onSubmit={onSubmit}
      />
      <TextField
        label="Last name" control={control} name={'lastName' as Path<T>} required
        maxLength={PROFILE_LIMITS.lastName} autoComplete="family-name" autoCapitalize="words" onSubmit={onSubmit}
      />
      <PhoneField
        label="Phone number" control={control} name={'phone' as Path<T>} required={phoneRequired}
        hint="Choose the country code, then enter the number."
      />
    </>
  );
}

const styles = StyleSheet.create({
  stack:  { gap: space.lg, minWidth: 0 },
  hidden: { display: 'none' },

  identity:        { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.lg },
  avatar:          { width: 64, height: 64, borderRadius: radius.pill, alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  avatarText:      { fontSize: 22, fontWeight: '700' },
  identityMain:    { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, gap: space.xs },
  identityName:    { fontSize: fontSize.xl, fontWeight: '700', color: colors.text },
  identityTagline: { fontSize: fontSize.md, color: colors.textMuted },
  badgeRow:        { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, marginTop: 2 },
  facts:           { flexDirection: 'row', flexWrap: 'wrap', columnGap: space.xl, rowGap: space.sm, maxWidth: '100%' },
  fact:            { gap: 2, minWidth: 0 },
  factLabel:       { fontSize: fontSize.xs, color: colors.textMuted },
  factValue:       { fontSize: fontSize.md, fontWeight: '700', color: colors.text },

  grid: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.lg },
  cell: profileCell,

  form: { gap: space.md, minWidth: 0 },

  emailRow:  { gap: 2 },
  emailNote: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', columnGap: space.sm, rowGap: 2 },
  note:      { flexShrink: 1, minWidth: 0, fontSize: fontSize.xs, color: colors.textMuted, lineHeight: 17 },
});
