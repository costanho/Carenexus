import { Ionicons } from '@expo/vector-icons';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { useCaregiverDependents } from '@/hooks/use-caregiver-dependents';

const WHITE  = '#FFFFFF';
const TEXT   = '#111827';
const GRAY   = '#6B7280';
const BORDER = '#E5E7EB';
const BG     = '#F9FAFB';
const PURPLE = '#7C3AED';
const PURPLE_BG = '#F5F3FF';
const GREEN  = '#10B981';
const ORANGE = '#F59E0B';
const RED    = '#EF4444';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];

interface GoalItem {
  label:    string;
  done:     boolean;
  due?:     string;
}
interface CarePlan {
  patientId:   number;
  patientName: string;
  diagnosis:   string[];
  status:      'ACTIVE' | 'REVIEW' | 'CRITICAL';
  doctor:      string;
  startDate:   string;
  nextReview:  string;
  goals:       GoalItem[];
  restrictions:string[];
  monitoring:  { label: string; icon: IoniconsName; value: string; alert?: boolean }[];
}

const CARE_PLANS: CarePlan[] = [
  {
    patientId: 3, patientName: 'Thomas Dube', status: 'REVIEW',
    diagnosis: ['Hypertension', 'Type 2 Diabetes Mellitus'],
    doctor: 'Dr. James Banda & Dr. Emeka Eze',
    startDate: '15 Jun 2026', nextReview: '10 Aug 2026',
    goals: [
      { label: 'Maintain BP < 130/80 mmHg',          done: false, due: 'Ongoing' },
      { label: 'Reduce HbA1c to < 7.0%',             done: false, due: 'Oct 2026' },
      { label: 'Take Amlodipine daily (morning)',     done: true  },
      { label: 'Take Metformin with meals (2x daily)',done: true  },
      { label: 'Take Atorvastatin at night',          done: true  },
      { label: 'Home BP monitoring (log readings)',   done: false, due: 'Daily' },
      { label: 'Fasting blood glucose test',         done: false, due: 'Before 20 Aug' },
    ],
    restrictions: [
      'Limit dietary sodium to < 2g per day',
      'Avoid grapefruit and grapefruit juice (Amlodipine interaction)',
      'Limit refined carbohydrates and sugary drinks',
      'Avoid alcohol (interferes with Metformin)',
    ],
    monitoring: [
      { label: 'Blood Pressure',    icon: 'pulse-outline',     value: 'Daily at home', alert: false },
      { label: 'Blood Glucose',     icon: 'water-outline',     value: 'Before meals',  alert: true  },
      { label: 'HbA1c',             icon: 'flask-outline',     value: 'Every 3 months',alert: true  },
      { label: 'Lipid Panel',       icon: 'stats-chart-outline',value: 'Every 3 months',alert: false },
    ],
  },
  {
    patientId: 4, patientName: 'Agnes Zimba', status: 'CRITICAL',
    diagnosis: ['Chronic Kidney Disease (Stage 3b)', 'Osteoarthritis'],
    doctor: 'Dr. Emeka Eze & Dr. James Banda',
    startDate: '1 Jul 2026', nextReview: '5 Aug 2026',
    goals: [
      { label: 'Slow CKD progression — maintain eGFR > 30',  done: false, due: 'Ongoing' },
      { label: 'Reduce proteinuria',                          done: false, due: 'Aug review' },
      { label: 'Take Furosemide (morning)',                   done: true  },
      { label: 'Take Calcium Carbonate with meals (2x)',      done: true  },
      { label: 'Take Tramadol only as needed for pain',       done: true  },
      { label: 'Daily weight monitoring',                     done: false, due: 'Daily' },
      { label: 'Physiotherapy session (weekly)',              done: false, due: 'Weekly' },
    ],
    restrictions: [
      'Strict fluid intake restriction: max 1.5L per day',
      'Low-potassium diet (avoid bananas, potatoes, tomatoes)',
      'Low-protein diet: max 0.6–0.8g protein per kg body weight',
      'Absolutely NO NSAIDs (Ibuprofen, Aspirin, Diclofenac) — contraindicated in CKD',
      'Limit phosphorus-rich foods (dairy, nuts, cola drinks)',
    ],
    monitoring: [
      { label: 'Daily Weight',   icon: 'scale-outline',        value: 'Every morning',   alert: true  },
      { label: 'eGFR / Creatinine', icon: 'flask-outline',    value: 'Monthly panel',   alert: true  },
      { label: 'Potassium',     icon: 'warning-outline',       value: 'With kidney panel',alert: true },
      { label: 'Pain Level',    icon: 'thermometer-outline',   value: 'Daily (scale 1-10)',alert: false },
    ],
  },
];

function statusColor(s: CarePlan['status']) {
  if (s === 'ACTIVE')   return { bg: '#D1FAE5', text: GREEN,  border: GREEN };
  if (s === 'REVIEW')   return { bg: '#FEF3C7', text: ORANGE, border: ORANGE };
  return { bg: '#FEE2E2', text: RED, border: RED };
}

function ProgressBar({ done, total }: { done: number; total: number }) {
  const pct = total === 0 ? 0 : Math.round((done / total) * 100);
  return (
    <View style={{ gap: 4 }}>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
        <Text style={{ fontSize: 11, color: GRAY }}>Goal Progress</Text>
        <Text style={{ fontSize: 11, fontWeight: '700', color: PURPLE }}>{pct}%</Text>
      </View>
      <View style={{ height: 6, backgroundColor: BORDER, borderRadius: 3 }}>
        <View style={{ height: 6, width: `${pct}%` as any, backgroundColor: pct >= 80 ? GREEN : pct >= 40 ? ORANGE : RED, borderRadius: 3 }} />
      </View>
    </View>
  );
}

function CarePlanCard({ plan }: { plan: CarePlan }) {
  const { bg, text, border } = statusColor(plan.status);
  const doneCnt = plan.goals.filter(g => g.done).length;

  return (
    <View style={[styles.card, { borderTopWidth: 3, borderTopColor: border }]}>
      {/* Header */}
      <View style={styles.cardHead}>
        <View style={{ flex: 1 }}>
          <Text style={styles.cardName}>{plan.patientName}</Text>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginTop: 6 }}>
            {plan.diagnosis.map(d => (
              <View key={d} style={styles.diagTag}>
                <Text style={styles.diagText}>{d}</Text>
              </View>
            ))}
          </View>
        </View>
        <View style={[styles.badge, { backgroundColor: bg }]}>
          <Text style={[styles.badgeText, { color: text }]}>{plan.status}</Text>
        </View>
      </View>

      {/* Meta */}
      <View style={styles.metaRow}>
        <View style={styles.metaItem}>
          <Ionicons name="person-outline" size={13} color={GRAY} />
          <Text style={styles.metaText}>{plan.doctor}</Text>
        </View>
        <View style={styles.metaItem}>
          <Ionicons name="calendar-outline" size={13} color={GRAY} />
          <Text style={styles.metaText}>Started {plan.startDate}</Text>
        </View>
        <View style={[styles.metaItem, { backgroundColor: '#FEF3C7', borderRadius: 6, paddingHorizontal: 8, paddingVertical: 3 }]}>
          <Ionicons name="alarm-outline" size={13} color={ORANGE} />
          <Text style={[styles.metaText, { color: ORANGE, fontWeight: '600' }]}>Next review: {plan.nextReview}</Text>
        </View>
      </View>

      <ProgressBar done={doneCnt} total={plan.goals.length} />

      {/* Goals */}
      <View>
        <Text style={styles.sectionTitle}>Treatment Goals</Text>
        <View style={styles.goalList}>
          {plan.goals.map((g, i) => (
            <View key={i} style={styles.goalRow}>
              <Ionicons
                name={g.done ? 'checkmark-circle' : 'ellipse-outline'}
                size={18}
                color={g.done ? GREEN : BORDER}
              />
              <Text style={[styles.goalLabel, g.done && styles.goalDone]}>{g.label}</Text>
              {g.due && !g.done && (
                <View style={styles.dueTag}>
                  <Text style={styles.dueText}>{g.due}</Text>
                </View>
              )}
            </View>
          ))}
        </View>
      </View>

      {/* Monitoring */}
      <View>
        <Text style={styles.sectionTitle}>Monitoring Checklist</Text>
        <View style={styles.monitorGrid}>
          {plan.monitoring.map(m => (
            <View key={m.label} style={[styles.monitorCard, m.alert && styles.monitorAlert]}>
              <Ionicons name={m.icon} size={18} color={m.alert ? RED : PURPLE} />
              <Text style={[styles.monitorLabel, m.alert && { color: RED }]}>{m.label}</Text>
              <Text style={styles.monitorValue}>{m.value}</Text>
            </View>
          ))}
        </View>
      </View>

      {/* Restrictions */}
      <View style={styles.restrictBox}>
        <View style={styles.restrictHeader}>
          <Ionicons name="warning-outline" size={16} color={ORANGE} />
          <Text style={styles.restrictTitle}>Dietary & Medication Restrictions</Text>
        </View>
        {plan.restrictions.map((r, i) => (
          <View key={i} style={styles.restrictRow}>
            <Text style={styles.restrictBullet}>•</Text>
            <Text style={styles.restrictText}>{r}</Text>
          </View>
        ))}
      </View>
    </View>
  );
}

export default function PatientProxyCarePlans() {
  const { dependents } = useCaregiverDependents();

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>
      <View style={styles.pageHeader}>
        <View>
          <Text style={styles.pageTitle}>Care Plans</Text>
          <Text style={styles.pageSub}>Active care plans for all linked patients</Text>
        </View>
      </View>

      {CARE_PLANS.map(plan => <CarePlanCard key={plan.patientId} plan={plan} />)}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll:     { flex: 1, backgroundColor: BG },
  container:  { padding: 24, paddingBottom: 48, gap: 20 },
  pageHeader: { marginBottom: 4 },
  pageTitle:  { fontSize: 22, fontWeight: '700', color: TEXT },
  pageSub:    { fontSize: 13, color: GRAY, marginTop: 3 },

  card:       { backgroundColor: WHITE, borderRadius: 14, borderWidth: 1, borderColor: BORDER, padding: 20, gap: 18 },
  cardHead:   { flexDirection: 'row', alignItems: 'flex-start', gap: 12 },
  cardName:   { fontSize: 18, fontWeight: '700', color: TEXT },
  badge:      { borderRadius: 20, paddingHorizontal: 12, paddingVertical: 4 },
  badgeText:  { fontSize: 12, fontWeight: '700' },

  diagTag:   { backgroundColor: PURPLE_BG, borderRadius: 20, paddingHorizontal: 10, paddingVertical: 3 },
  diagText:  { fontSize: 11, color: PURPLE, fontWeight: '600' },

  metaRow:  { flexDirection: 'row', flexWrap: 'wrap', gap: 12, alignItems: 'center' },
  metaItem: { flexDirection: 'row', alignItems: 'center', gap: 5 },
  metaText: { fontSize: 12, color: GRAY },

  sectionTitle: { fontSize: 13, fontWeight: '700', color: TEXT, marginBottom: 10 },

  goalList: { gap: 10 },
  goalRow:  { flexDirection: 'row', alignItems: 'center', gap: 10 },
  goalLabel:{ fontSize: 13, color: TEXT, flex: 1 },
  goalDone: { color: GRAY, textDecorationLine: 'line-through' },
  dueTag:   { backgroundColor: '#FEF3C7', borderRadius: 6, paddingHorizontal: 8, paddingVertical: 2 },
  dueText:  { fontSize: 11, color: ORANGE, fontWeight: '600' },

  monitorGrid:  { flexDirection: 'row', flexWrap: 'wrap', gap: 10 },
  monitorCard:  { backgroundColor: PURPLE_BG, borderRadius: 10, padding: 12, minWidth: 140, flex: 1, gap: 4 },
  monitorAlert: { backgroundColor: '#FEE2E2' },
  monitorLabel: { fontSize: 12, fontWeight: '700', color: PURPLE, marginTop: 4 },
  monitorValue: { fontSize: 11, color: GRAY },

  restrictBox:    { backgroundColor: '#FFFBEB', borderRadius: 10, padding: 14, borderWidth: 1, borderColor: '#FDE68A', gap: 8 },
  restrictHeader: { flexDirection: 'row', alignItems: 'center', gap: 6, marginBottom: 4 },
  restrictTitle:  { fontSize: 13, fontWeight: '700', color: ORANGE },
  restrictRow:    { flexDirection: 'row', gap: 8 },
  restrictBullet: { fontSize: 13, color: ORANGE, lineHeight: 20 },
  restrictText:   { fontSize: 12, color: TEXT, lineHeight: 20, flex: 1 },
});
