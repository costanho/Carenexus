package com.carenexus.api.account.retention.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Retention windows of the retention sweeper ({@code carenexus.retention.*}), in whole days. Defaults live here;
 * application.yml only overrides.
 *
 * <pre>
 * carenexus.retention.tokens-days               = 30   refresh tokens, after they expired or were revoked
 * carenexus.retention.read-notifications-days   = 180  read notifications, from created_at
 * carenexus.retention.outbound-messages-days    = 30   SENT and SKIPPED outbound messages, from sent_at
 * carenexus.retention.call-records-days         = 30   finished call jobs (completed_at), provider events (received_at)
 * carenexus.jobs.retention.interval-ms          = 3600000  time between sweeps
 * </pre>
 * Account link tokens (password reset, email verification, email change, MFA challenges) and idempotency keys are
 * removed as soon as they are used or expired, without a window. Audit and clinical rows are never deleted.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties("carenexus.retention")
public class RetentionProperties {

    /** Days a refresh token is kept after it expired or was revoked. */
    @Min(1)
    @Max(3650)
    private int tokensDays = 30;

    /** Days a read notification is kept, counted from its creation. */
    @Min(1)
    @Max(3650)
    private int readNotificationsDays = 180;

    /** Days a SENT or SKIPPED outbound message is kept, counted from sent_at. */
    @Min(1)
    @Max(3650)
    private int outboundMessagesDays = 30;

    /** Days a finished call job (from completed_at) and a provider webhook event (from received_at) are kept. */
    @Min(1)
    @Max(3650)
    private int callRecordsDays = 30;
}
