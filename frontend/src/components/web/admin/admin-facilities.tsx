import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Banner, Button, Card, colors, fontSize, FormActions, FormError, InfoRow, Modal, PagedList, Screen,
  SelectField, space, StatusBadge, TextField, type SelectOption,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { useAdminFacilities } from '@/hooks/use-admin-ops-facilities';
import { useDebouncedValue } from '@/hooks/use-admin-ops-debounced';
import { useSectionNav } from '@/hooks/use-section-nav';
import { createFacility, setFacilityActive, updateFacility } from '@/lib/admin/ops-api';
import {
  EMPTY_FACILITY_FORM, FACILITY_FORM_FIELDS, FACILITY_QUERY_MAX, facilitySchema, facilityToForm, toFacilityInput,
  type Facility, type FacilityActiveFilter, type FacilityFormValues,
} from '@/lib/admin/ops-types';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import { ROLE_THEME, type SectionProps } from '@/lib/nav/sections';
import { formatClinicDateTime } from '@/lib/time/clinic';
import { confirmAction } from '@/lib/ui/dialogs';

// Admin section `facilities`: the locations offered for in-person appointments and care teams. An administrator
// lists them in any state, adds one, edits its details and switches it on or off. Nothing is ever deleted.

const STATUS_OPTIONS: readonly SelectOption<FacilityActiveFilter>[] = [
  { value: 'all',      label: 'All facilities' },
  { value: 'active',   label: 'Active only' },
  { value: 'inactive', label: 'Inactive only' },
];

function FacilityDialog({ facility, onClose, onSaved }: {
  /** The facility being edited, or null to add one. */
  facility: Facility | null;
  onClose:  () => void;
  onSaved:  (saved: Facility, created: boolean) => void;
}) {
  const editing = facility != null;
  const key = useIdempotencyKey();
  const [formError, setFormError] = useState<string | null>(null);
  const { control, handleSubmit, setError, trigger, formState: { isDirty } } = useForm<FacilityFormValues>({
    resolver: zodResolver(facilitySchema),
    defaultValues: facility ? facilityToForm(facility) : EMPTY_FACILITY_FORM,
  });
  const save = useAction((values: FacilityFormValues) => {
    const input = toFacilityInput(values);
    return facility ? updateFacility(facility.facilityId, input) : createFacility(input, key.current());
  }, { errorMessage: 'The facility could not be saved.' });

  // A row stored before these rules existed (the development seed has placeholder phone numbers) would be
  // refused on save: point at the field as soon as the form opens instead of after the first attempt.
  useEffect(() => {
    if (editing) void trigger();
    // Runs once for the facility this dialog was opened for.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const submit = handleSubmit(async values => {
    setFormError(null);
    const result = await save.run(values);
    if (result.ok) {
      key.renew();
      onSaved(result.data, !editing);
    } else if (result.error) {
      setFormError(applyFieldErrors(result.error, setError, FACILITY_FORM_FIELDS));
    }
  });

  const close = async () => {
    if (isDirty && !(await confirmAction('Discard changes?', 'What you typed will not be saved.', 'Discard', true))) return;
    onClose();
  };

  return (
    <Modal
      open
      title={editing ? 'Edit facility' : 'Add facility'}
      onClose={() => { void close(); }}
      dismissOnBackdrop={false}
      busy={save.pending}
      footer={(
        <FormActions
          submitLabel={editing ? 'Save changes' : 'Add facility'}
          onSubmit={() => { void submit(); }}
          onCancel={() => { void close(); }}
          submitting={save.pending}
        />
      )}
    >
      <FormError message={formError} />
      <TextField control={control} name="name" label="Name" required maxLength={200} autoCapitalize="words" />
      <TextField
        control={control} name="type" label="Type" maxLength={80}
        hint="Optional. The kind of facility, for example Hospital, Clinic or Diagnostic Centre."
      />
      <TextField
        control={control} name="address" label="Address" maxLength={300}
        hint="Optional. Shown to patients with an in-person appointment."
      />
      <TextField
        control={control} name="phone" label="Phone" maxLength={20} inputMode="tel" autoComplete="tel"
        hint="Optional. 7 to 15 digits; may start with + and contain spaces, dots, dashes and brackets."
      />
      <TextField
        control={control} name="email" label="Email" maxLength={150} inputMode="email" autoComplete="email"
        autoCapitalize="none" hint="Optional."
      />
      {editing ? (
        <Text style={styles.note}>
          Saving replaces all five details. Clearing an optional field removes the stored value. Whether the facility
          is active does not change here.
        </Text>
      ) : (
        <Text style={styles.note}>A new facility is active at once and can be chosen for in-person appointments.</Text>
      )}
    </Modal>
  );
}

function FacilityRow({ facility, busy, disabled, onEdit, onToggle }: {
  facility: Facility;
  /** The activate or deactivate request of this row is running. */
  busy:     boolean;
  /** Another row's request is running. */
  disabled: boolean;
  onEdit:   () => void;
  onToggle: () => void;
}) {
  return (
    <Card style={styles.row}>
      <View style={styles.rowHead}>
        <View style={styles.rowTitles}>
          <Text style={styles.rowTitle} accessibilityRole="header" {...ariaLevel(2)}>{facility.name}</Text>
          {facility.type ? <Text style={styles.rowSub}>{facility.type}</Text> : null}
        </View>
        <StatusBadge
          label={facility.isActive ? 'Active' : 'Inactive'}
          tone={facility.isActive ? 'success' : 'neutral'}
          icon={facility.isActive ? 'checkmark-circle-outline' : 'remove-circle-outline'}
        />
      </View>
      <View>
        <InfoRow label="Address" value={facility.address} />
        <InfoRow label="Phone" value={facility.phone} />
        <InfoRow label="Email" value={facility.email} />
        <InfoRow label="Last changed" value={formatClinicDateTime(facility.updatedAt)} emptyText="Unknown" />
      </View>
      <View style={styles.rowActions}>
        <Button
          label="Edit" icon="create-outline" variant="secondary" size="sm" onPress={onEdit}
          disabled={busy || disabled} accessibilityLabel={`Edit ${facility.name}`}
        />
        {facility.isActive ? (
          <Button
            label="Deactivate" icon="remove-circle-outline" variant="secondary" size="sm" onPress={onToggle}
            loading={busy} disabled={disabled} accessibilityLabel={`Deactivate ${facility.name}`}
          />
        ) : (
          <Button
            label="Activate" icon="checkmark-circle-outline" variant="secondary" size="sm" onPress={onToggle}
            loading={busy} disabled={disabled} accessibilityLabel={`Activate ${facility.name}`}
          />
        )}
      </View>
    </Card>
  );
}

export default function AdminFacilities({ theme, params }: Partial<SectionProps>) {
  const nav = useSectionNav();
  const look = theme ?? ROLE_THEME.admin;
  const urlParams = params ?? nav.params;
  const active: FacilityActiveFilter =
    urlParams.status === 'active' || urlParams.status === 'inactive' ? urlParams.status : 'all';

  const [search, setSearch] = useState('');
  const query = useDebouncedValue(search.trim());
  const list = useAdminFacilities({ active, query });

  const [dialog, setDialog] = useState<{ facility: Facility | null } | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const toggle = useAction(
    (facility: Facility) => setFacilityActive(facility.facilityId, !facility.isActive),
    { errorMessage: 'The facility could not be changed.' },
  );

  const openDialog = (facility: Facility | null) => {
    setNotice(null);
    toggle.reset();
    setDialog({ facility });
  };

  const onSaved = (saved: Facility, created: boolean) => {
    setDialog(null);
    if (created) {
      // The list is sorted by name and may be filtered: ask the server where the new row belongs.
      list.refetch();
      setNotice(`${saved.name} was added. It is active and can be chosen for in-person appointments.`);
    } else {
      list.setItems(prev => prev.map(f => (f.facilityId === saved.facilityId ? saved : f)));
      setNotice(`${saved.name} was updated.`);
    }
  };

  const onToggle = async (facility: Facility) => {
    if (facility.isActive) {
      const confirmed = await confirmAction(
        `Deactivate ${facility.name}?`,
        'It will no longer be listed when an in-person appointment is booked or a care team entry is added. '
          + 'Existing appointments and care team entries keep it, and you can activate it again at any time.',
        'Deactivate',
        true,
      );
      if (!confirmed) return;
    }
    setNotice(null);
    setBusyId(facility.facilityId);
    const result = await toggle.run(facility);
    setBusyId(null);
    if (!result.ok) return;
    const changed = result.data;
    if (active === 'all') {
      list.setItems(prev => prev.map(f => (f.facilityId === changed.facilityId ? changed : f)));
    } else {
      // The row no longer matches the status filter.
      list.refetch();
    }
    setNotice(changed.isActive
      ? `${changed.name} is active again and can be chosen for in-person appointments.`
      : `${changed.name} was deactivated. Existing appointments keep it.`);
  };

  const filtered = active !== 'all' || query !== '';

  return (
    <Screen
      title="Facilities"
      subtitle="Locations that can be chosen for in-person appointments and care teams. A facility is never deleted: deactivate it to take it out of use."
      theme={look}
      actions={<Button label="Add facility" icon="add" onPress={() => openDialog(null)} />}
    >
      {notice ? <Banner tone="success" text={notice} onDismiss={() => setNotice(null)} /> : null}
      {toggle.error ? <Banner tone="danger" text={toggle.error} onDismiss={toggle.reset} /> : null}

      <PagedList
        state={list}
        label="Facilities"
        keyOf={f => f.facilityId}
        header={(
          <View style={styles.filters}>
            <View style={styles.filter}>
              <SelectField<FacilityActiveFilter>
                label="Status"
                value={active}
                options={STATUS_OPTIONS}
                onChange={value => nav.setParams({ status: value === 'all' ? undefined : value })}
              />
            </View>
            <View style={styles.filterWide}>
              <TextField
                label="Search"
                value={search}
                onChange={setSearch}
                maxLength={FACILITY_QUERY_MAX}
                placeholder="Name or address"
                autoCapitalize="none"
              />
            </View>
          </View>
        )}
        renderItem={facility => (
          <FacilityRow
            facility={facility}
            busy={busyId === facility.facilityId}
            disabled={busyId != null && busyId !== facility.facilityId}
            onEdit={() => openDialog(facility)}
            onToggle={() => { void onToggle(facility); }}
          />
        )}
        empty={filtered
          ? { icon: 'search-outline', title: 'No facilities match', sub: 'Change the status filter or the search text.' }
          : {
              icon: 'business-outline',
              title: 'No facilities yet',
              sub: 'Add the first facility so in-person appointments can name a location.',
              action: { label: 'Add facility', icon: 'add', onPress: () => openDialog(null) },
            }}
      />

      {dialog ? (
        <FacilityDialog
          key={dialog.facility?.facilityId ?? 'new'}
          facility={dialog.facility}
          onClose={() => setDialog(null)}
          onSaved={onSaved}
        />
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  filters:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: space.md },
  filter:     { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, maxWidth: 320 },
  filterWide: { flexGrow: 2, flexShrink: 1, flexBasis: 240, minWidth: 0, maxWidth: 480 },

  row:        { gap: space.sm },
  rowHead:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.sm },
  rowTitles:  { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, gap: 2 },
  rowTitle:   { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  rowSub:     { fontSize: fontSize.sm, color: colors.textMuted },
  rowActions: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },

  note: { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
});
