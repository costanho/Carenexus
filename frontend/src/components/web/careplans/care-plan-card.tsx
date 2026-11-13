import type { ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import {
  ariaLevel, Card, colors, CONTROL_HEIGHT, fontSize, Icon, InfoRow, radius, space, StatusBadge, statusTone, useUiTheme,
} from '@/components/web/ui';
import { DOCTOR_NAME_FALLBACK, useDoctorName } from '@/lib/doctors/names';
import {
  canUpdateGoal, GOAL_STATUS_LABEL, type GoalActor, goalSummary, isReviewDue, PLAN_STATUS_LABEL,
} from '@/lib/careplans/rules';
import type { CarePlan, CarePlanGoal, CarePlanIntervention } from '@/lib/careplans/types';
import { formatClinicDateTime } from '@/lib/time/clinic';
import { formatDateOnly } from '@/lib/ui/date-only';
import { GoalProgressControl } from './goal-progress-control';

export interface CarePlanCardProps {
  plan:      CarePlan;
  /** Whether the goals, interventions and review details are shown. */
  expanded:  boolean;
  onToggle:  () => void;
  /** Name of the patient the plan is for; shown on screens that list plans of several people. */
  patientName?: string | null;
  /** Replaces the author's name, for example "You" on the doctor's own plans. */
  authorLabel?: string;
  /** Who may update goal progress on this plan here, or null for a read-only card. */
  goalActor?:   GoalActor | null;
  /** Called with the plan as returned by the server after a goal was updated. */
  onPlanChanged?: (plan: CarePlan) => void;
  /** A goal update was refused because the plan changed elsewhere or access ended. */
  onStale?:     (message: string) => void;
  /** Buttons under the details (edit, mark reviewed, status changes on the doctor screen). */
  actions?:     ReactNode;
  /** Heading level of the plan title: 2 directly under the screen title, 3 inside a titled section. */
  headingLevel?: 2 | 3;
}

const byPosition = (a: { position: number }, b: { position: number }) => a.position - b.position;

function GoalRow({ plan, goal, actor, onPlanChanged, onStale }: {
  plan:  CarePlan;
  goal:  CarePlanGoal;
  actor: GoalActor | null | undefined;
  onPlanChanged?: (plan: CarePlan) => void;
  onStale?: (message: string) => void;
}) {
  const editable = !!actor && !!onPlanChanged && canUpdateGoal(plan, goal, actor);
  return (
    <View style={styles.item} role="listitem">
      <View style={styles.itemHead}>
        <Text style={styles.itemText}>{goal.description}</Text>
        <StatusBadge label={GOAL_STATUS_LABEL[goal.status] ?? goal.status} tone={statusTone('goal', goal.status)} />
      </View>
      <Text style={styles.muted}>
        {goal.targetDate ? `Target date: ${formatDateOnly(goal.targetDate)}` : 'No target date'}
      </Text>
      {goal.progressNote ? <Text style={styles.note}>Progress: {goal.progressNote}</Text> : null}
      <Text style={styles.muted}>Last updated {formatClinicDateTime(goal.updatedAt, 'at an unknown time')}</Text>
      {editable && actor && onPlanChanged ? (
        <GoalProgressControl planId={plan.planId} goal={goal} actor={actor} onSaved={onPlanChanged} onStale={onStale} />
      ) : null}
    </View>
  );
}

function InterventionRow({ intervention }: { intervention: CarePlanIntervention }) {
  const details = [
    intervention.frequency ? `Frequency: ${intervention.frequency}` : null,
    intervention.responsible ? `Responsible: ${intervention.responsible}` : null,
  ].filter(Boolean).join('  ·  ');
  return (
    <View style={styles.item} role="listitem">
      <Text style={styles.itemText}>{intervention.description}</Text>
      {details ? <Text style={styles.muted}>{details}</Text> : null}
    </View>
  );
}

/**
 * One care plan: title, status and next review date, and (when expanded) its description, dates, goals with
 * their progress, interventions and the last review. Goal progress can be updated in place when `goalActor`
 * is given; everything else is read-only here.
 */
export function CarePlanCard({
  plan, expanded, onToggle, patientName, authorLabel, goalActor, onPlanChanged, onStale, actions, headingLevel = 2,
}: CarePlanCardProps) {
  const { accent } = useUiTheme();
  const resolvedName = useDoctorName(plan.doctorSystemId, authorLabel);
  // The lookup's fallback reads "Your doctor", which is wrong for a caregiver or another doctor reading the plan.
  const doctorName = resolvedName === DOCTOR_NAME_FALLBACK ? 'a doctor' : resolvedName;
  const reviewDue = isReviewDue(plan);
  const subLevel = (headingLevel + 1) as 3 | 4;

  const goals = [...plan.goals].sort(byPosition);
  const interventions = [...plan.interventions].sort(byPosition);

  const summary = [
    patientName ? `For ${patientName}` : null,
    `Written by ${doctorName}`,
    `Started ${formatDateOnly(plan.startDate)}`,
    plan.reviewDate ? `Next review ${formatDateOnly(plan.reviewDate)}` : null,
    goalSummary(goals),
  ].filter(Boolean).join('  ·  ');

  return (
    <Card style={styles.card}>
      <View style={styles.head}>
        <View style={styles.titles}>
          <Text style={styles.title} accessibilityRole="header" {...ariaLevel(headingLevel)}>{plan.title}</Text>
          <View style={styles.badges}>
            <StatusBadge label={PLAN_STATUS_LABEL[plan.status] ?? plan.status} tone={statusTone('carePlan', plan.status)} />
            {reviewDue ? <StatusBadge label="Review due" tone="warning" icon="calendar-outline" /> : null}
          </View>
        </View>
        <Pressable
          onPress={onToggle}
          accessibilityRole="button"
          accessibilityLabel={`${expanded ? 'Hide details' : 'Show details'}, ${plan.title}`}
          aria-expanded={expanded}
          style={({ pressed }) => [styles.toggle, pressed && styles.pressed]}
        >
          <Text style={[styles.toggleText, { color: accent }]}>{expanded ? 'Hide details' : 'Show details'}</Text>
          <Icon name={expanded ? 'chevron-up' : 'chevron-down'} size={16} color={accent} />
        </Pressable>
      </View>
      <Text style={styles.summary}>{summary}</Text>

      {expanded ? (
        <View style={styles.details}>
          {plan.description ? <Text style={styles.description}>{plan.description}</Text> : null}

          <View>
            <InfoRow label="Start date" value={formatDateOnly(plan.startDate)} />
            <InfoRow label="Target date" value={plan.targetDate ? formatDateOnly(plan.targetDate) : null} emptyText="Not set" />
            <InfoRow
              label="Next review"
              value={plan.reviewDate ? `${formatDateOnly(plan.reviewDate)}${reviewDue ? ' (due)' : ''}` : null}
              emptyText="None scheduled"
            />
            <InfoRow
              label="Last reviewed"
              value={plan.lastReviewedAt ? formatClinicDateTime(plan.lastReviewedAt) : null}
              emptyText="Not reviewed yet"
            />
            {plan.lastReviewNote ? <InfoRow label="Review note" value={plan.lastReviewNote} /> : null}
            <InfoRow label="Last changed" value={formatClinicDateTime(plan.updatedAt)} emptyText="Unknown" />
          </View>

          <View style={styles.block}>
            <Text style={styles.blockTitle} accessibilityRole="header" {...ariaLevel(subLevel)}>Goals</Text>
            {goals.length === 0 ? (
              <Text style={styles.muted}>No goals have been set for this plan.</Text>
            ) : (
              <View style={styles.items} role="list" aria-label={`Goals of ${plan.title}`}>
                {goals.map(goal => (
                  <GoalRow
                    key={goal.goalId}
                    plan={plan}
                    goal={goal}
                    actor={goalActor}
                    onPlanChanged={onPlanChanged}
                    onStale={onStale}
                  />
                ))}
              </View>
            )}
          </View>

          <View style={styles.block}>
            <Text style={styles.blockTitle} accessibilityRole="header" {...ariaLevel(subLevel)}>Interventions</Text>
            {interventions.length === 0 ? (
              <Text style={styles.muted}>No interventions have been set for this plan.</Text>
            ) : (
              <View style={styles.items} role="list" aria-label={`Interventions of ${plan.title}`}>
                {interventions.map((intervention, index) => (
                  // Intervention ids change on every edit of the plan, so the position is the key.
                  <InterventionRow key={index} intervention={intervention} />
                ))}
              </View>
            )}
          </View>

          {actions ? <View style={styles.actions}>{actions}</View> : null}
        </View>
      ) : null}
    </Card>
  );
}

const styles = StyleSheet.create({
  card:    { gap: space.sm },
  head:    { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.sm },
  titles:  { flexGrow: 1, flexShrink: 1, flexBasis: 200, minWidth: 0, gap: space.xs },
  title:   { fontSize: fontSize.lg, fontWeight: '700', color: colors.text },
  badges:  { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs },
  toggle: {
    minHeight: CONTROL_HEIGHT, flexDirection: 'row', alignItems: 'center', gap: space.xs,
    paddingHorizontal: space.sm, borderRadius: radius.md,
  },
  toggleText: { fontSize: fontSize.sm, fontWeight: '600' },
  pressed:    { opacity: 0.7 },
  summary:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  details:     { gap: space.lg, paddingTop: space.md, borderTopWidth: 1, borderTopColor: colors.border },
  description: { fontSize: fontSize.md, color: colors.text, lineHeight: 21 },
  block:       { gap: space.sm },
  blockTitle:  { fontSize: fontSize.md, fontWeight: '700', color: colors.text },
  items:       { gap: space.sm },
  item: {
    gap: space.xs, padding: space.md, minWidth: 0,
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.surface,
  },
  itemHead: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', justifyContent: 'space-between', gap: space.sm },
  itemText: { flexGrow: 1, flexShrink: 1, flexBasis: 180, minWidth: 0, fontSize: fontSize.md, fontWeight: '600', color: colors.text, lineHeight: 20 },
  note:     { fontSize: fontSize.sm, color: colors.text, lineHeight: 19 },
  muted:    { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  actions: {
    flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm,
    paddingTop: space.md, borderTopWidth: 1, borderTopColor: colors.border,
  },
});
