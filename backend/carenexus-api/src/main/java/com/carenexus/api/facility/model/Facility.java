package com.carenexus.api.facility.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A clinic location (table {@code facilities}, V1; {@code updated_at} added by V64). Reference data, not patient
 * data: appointments and care-team rows point at it by {@code facility_id}. Rows are never deleted; an
 * administrator deactivates a facility instead ({@code is_active = false}).
 *
 * Timestamps are clinic wall-clock {@link LocalDateTime} values set by {@code FacilityService} from the
 * application clock.
 */
@Entity
@Table(name = "facilities")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Facility {

    public static final int NAME_MAX = 200;
    public static final int TYPE_MAX = 80;
    public static final int ADDRESS_MAX = 300;
    public static final int PHONE_MAX = 20;
    public static final int EMAIL_MAX = 150;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "facility_id", nullable = false)
    private Integer facilityId;

    @Column(name = "name", nullable = false, length = NAME_MAX)
    private String name;

    /** Free-text kind of facility, for example Hospital, Clinic, Diagnostic Centre. */
    @Column(name = "type", length = TYPE_MAX)
    private String type;

    @Column(name = "address", length = ADDRESS_MAX)
    private String address;

    @Column(name = "phone", length = PHONE_MAX)
    private String phone;

    @Column(name = "email", length = EMAIL_MAX)
    private String email;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
