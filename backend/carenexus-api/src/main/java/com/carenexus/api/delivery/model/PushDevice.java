package com.carenexus.api.delivery.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Set;

/**
 * A push token registered by one of a user's devices (V62). A token belongs to one account at a time; a user keeps
 * at most {@link #MAX_DEVICES_PER_USER} devices. Not clinical data: rows are hard-deleted.
 */
@Entity
@Table(name = "push_devices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PushDevice {

    public static final String IOS = "IOS";
    public static final String ANDROID = "ANDROID";
    public static final String WEB = "WEB";

    public static final Set<String> PLATFORMS = Set.of(IOS, ANDROID, WEB);

    public static final int MAX_DEVICES_PER_USER = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "device_id")
    private Integer deviceId;

    @Column(name = "system_id", nullable = false, length = 20)
    private String systemId;

    @Column(name = "platform", nullable = false, length = 10)
    private String platform;

    @Column(name = "token", nullable = false, length = 512)
    private String token;

    @Column(name = "last_seen_at", nullable = false, columnDefinition = "timestamptz")
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (lastSeenAt == null) {
            lastSeenAt = now;
        }
    }
}
