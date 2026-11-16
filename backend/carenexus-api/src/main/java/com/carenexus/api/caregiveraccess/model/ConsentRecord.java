package com.carenexus.api.caregiveraccess.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * One consent-ledger row (table {@code consent_records}, V1 re-keyed in V44): one lifecycle step of a caregiver
 * grant with a snapshot of the grant right after it. Rows are inserted once and never updated or deleted by the
 * application ({@link Immutable}).
 *
 * Columns of the V1 table that the ledger does not use ({@code patient_id}, {@code caregiver_id}, {@code doctor_id},
 * {@code terms_agreed}, {@code ip_address}, {@code received_at}) are not mapped; their database defaults apply.
 */
@Entity
@Immutable
@Table(name = "consent_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConsentRecord {

    /** The V1 CHECK value every ledger row writes. */
    public static final String CONSENT_TYPE_DATA_ACCESS = "DATA_ACCESS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "consent_id")
    private Integer consentId;

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    @Column(name = "caregiver_system_id", length = 20)
    private String caregiverSystemId;

    @Column(name = "doctor_system_id", length = 20)
    private String doctorSystemId;

    /** Who performed the step: the patient, the caregiver, an administrator, or null for the system (expiry). */
    @Column(name = "granted_by_system_id", length = 20)
    private String grantedBySystemId;

    @Column(name = "access_id")
    private Integer accessId;

    /** CaregiverGrantRecordedEvent kind; null only on rows written before V44. */
    @Column(name = "event_kind", length = 30)
    private String eventKind;

    @Column(name = "consent_type", nullable = false, length = 20)
    @Builder.Default
    private String consentType = CONSENT_TYPE_DATA_ACCESS;

    /** JSON snapshot of the grant after the step (see ConsentScope). */
    @Column(name = "scope", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    private String scope;

    /** When the step was recorded (clinic wall-clock). */
    @Column(name = "signed_date", nullable = false)
    private LocalDateTime signedDate;

    /** The grant's expiry after the step; null = no expiry. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** False for steps that end the grant's effect (REVOKED, EXPIRED). */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /** Set on a REVOKED step: when the grant was revoked. */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;
}
