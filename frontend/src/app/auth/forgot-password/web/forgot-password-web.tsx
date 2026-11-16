import { zodResolver } from '@hookform/resolvers/zod';
import { type Href, Link, useLocalSearchParams, useRouter } from 'expo-router';
import Head from 'expo-router/head';
import { type ReactNode, useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Platform, ScrollView, StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import {
  ariaLevel, Button, Card, colors, decorative, fontSize, FormError, Icon, type IoniconsName, PHONE_BREAKPOINT,
  radius, space, STATUS_TONES, TextField, UiThemeProvider, useUiTheme,
} from '@/components/web/ui';
import { useAction } from '@/hooks/use-action';
import { useLogout } from '@/hooks/use-logout';
import { applyFieldErrors } from '@/lib/api/form-errors';
import { requestPasswordReset } from '@/lib/auth/auth-flows-api';
import { getSessionFlags, getUser, isAuthenticated } from '@/lib/auth/auth.storage';
import { type AppRole, type AuthUser, isAppRole, ROLE_HOME, type SessionFlags } from '@/lib/auth/auth.types';
import {
  type ForgotPasswordForm, forgotPasswordSchema, linkToken, passwordChecks,
} from '@/lib/validation/account-recovery';

// ─────────────────────────────────────────────────────────────────────────────
// Shared pieces of the account pages
//
// The pages outside the dashboard (the account flows under /auth and the legal pages) share one frame, their
// links and a few hooks. Every file under app/ becomes a page of its own, so these are exported from this
// screen's module rather than from extra files that would each add an address serving nothing.
// ─────────────────────────────────────────────────────────────────────────────

export const SIGN_IN_ROUTE = Platform.OS === 'web'
  ? '/auth/login/web/login-web'
  : '/auth/login/mobile/login-mobile';
export const FORGOT_PASSWORD_ROUTE = '/auth/forgot-password';
export const TERMS_ROUTE   = '/legal/terms';
export const PRIVACY_ROUTE = '/legal/privacy';

/** Who is signed in on this device, read from the stored session (no request is made). */
export type AccountSession =
  | { status: 'checking' }
  | { status: 'signed-out' }
  | { status: 'signed-in'; user: AuthUser; flags: SessionFlags };

export function useAccountSession(): AccountSession {
  const [session, setSession] = useState<AccountSession>({ status: 'checking' });
  useEffect(() => {
    let cancelled = false;
    Promise.all([isAuthenticated(), getUser(), getSessionFlags()])
      .then(([authed, user, flags]) => {
        if (cancelled) return;
        setSession(authed && user && isAppRole(user.role)
          ? { status: 'signed-in', user, flags }
          : { status: 'signed-out' });
      })
      .catch(() => { if (!cancelled) setSession({ status: 'signed-out' }); });
    return () => { cancelled = true; };
  }, []);
  return session;
}

/**
 * The stored session for a page that only makes sense when signed in (forced password change, accepting the
 * terms). The /auth pages are public to the root guard, so a signed-out visitor is sent to sign in from here.
 */
export function useRequiredSession(): AccountSession {
  const router  = useRouter();
  const session = useAccountSession();
  const signedOut = session.status === 'signed-out';
  useEffect(() => {
    if (signedOut) router.replace(SIGN_IN_ROUTE as Href);
  }, [signedOut, router]);
  return session;
}

/**
 * The `token` of the emailed link that opened this page: undefined until it has been read, null when the link
 * has none (or one too long for the server to accept). It is read after the page has mounted because the
 * pre-rendered page is built without a query string.
 */
export function useLinkToken(maxLength: number): string | null | undefined {
  const params = useLocalSearchParams<{ token?: string | string[] }>();
  const fromRouter = Array.isArray(params.token) ? params.token[0] : params.token;
  const [token, setToken] = useState<string | null | undefined>(undefined);
  useEffect(() => {
    let raw: string | null | undefined = fromRouter;
    if (!raw && Platform.OS === 'web' && typeof window !== 'undefined') {
      raw = new URLSearchParams(window.location.search).get('token');
    }
    setToken(linkToken(raw, maxLength));
  }, [fromRouter, maxLength]);
  return token;
}

export type PageStatus = 'info' | 'success' | 'warning' | 'danger';

const STATUS_ICON: Record<PageStatus, IoniconsName> = {
  info:    'information-circle-outline',
  success: 'checkmark-circle-outline',
  warning: 'warning-outline',
  danger:  'alert-circle-outline',
};

export interface AccountPageProps {
  /** Browser tab title, without the product name ("Reset password"). */
  title:     string;
  /** The page's main heading. It changes with the state of the flow and is announced when it does. */
  heading:   string;
  /** One or two sentences under the heading. */
  intro?:    string;
  /** Outcome the page reports; draws a matching icon above the heading (the words carry the meaning). */
  status?:   PageStatus;
  /** Icon above the heading when there is no `status`, or to replace the status icon. */
  icon?:     IoniconsName;
  /** Accent colours of the signed-in person's role; patient teal when nobody is signed in. */
  role?:     AppRole;
  /** A wider column, for pages that hold a document to read. */
  wide?:     boolean;
  children?: ReactNode;
  /** Links under the card (Back to sign in, Terms, Privacy). */
  footer?:   ReactNode;
}

/**
 * The frame of a page outside the dashboard: document title, product name, one card with the page heading and
 * its content, and links underneath. The column is centred and never wider than the window, down to 375 px.
 */
export function AccountPage(props: AccountPageProps) {
  return (
    <UiThemeProvider role={props.role}>
      <Head><title>{`${props.title} | CareNexus`}</title></Head>
      <PageFrame {...props} />
    </UiThemeProvider>
  );
}

function PageFrame({ heading, intro, status, icon, wide = false, children, footer }: AccountPageProps) {
  const { accent, accentBg } = useUiTheme();
  const { width } = useWindowDimensions();
  const phone = width < PHONE_BREAKPOINT;
  const tone  = status ? STATUS_TONES[status] : null;
  const glyph = icon ?? (status ? STATUS_ICON[status] : null);
  return (
    <ScrollView
      style={styles.page}
      contentContainerStyle={[styles.pageContent, { padding: phone ? space.lg : space.xl }]}
      keyboardShouldPersistTaps="handled"
    >
      <View style={[styles.column, wide && styles.columnWide]} role="main">
        <View style={styles.brand}>
          <Icon name="heart" size={20} color={accent} />
          <Text style={styles.brandText}>CareNexus</Text>
        </View>
        <Card style={[styles.card, !phone && styles.cardRoomy]}>
          {/* Polite live region: when a flow moves on (sent, verified, link expired) the new heading is read out. */}
          <View style={styles.header} aria-live="polite">
            {glyph ? (
              <View style={[styles.badge, { backgroundColor: tone?.bg ?? accentBg }]} {...decorative}>
                <Icon name={glyph} size={26} color={tone?.fg ?? accent} />
              </View>
            ) : null}
            <Text style={styles.heading} accessibilityRole="header" {...ariaLevel(1)}>{heading}</Text>
            {intro ? <Text style={styles.intro}>{intro}</Text> : null}
          </View>
          {children}
        </Card>
        {footer ? <View style={styles.footer}>{footer}</View> : null}
      </View>
    </ScrollView>
  );
}

/** A paragraph of page text. */
export function PageText({ children }: { children: ReactNode }) {
  return <Text style={styles.text}>{children}</Text>;
}

/** A link to another page of the app. Underlined, so it does not rely on colour to read as a link. */
export function PageLink({ href, label, replace = false }: { href: string; label: string; replace?: boolean }) {
  const { accent } = useUiTheme();
  return (
    <Link href={href as Href} replace={replace} style={[styles.link, { color: accent }]}>
      {label}
    </Link>
  );
}

/**
 * Where a person goes on from a page that does not need a session: their dashboard when signed in, sign-in
 * otherwise. Nothing is drawn while the stored session is still being read.
 */
export function HomeLink({ session, signedOutLabel = 'Back to sign in' }: {
  session:         AccountSession;
  signedOutLabel?: string;
}) {
  if (session.status === 'checking') return null;
  return session.status === 'signed-in'
    ? <PageLink href={ROLE_HOME[session.user.role]} label="Back to dashboard" replace />
    : <PageLink href={SIGN_IN_ROUTE} label={signedOutLabel} replace />;
}

/** Signs out and goes to sign-in, so a page that blocks the dashboard is never a dead end. */
export function SignOutButton() {
  const { handleLogout, loading } = useLogout();
  return (
    <Button
      label={loading ? 'Signing out…' : 'Sign out'}
      icon="log-out-outline"
      variant="ghost"
      size="sm"
      loading={loading}
      onPress={() => { void handleLogout(); }}
    />
  );
}

/** The password rules under a new-password field, each one marked as met or not while the person types. */
export function PasswordChecklist({ value }: { value: string }) {
  return (
    <View style={styles.checks} role="list" accessibilityLabel="Password requirements">
      {passwordChecks(value).map(check => (
        <View key={check.key} style={styles.check} role="listitem">
          {/* The glyph itself is hidden from assistive technology; the wrapper says what it shows. */}
          <View role="img" accessibilityLabel={check.met ? 'Met:' : 'Not met yet:'}>
            <Icon
              name={check.met ? 'checkmark-circle' : 'ellipse-outline'}
              size={15}
              color={check.met ? STATUS_TONES.success.fg : colors.textSubtle}
            />
          </View>
          <Text style={[styles.checkText, check.met && styles.checkTextMet]}>{check.label}</Text>
        </View>
      ))}
    </View>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// /auth/forgot-password
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Asks for a password reset link. The confirmation is the same whatever address was entered and never repeats
 * it, so the page does not reveal whether an account exists.
 */
export default function ForgotPasswordWeb() {
  const session = useAccountSession();
  const [sent, setSent] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const { control, handleSubmit, setError, reset } = useForm<ForgotPasswordForm>({
    resolver:      zodResolver(forgotPasswordSchema),
    defaultValues: { email: '' },
  });
  const request = useAction((email: string) => requestPasswordReset(email), {
    errorMessage: 'We could not send the request. Please try again.',
  });

  const submit = handleSubmit(async values => {
    setFormError(null);
    const result = await request.run(values.email);
    if (result.ok) { setSent(true); return; }
    if (result.error) setFormError(applyFieldErrors(result.error, setError, ['email']));
  });

  const footer = <HomeLink session={session} />;

  if (sent) {
    return (
      <AccountPage
        title="Forgot password"
        heading="Check your inbox"
        intro="If an account exists for that email address, we have sent it a link to reset the password."
        icon="mail-outline"
        footer={footer}
      >
        <PageText>
          The link expires after a short time and only the most recent one works. If nothing arrives in a few
          minutes, check your spam folder or ask for a new link.
        </PageText>
        <Button
          label="Use a different email address"
          variant="secondary"
          fullWidth
          onPress={() => { reset({ email: '' }); setFormError(null); setSent(false); }}
        />
      </AccountPage>
    );
  }

  return (
    <AccountPage
      title="Forgot password"
      heading="Forgot your password?"
      intro="Enter the email address you sign in with and we will send you a link to choose a new password."
      icon="key-outline"
      footer={footer}
    >
      <TextField
        label="Email address"
        control={control}
        name="email"
        required
        placeholder="you@example.com"
        inputMode="email"
        autoComplete="email"
        autoCapitalize="none"
        onSubmit={() => { void submit(); }}
        disabled={request.pending}
      />
      <FormError message={formError} />
      <Button
        label="Send reset link"
        icon="send-outline"
        fullWidth
        loading={request.pending}
        onPress={() => { void submit(); }}
      />
    </AccountPage>
  );
}

const styles = StyleSheet.create({
  page:        { flex: 1, backgroundColor: colors.bg },
  pageContent: { flexGrow: 1, alignItems: 'center', justifyContent: 'center' },
  column:      { width: '100%', maxWidth: 460, gap: space.lg },
  columnWide:  { maxWidth: 780 },

  brand:     { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: space.sm },
  brandText: { fontSize: fontSize.xl, fontWeight: '800', color: colors.text },

  card:      { gap: space.lg },
  cardRoomy: { padding: space.xl },
  header:    { gap: space.sm },
  badge: {
    width: 48, height: 48, borderRadius: radius.pill, alignItems: 'center', justifyContent: 'center',
    marginBottom: space.xs,
  },
  heading: { fontSize: fontSize.title, fontWeight: '700', color: colors.text },
  intro:   { fontSize: fontSize.md, lineHeight: 21, color: colors.textMuted },
  text:    { fontSize: fontSize.md, lineHeight: 21, color: colors.textMuted },

  footer: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'center', columnGap: space.xl, rowGap: space.xs },
  link:   { fontSize: fontSize.md, fontWeight: '600', textDecorationLine: 'underline', paddingVertical: space.sm },

  checks:       { gap: space.xs },
  check:        { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  checkText:    { flex: 1, minWidth: 0, fontSize: fontSize.sm, color: colors.textMuted, lineHeight: 19 },
  checkTextMet: { color: colors.text },
});
