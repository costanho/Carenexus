import { useEffect, useRef } from 'react';

// Tells every mounted health view of a patient that the stored data changed, so tiles, the chart and the lists
// reload after a reading or a symptom log is added or removed anywhere on the page (a form in a dialog, a delete
// in a list). In-memory and per browser tab; nothing is sent anywhere.

export type HealthTopic = 'metrics' | 'symptoms';

type Listener = (topic: HealthTopic, patientSystemId: string) => void;

const listeners = new Set<Listener>();

/** Call after a successful write. */
export function notifyHealthChanged(topic: HealthTopic, patientSystemId: string): void {
  if (!patientSystemId) return;
  // Copy first: a listener may unsubscribe while being called.
  for (const listener of Array.from(listeners)) {
    try { listener(topic, patientSystemId); } catch { /* one failing view must not stop the others */ }
  }
}

export function subscribeHealthChanges(listener: Listener): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

/** Runs `onChange` whenever data of `topic` changes for this patient. */
export function useHealthChange(topic: HealthTopic, patientSystemId: string | null | undefined, onChange: () => void): void {
  const handler = useRef(onChange);
  handler.current = onChange;
  useEffect(() => {
    if (!patientSystemId) return undefined;
    return subscribeHealthChanges((changedTopic, changedPatient) => {
      if (changedTopic === topic && changedPatient === patientSystemId) handler.current();
    });
  }, [topic, patientSystemId]);
}
