import { type ReactNode, useCallback, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import {
  Banner, colors, type EmptyAction, fontSize, type IoniconsName, LoadingView, PagedList, SelectField, space,
} from '@/components/web/ui';
import type { CarePlanBoard } from '@/hooks/use-care-plans';
import { type GoalActor, PLAN_STATUS_LABEL, toCarePlanStatus } from '@/lib/careplans/rules';
import { CARE_PLAN_STATUSES, type CarePlan, type CarePlanStatus } from '@/lib/careplans/types';
import { CarePlanCard } from './care-plan-card';

export interface CarePlanListProps {
  /** The list and the linked plan, from `useCarePlanBoard`. */
  board: CarePlanBoard;
  /** Opens a plan (its id goes into the `id` URL parameter) or closes the open one (null). */
  onOpenChange: (planId: number | null) => void;
  empty: { icon: IoniconsName; title: string; sub?: string; action?: EmptyAction | ReactNode };
  /**
   * Title and text of the empty state while a plan is shown opened above the list (it is not on the list, for
   * example a plan a link opened that the status filter leaves out). Default "No other care plans to show".
   */
  emptyBesideOpened?: { title: string; sub?: string };
  /** Filters shown above the list in every state. */
  header?: ReactNode;
  /** Names the list for assistive technology, for example "Care plans for Linda Moyo". */
  label?: string;
  /** Shown instead of the generic sentence when the server refuses the list (403). */
  noAccessMessage?: string;
  /** Who may update goal progress on a plan here; null or omitted for read-only cards. */
  goalActor?: (plan: CarePlan) => GoalActor | null;
  patientName?: (plan: CarePlan) => string | null | undefined;
  authorLabel?: (plan: CarePlan) => string | undefined;
  /** Buttons under an opened plan (the doctor's edit, review and status actions). */
  renderActions?: (plan: CarePlan) => ReactNode;
  headingLevel?: 2 | 3;
  /** A message from the owner to show above the list (a refused save, a confirmation). */
  notice?: { tone: 'success' | 'warning'; text: string } | null;
  onDismissNotice?: () => void;
}

/**
 * A paged list of care plans in every state, with one plan opened at a time. The plan named in the URL is
 * shown opened even when it is not on the loaded page (it is fetched on its own and listed first); when it
 * cannot be read the reason is shown above the list. Goal progress saved on a card is applied in place.
 */
export function CarePlanList({
  board, onOpenChange, empty, emptyBesideOpened, header, label, noAccessMessage, goalActor, patientName, authorLabel,
  renderActions, headingLevel = 2, notice, onDismissNotice,
}: CarePlanListProps) {
  const { list, openId, linked, apply, refresh } = board;
  // A goal update the server refused because the plan changed elsewhere: say why and show the current plan.
  const [stale, setStale] = useState<string | null>(null);
  const onStale = useCallback((message: string) => {
    setStale(message);
    refresh();
  }, [refresh]);

  const card = (plan: CarePlan, pinned: boolean) => {
    const open = pinned || plan.planId === openId;
    return (
      <CarePlanCard
        plan={plan}
        expanded={open}
        onToggle={() => onOpenChange(open ? null : plan.planId)}
        patientName={patientName?.(plan)}
        authorLabel={authorLabel?.(plan)}
        goalActor={goalActor?.(plan) ?? null}
        onPlanChanged={apply}
        onStale={onStale}
        actions={open ? renderActions?.(plan) : undefined}
        headingLevel={headingLevel}
      />
    );
  };

  let linkedView: ReactNode = null;
  if (openId !== null) {
    if (linked.loading) {
      linkedView = <LoadingView compact label="Loading the care plan" />;
    } else if (linked.plan) {
      linkedView = (
        <View style={styles.linked}>
          <Text style={styles.caption}>Opened care plan</Text>
          {card(linked.plan, true)}
        </View>
      );
    } else if (linked.error) {
      linkedView = linked.errorStatus === 403 ? (
        <Banner tone="warning" text="You don't have access to the care plan this link points to." onDismiss={() => onOpenChange(null)} />
      ) : linked.errorStatus === 404 ? (
        <Banner tone="warning" text="The care plan this link points to was not found." onDismiss={() => onOpenChange(null)} />
      ) : (
        <Banner tone="danger" text={linked.error} action={{ label: 'Retry', onPress: linked.retry }} onDismiss={() => onOpenChange(null)} />
      );
    }
  }

  // Everything above the rows goes into PagedList's header, in reading order: the caller's filters first, then
  // the messages, then the opened plan. The header stays mounted in every list state, so the filters keep their
  // focus, and the opened plan sits under the filters and above the list's own empty or error state.
  const showsMessages = !!notice || !!stale || linkedView !== null;
  const top = (header != null && header !== false) || showsMessages ? (
    <View style={styles.top}>
      {header}
      {notice ? <Banner tone={notice.tone} text={notice.text} onDismiss={onDismissNotice} /> : null}
      {stale ? <Banner tone="warning" text={stale} onDismiss={() => setStale(null)} /> : null}
      {linkedView}
    </View>
  ) : undefined;

  // The opened plan is not one of the rows, so an empty list means there are no other plans to show.
  const emptyState = linked.plan
    ? { ...empty, ...(emptyBesideOpened ?? { title: 'No other care plans to show', sub: undefined }) }
    : empty;

  return (
    <PagedList
      state={list}
      keyOf={plan => plan.planId}
      renderItem={plan => card(plan, false)}
      empty={emptyState}
      header={top}
      label={label}
      noAccessMessage={noAccessMessage}
      loadMoreLabel="Load more care plans"
    />
  );
}

const ALL = '';

/**
 * The status filter of a care-plan list. `value` comes from the `status` URL parameter; the empty choice lists
 * every status.
 */
export function CarePlanStatusFilter({ value, onChange }: {
  value:    CarePlanStatus | null;
  onChange: (status: CarePlanStatus | null) => void;
}) {
  return (
    <SelectField
      label="Status"
      value={value ?? ALL}
      onChange={next => onChange(toCarePlanStatus(next))}
      options={CARE_PLAN_STATUSES.map(s => ({ value: s as string, label: PLAN_STATUS_LABEL[s] }))}
      placeholder="All statuses"
      allowEmpty
    />
  );
}

const styles = StyleSheet.create({
  top:     { gap: space.md, minWidth: 0 },
  linked:  { gap: space.xs, minWidth: 0 },
  caption: { fontSize: fontSize.sm, fontWeight: '600', color: colors.textMuted },
});
