import type { useRouter } from 'expo-router';

type Router = ReturnType<typeof useRouter>;

/**
 * Goes back when there is a screen to go back to; otherwise (a screen opened directly, e.g. from a deep link
 * or after an app restart) replaces it with `fallback` so the back button never does nothing.
 */
export function goBackOr(router: Router, fallback: string): void {
  if (router.canGoBack()) router.back();
  else router.replace(fallback as any);
}
