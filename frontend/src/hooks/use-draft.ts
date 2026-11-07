import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { clearDraft, loadDraft, saveDraft } from '@/lib/communications/drafts';
import { onMessagingReset } from '@/lib/communications/session';

const DRAFT_DEBOUNCE_MS = 400;

/**
 * Text bound to a stored draft (lib/communications/drafts.ts), with the same rules as the web composer:
 * restored when `draftKey` changes, saved 400 ms after typing stops, flushed straight away when the key
 * changes or the component unmounts, and never written back after a sign-out cleared every draft.
 */
export function useDraft(draftKey: string | null): {
  text:    string;
  setText: (value: string) => void;
  /** Empties the box and forgets the stored draft (after a send). */
  clear:   () => void;
} {
  const [text, setTextState] = useState(() => (draftKey ? loadDraft(draftKey) : ''));
  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const unsaved   = useRef<{ key: string; value: string } | null>(null);
  const keyRef    = useRef(draftKey);

  const flush = useCallback(() => {
    if (saveTimer.current) clearTimeout(saveTimer.current);
    saveTimer.current = null;
    const p = unsaved.current;
    unsaved.current = null;
    if (p) saveDraft(p.key, p.value);
  }, []);

  useEffect(() => {
    keyRef.current = draftKey;
    setTextState(draftKey ? loadDraft(draftKey) : '');
    return flush;
  }, [draftKey, flush]);

  // The app may be closed from the background: save the last keystrokes when it leaves the foreground.
  useEffect(() => {
    const sub = AppState.addEventListener('change', state => { if (state !== 'active') flush(); });
    return () => sub.remove();
  }, [flush]);

  useEffect(() => onMessagingReset(() => {
    if (saveTimer.current) clearTimeout(saveTimer.current);
    saveTimer.current = null;
    unsaved.current = null;
  }), []);

  const setText = useCallback((value: string) => {
    setTextState(value);
    const key = keyRef.current;
    if (!key) return;
    if (saveTimer.current) clearTimeout(saveTimer.current);
    unsaved.current = { key, value };
    saveTimer.current = setTimeout(flush, DRAFT_DEBOUNCE_MS);
  }, [flush]);

  const clear = useCallback(() => {
    setTextState('');
    if (saveTimer.current) clearTimeout(saveTimer.current);
    saveTimer.current = null;
    unsaved.current = null;
    if (keyRef.current) clearDraft(keyRef.current);
  }, []);

  return { text, setText, clear };
}
