// Tells mounted correction lists that a request was filed or resolved somewhere else on the screen (a
// "Request a correction" button on a record card and the list on the Corrections tab do not share a parent).

type Listener = (patientSystemId: string | null) => void;

const listeners = new Set<Listener>();

/** Call after a request was created or resolved. `patientSystemId` is the patient the request is about. */
export function notifyCorrectionsChanged(patientSystemId: string | null = null): void {
  for (const listener of Array.from(listeners)) {
    try { listener(patientSystemId); } catch { /* one failing list must not stop the others */ }
  }
}

/** Subscribes to changes; returns the function that unsubscribes. */
export function subscribeCorrectionsChanged(listener: Listener): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}
