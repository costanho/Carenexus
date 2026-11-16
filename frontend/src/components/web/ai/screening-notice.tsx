import { Banner } from '@/components/web/ui';
import { SCREENING_DISCLAIMER } from '@/lib/ai/labels';

/**
 * The label every alert and screening view carries: the content comes from automated rule-based screening and
 * is not a diagnosis. Rendered once per view. Pass the `disclaimer` of a loaded row when there is one; the
 * same fixed wording is used while a list is empty or loading.
 */
export function ScreeningNotice({ text }: { text?: string | null }) {
  return <Banner tone="info" icon="information-circle-outline" text={text?.trim() || SCREENING_DISCLAIMER} />;
}
