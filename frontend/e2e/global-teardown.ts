import { resetE2EData } from './global-setup';

// Deletes the rows this run created (see global-setup.ts). Never fails the run.
export default async function globalTeardown(): Promise<void> {
  await resetE2EData('after the run');
}
