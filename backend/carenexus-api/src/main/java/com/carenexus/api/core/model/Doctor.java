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
