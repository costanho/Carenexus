package com.carenexus.api.calls.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Call window and background job settings ({@code carenexus.calls.*}).
 */
@Getter
@Setter
@ConfigurationProperties("carenexus.calls")
public class CallsProperties {

    /** Joining opens this many minutes before the scheduled start. */
    private int joinOpensMinutesBefore = 15;
    /** Joining closes this many minutes after the scheduled end (start + duration). */
    private int joinClosesMinutesAfterEnd = 60;
    private boolean workerEnabled = true;
    private long workerIntervalMs = 5000;
    private long sweeperIntervalMs = 60000;
}
