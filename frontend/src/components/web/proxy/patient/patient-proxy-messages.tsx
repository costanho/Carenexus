import { MessagesScreen } from '../../messages/messages-screen';

const PROXY_THEME = { accent: '#7C3AED', accentBg: '#F5F3FF' };

// Caregiver messages: conversations about the loved ones who approved messaging.
export default function PatientProxyMessages({ openThreadId, onOpenThreadHandled }: {
  openThreadId?:        number | null;
  onOpenThreadHandled?: () => void;
} = {}) {
  return (
    <MessagesScreen
      mode="CAREGIVER"
      theme={PROXY_THEME}
      openThreadId={openThreadId}
      onOpenThreadHandled={onOpenThreadHandled}
    />
  );
}
