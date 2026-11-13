import { type ReactNode, useMemo } from 'react';
import { StyleSheet, View } from 'react-native';
import {
  EmptyView, ErrorView, LoadingView, ROLE_THEME, type RoleTheme, Screen, space, Tabs, type TabItem,
} from '@/components/web/ui';
import { useCaregiverDependents, type CaregiverDependent } from '@/hooks/use-caregiver-dependents';
import { useSectionNav } from '@/hooks/use-section-nav';
import type { SectionParams, SectionParamsInput, SectionProps } from '@/lib/nav/sections';
import {
  type AttachmentRenderer, ConsultationsTab, DependentFilter, type DependentRecordCardActions, ImagingResultsTab,
  KIND_TEXT, LabResultsTab, MedicalRecordsTab, parseId, RECORD_KINDS, type RecordKind, scopeDependents,
} from './dependent-records';

// Caregiver "Documents & Results": the clinical records of the caregiver's loved ones, one tab per kind.
//
// URL (`/dashboard/proxy?section=documents-results&...`):
//   tab      labs (default) | imaging | records | consultations | the key of an extra tab (the dashboard adds
//            documents, referrals and corrections)
//   patient  PAT id: show this loved one only (links from a loved one's profile and from notifications)
//   id       the record open in the detail panel of the active tab
//
// Every built-in tab is gated per loved one with the shared caregiver permission rule
// (`@/lib/caregiver/permissions`): lab results need canViewLabResults, imaging canViewImaging, medical records
// canViewRecords and consultations canViewConsultations (FULL_ACCESS grants all of them).

/** What an extra tab gets to render with. */
export interface ProxyDocumentsTabContext {
  /** The loved ones in view: all of them, or the one the `patient` URL param names. */
  dependents:      CaregiverDependent[];
  /** The `patient` URL param when it names a linked loved one, else null. */
  patientSystemId: string | null;
  /** URL params of the section (`id`, `tab`, `patient` and any other). */
  params:          SectionParams;
  /** Updates URL params in place (the screen stays mounted); undefined removes a param. */
  setParams:       (params: SectionParamsInput) => void;
  navigate:        SectionProps['navigate'];
  theme:           RoleTheme;
}

/** A tab added after the built-in ones (documents, referrals, correction requests). */
export interface ProxyDocumentsExtraTab {
  /** The `tab` URL param value, for example `documents`. A key of a built-in tab is ignored. */
  key:    string;
  label:  string;
  /** A count shown on the tab. */
  badge?: number;
  render: (context: ProxyDocumentsTabContext) => ReactNode;
}

export interface PatientProxyDocumentsProps extends Partial<SectionProps> {
  /** Tabs shown after Lab results, Imaging, Medical records and Consultations. */
  extraTabs?:        readonly ProxyDocumentsExtraTab[];
  /**
   * Renders the files attached to a lab result, imaging result or medical record inside its detail view (a
   * download control). Without it nothing is shown for attachments.
   */
  renderAttachment?: AttachmentRenderer;
  /** More controls on the record cards of the built-in tabs, per kind (for example "Request a correction"). */
  cardActions?:      DependentRecordCardActions;
}

const DEFAULT_TAB: RecordKind = 'labs';

function isRecordKind(key: string | undefined): key is RecordKind {
  return !!key && (RECORD_KINDS as readonly string[]).includes(key);
}

export default function PatientProxyDocuments({
  params: paramsProp, navigate: navigateProp, theme: themeProp, extraTabs, renderAttachment, cardActions,
}: PatientProxyDocumentsProps = {}) {
  // The registry may render this screen without props; the dashboard's navigation is then read from context.
  const nav = useSectionNav();
  const params = paramsProp ?? nav.params;
  const navigate = navigateProp ?? nav.navigate;
  const { setParams } = nav;
  const theme = themeProp ?? ROLE_THEME.proxy;

  const { dependents, loading, error, refetch } = useCaregiverDependents();
  const scope = useMemo(() => scopeDependents(dependents, params.patient), [dependents, params.patient]);

  const extras = useMemo(
    () => (extraTabs ?? []).filter(tab => !isRecordKind(tab.key)),
    [extraTabs],
  );
  const tabs = useMemo<TabItem[]>(() => [
    ...RECORD_KINDS.map(kind => ({ key: kind as string, label: KIND_TEXT[kind].tab })),
    ...extras.map(tab => ({ key: tab.key, label: tab.label, badge: tab.badge })),
  ], [extras]);

  // An unknown `tab` value (a stale link, or a tab that is not installed) opens the first tab.
  const requested = params.tab;
  const active = requested && tabs.some(tab => tab.key === requested) ? requested : DEFAULT_TAB;
  const openId = parseId(params.id);

  // The open record belongs to the tab it was opened in, so changing tab closes it.
  const selectTab = (key: string) => setParams({ tab: key, id: undefined });
  const openRecord = (id: number | null) => setParams({ id: id ?? undefined });
  const selectPatient = (patientSystemId: string | null) => setParams({ patient: patientSystemId ?? undefined, id: undefined });

  let content: ReactNode;
  if (loading) {
    content = <LoadingView label="Loading your loved ones" />;
  } else if (error) {
    content = <ErrorView message={error} onRetry={refetch} />;
  } else if (dependents.length === 0) {
    content = (
      <EmptyView
        icon="people-outline"
        title="No loved ones linked yet"
        sub="When you are linked to a loved one, the records they share with you appear here."
        action={{ label: 'Add a loved one', icon: 'person-add-outline', onPress: () => navigate('add-dependent') }}
      />
    );
  } else if (scope.unknown) {
    content = (
      <EmptyView
        icon="person-outline"
        title="This person is not one of your loved ones"
        sub="The link may be out of date, or your access to them has ended."
        action={{ label: 'Show all loved ones', onPress: () => selectPatient(null) }}
      />
    );
  } else {
    const tabProps = { dependents: scope.dependents, openId, onOpen: openRecord };
    const extra = extras.find(tab => tab.key === active);
    let panel: ReactNode;
    if (extra) {
      panel = extra.render({
        dependents: scope.dependents, patientSystemId: scope.selectedId, params, setParams, navigate, theme,
      });
    } else if (active === 'imaging') {
      panel = <ImagingResultsTab {...tabProps} renderAttachment={renderAttachment} cardActions={cardActions?.imaging} />;
    } else if (active === 'records') {
      panel = <MedicalRecordsTab {...tabProps} renderAttachment={renderAttachment} cardActions={cardActions?.records} />;
    } else if (active === 'consultations') {
      panel = (
        <ConsultationsTab
          {...tabProps}
          onOpenRecord={recordId => setParams({ tab: 'records', id: recordId })}
          cardActions={cardActions?.consultations}
        />
      );
    } else {
      panel = <LabResultsTab {...tabProps} renderAttachment={renderAttachment} cardActions={cardActions?.labs} />;
    }
    const activeLabel = tabs.find(tab => tab.key === active)?.label;
    content = (
      <>
        <DependentFilter dependents={dependents} selectedId={scope.selectedId} onChange={selectPatient} />
        <Tabs tabs={tabs} active={active} onChange={selectTab} label="Kinds of records" />
        {/* Keyed by tab: each tab starts with its own filters and lists. */}
        <View key={active} style={styles.panel} role="tabpanel" aria-label={activeLabel}>
          {panel}
        </View>
      </>
    );
  }

  return (
    <Screen
      title="Documents & Results"
      subtitle="Lab results, imaging, medical records and consultations your loved ones share with you"
      theme={theme}
    >
      {content}
    </Screen>
  );
}

const styles = StyleSheet.create({
  panel: { gap: space.lg, minWidth: 0 },
});
