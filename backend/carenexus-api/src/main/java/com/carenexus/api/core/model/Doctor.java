package com.carenexus.api.core.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "doctors", uniqueConstraints = {
    @UniqueConstraint(columnNames = "system_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Doctor {

    public static final String VERIFICATION_PENDING = "PENDING";
    public static final String VERIFICATION_APPROVED = "APPROVED";
    public static final String VERIFICATION_REJECTED = "REJECTED";
    public static final String VERIFICATION_SUSPENDED = "SUSPENDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "doctor_id")
    private Integer doctorId;

    @Column(name = "system_id", nullable = false, unique = true, length = 20)
    private String systemId;

    @Column(name = "specialization", length = 150)
    private String specialization;

    @Column(name = "license_no", length = 50, unique = true)
    private String licenseNo;

    @Column(name = "bio", columnDefinition = "text")
    private String bio;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /** Clinician verification: PENDING, APPROVED, REJECTED or SUSPENDED (see the constants below). */
    @Column(name = "verification_status", nullable = false, length = 20)
    @Builder.Default
    private String verificationStatus = VERIFICATION_PENDING;

    /** Administrator who made the last verification decision. */
    @Column(name = "verified_by_system_id", length = 20)
    private String verifiedBySystemId;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    /** Reason given for REJECTED or SUSPENDED. */
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    /** Regulator that issued the licence (for example a medical council). */
    @Column(name = "licensing_body", length = 150)
    private String licensingBody;

    /** ISO 3166-1 alpha-2 country of the licence. */
    @Column(name = "license_country", length = 2)
    private String licenseCountry;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    /**
     * Convenience method for accessing systemId as doctorSystemId
     */
    public String getDoctorSystemId() {
        return this.systemId;
    }
}
