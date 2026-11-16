import { Banner } from '@/components/web/ui';
import type { BillingConfig } from '@/lib/billing/types';

/**
 * The plain statement shown wherever a payment is started or followed while the local test gateway is active:
 * nothing is charged and no money moves. Renders nothing for a live gateway.
 */
export function GatewayNotice({ config }: { config: BillingConfig | null | undefined }) {
  if (!config || config.liveGateway) return null;
  return (
    <Banner
      tone="info"
      icon="flask-outline"
      text="No live payment gateway is connected. Payments here are recorded for testing only: no card is charged and no M-PESA money moves."
    />
  );
}
