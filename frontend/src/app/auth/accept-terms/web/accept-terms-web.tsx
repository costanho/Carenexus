import { type Href, useRouter } from 'expo-router';
import { useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import {
  AccountPage, PageText, SignOutButton, useRequiredSession,
} from '@/app/auth/forgot-password/web/forgot-password-web';
import { LegalDocumentBody, legalVersionLine, PENDING_LEGAL_REVIEW_NOTICE } from '@/app/legal/terms';
import {
  ariaLevel, Banner, Button, colors, ErrorView, fontSize, FormError, LoadingView, radius, space, webOnly,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { useResource } from '@/hooks/use-resource';
import { acceptLegalDocument, getLegalDocument, getLegalStatus, type LegalDocument } from '@/lib/auth/auth-flows-api';
import { updateSessionFlags } from '@/lib/auth/auth.storage';
import { requiredAccountStep, ROLE_HOME } from '@/lib/auth/auth.types';

const TITLE = 'Terms and privacy';

/**
 * /auth/accept-terms: the signed-in person reads and accepts the current terms and privacy policy. The
 * dashboard guard sends a session here while something is outstanding (flag `legalActionRequired`), for example
 * after a new version was published. Documents are shown one at a time and each acceptance is recorded on the
 * server for the version that was on screen. Signing out is the way to leave without accepting.
 */
export default function AcceptTermsWeb() {
  const router   = useRouter();
  const session  = useRequiredSession();
  const signedIn = session.status === 'signed-in';
  const role     = signedIn ? session.user.role : undefined;
  const { height } = useWindowDimensions();

  const [acceptedCount, setAcceptedCount] = useState(0);
  const [notice, setNotice]           = useState<string | null>(null);
  const [acceptError, setAcceptError] = useState<string | null>(null);
  const [leaving, setLeaving]         = useState(false);

  const status = useResource(() => getLegalStatus(), [], {
    enabled:      signedIn,
    errorMessage: 'We could not check which documents you still need to accept. Please try again.',
  });
  // Terms come before privacy; the first outstanding document is the one on screen.
  const current = status.data?.pending[0] ?? null;
  const outstanding = status.data ? status.data.pending.length : null;

  // Read outside the closure: the React Compiler hoists property reads in closures into memo dependencies that
  // run on every render, so `current!.docType` inside the callback would throw while `current` is still null.
  const currentDocType = current?.docType ?? null;
  const doc = useResource(
    () => (currentDocType === null
      ? Promise.reject(new Error('No document is waiting to be accepted.'))
      : getLegalDocument(currentDocType)),
    [currentDocType, current?.version ?? null],
    { enabled: current !== null, errorMessage: 'We could not load this document. Please try again.' },
  );

  // The version sent is the one of the text that was shown, never one taken from the list.
  const accept = useAction((shown: LegalDocument) => acceptLegalDocument(shown.docType, shown.version), {
    errorMessage: 'We could not record your acceptance. Please try again.',
  });

  // Nothing (left) to accept: clear the flag the dashboard guard reads, then carry on to the dashboard, or to
  // another step the account still has to complete.
  useEffect(() => {
    if (!role || outstanding !== 0) return;
    let active = true;
    setLeaving(true);
    updateSessionFlags({ legalActionRequired: false })
      .then(flags => { if (active) router.replace((requiredAccountStep(flags) ?? ROLE_HOME[role]) as Href); })
      .catch(() => { if (active) router.replace(ROLE_HOME[role] as Href); });
    return () => { active = false; };
  }, [role, outstanding, router]);

  async function onAccept(shown: LegalDocument) {
    setAcceptError(null);
    setNotice(null);
    const result = await accept.run(shown);
    if (result.ok) {
      setAcceptedCount(n => n + 1);
      status.setData(result.data);
      return;
    }
    const error = result.error;
    if (!error) return;
    if (error.code === 'LEGAL_ACCEPTANCE_REQUIRED') {
      // A newer version was published while this one was on screen: fetch what is current and ask again.
      setNotice('This document was updated while you were reading it. Please review the latest version below.');
      status.refetch();
      doc.refetch();
      return;
    }
    setAcceptError(error.message);
  }

  const footer = <SignOutButton />;

  if (!signedIn || status.loading || leaving || outstanding === 0) {
    return (
      <AccountPage title={TITLE} heading="Terms and privacy" role={role}>
        <LoadingView label={leaving || outstanding === 0 ? 'Opening your dashboard…' : 'Checking your account…'} compact />
      </AccountPage>
    );
  }

  if (!status.data || !current) {
    return (
      <AccountPage title={TITLE} heading="Terms and privacy" role={role} footer={footer}>
        <ErrorView
          message={status.error ?? 'We could not check which documents you still need to accept. Please try again.'}
          onRetry={status.refetch}
          compact
        />
      </AccountPage>
    );
  }

  const total = acceptedCount + status.data.pending.length;
  const shown = doc.data && doc.data.docType === current.docType ? doc.data : null;
  const title = shown?.title ?? current.title;

  return (
    <AccountPage
      title={TITLE}
      heading="Review and accept to continue"
      intro={total > 1
        ? 'Please read each document. You can use CareNexus once you have accepted them.'
        : 'Please read the document below. You can use CareNexus once you have accepted it.'}
      icon="document-text-outline"
      role={role}
      wide
      footer={footer}
    >
      {notice ? <Banner tone="info" text={notice} onDismiss={() => setNotice(null)} /> : null}

      <View style={styles.docHeader}>
        {total > 1 ? <Text style={styles.step}>{`Document ${acceptedCount + 1} of ${total}`}</Text> : null}
        <Text style={styles.docTitle} accessibilityRole="header" {...ariaLevel(2)}>{title}</Text>
        <Text style={styles.docMeta}>{legalVersionLine(shown ?? current)}</Text>
      </View>

      {doc.loading ? <LoadingView label="Loading document…" compact /> : null}
      {!doc.loading && !shown ? (
        <ErrorView message={doc.error ?? 'We could not load this document. Please try again.'} onRetry={doc.refetch} compact />
      ) : null}

      {shown ? (
        <>
          {shown.pendingLegalReview ? <Banner tone="warning" text={PENDING_LEGAL_REVIEW_NOTICE} /> : null}
          {/* A new document remounts the region, so reading always starts at the top. It takes keyboard focus
              so the text can be scrolled without a mouse. */}
          <ScrollView
            key={`${shown.docType}:${shown.version}`}
            style={[styles.reader, { maxHeight: Math.max(220, Math.round(height * 0.45)) }]}
            contentContainerStyle={styles.readerContent}
            role="region"
            accessibilityLabel={`${title}, full text`}
            {...webOnly({ tabIndex: 0 })}
          >
            <LegalDocumentBody markdown={shown.contentMd} title={shown.title} headingLevel={3} />
          </ScrollView>
          <PageText>
            {`By choosing Accept you confirm that you have read and agree to the ${title} (version ${shown.version}). `
              + 'If you do not agree, sign out; your account stays as it is.'}
          </PageText>
          <FormError message={acceptError} />
          <Button
            label={`Accept ${title}`}
            icon="checkmark-outline"
            fullWidth
            loading={accept.pending}
            onPress={() => { void onAccept(shown); }}
          />
        </>
      ) : null}
    </AccountPage>
  );
}

const styles = StyleSheet.create({
  docHeader: { gap: 2 },
  step:      { fontSize: fontSize.xs, fontWeight: '700', color: colors.textMuted, textTransform: 'uppercase', letterSpacing: 0.5 },
  docTitle:  { fontSize: fontSize.xl, fontWeight: '700', color: colors.text },
  docMeta:   { fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },

  reader: {
    borderWidth: 1, borderColor: colors.border, borderRadius: radius.md, backgroundColor: colors.bg,
    flexGrow: 0,
  },
  readerContent: { padding: space.lg },
});
