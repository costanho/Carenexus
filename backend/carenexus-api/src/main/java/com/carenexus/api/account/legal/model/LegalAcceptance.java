package com.carenexus.api.account.legal.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Proof that an account accepted one version of the terms or the privacy policy (table {@code legal_acceptances},
 * V29). Unique per account, type and version; append-only. Rows are written with
 * {@code LegalAcceptanceRepository.insertIfAbsent} so a repeat never aborts the surrounding transaction.
 */
@Entity
@Table(name = "legal_acceptances")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LegalAcceptance {

    /** Column sizes, used to cut the recorded evidence. */
    public static final int MAX_IP_LENGTH = 45;
    public static final int MAX_USER_AGENT_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "acceptance_id")
    private Integer acceptanceId;

    /** The accepting account. */
    @Column(name = "system_id", nullable = false, length = 20)
    private String systemId;

    @Column(name = "doc_type", nullable = false, length = 20)
    private String docType;

    @Column(name = "version", nullable = false, length = 20)
    private String version;

    @Column(name = "accepted_at", nullable = false, columnDefinition = "timestamptz")
    private Instant acceptedAt;

    @Column(name = "ip_address", length = MAX_IP_LENGTH)
    private String ipAddress;

    @Column(name = "user_agent", length = MAX_USER_AGENT_LENGTH)
    private String userAgent;
}
