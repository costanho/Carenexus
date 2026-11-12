import { useCallback } from 'react';
import { useSectionNav } from '@/hooks/use-section-nav';
import { ROLE_THEME, type SectionProps } from '@/lib/nav/sections';
import { MessagesScreen } from '../messages/messages-screen';

/** A positive whole number from a URL param, or null. */
function threadIdFrom(value: string | undefined): number | null {
  if (value === undefined || !/^\d+$/.test(value)) return null;
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

/**
 * Administrator section `messages`: the support inbox. Lists the support conversations people opened from
 * Help & Support (open or closed), lets the administrator read and answer one, and close it.
 *
 * A link can ask for one conversation: `?section=messages&thread=<id>` (in-app links) or
 * `?section=messages&id=<id>` (the administrator's "new support message" notification). The conversation is
 * opened and the param is then removed in place, so following the same link again later opens it again.
 */
export default function AdminSupportInbox({ params, theme }: Partial<SectionProps>) {
  const nav = useSectionNav();
  const { setParams } = nav;
  const urlParams = params ?? nav.params;
  const openThreadId = threadIdFrom(urlParams.thread) ?? threadIdFrom(urlParams.id);
  const onOpenThreadHandled = useCallback(() => setParams({ thread: undefined, id: undefined }), [setParams]);

  return (
    <MessagesScreen
      mode="ADMIN"
      theme={theme ?? ROLE_THEME.admin}
      openThreadId={openThreadId}
      onOpenThreadHandled={onOpenThreadHandled}
    />
  );
}
