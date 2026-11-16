package com.carenexus.api.facility.service;

import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.facility.dto.request.FacilityRequest;
import com.carenexus.api.facility.dto.response.FacilityResponse;
import com.carenexus.api.facility.model.Facility;
import com.carenexus.api.facility.repository.FacilityRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Clinic locations (facilities): reference data for bookings and care-team entries.
 *
 * <h3>Readers (any signed-in user)</h3>
 * {@link #listActive} lists ACTIVE facilities only (pickers); {@link #get} returns one facility whether active or
 * not, so screens can still show the location of older entries. Facilities are not patient data: caregiver grants
 * play no part and reads are not audited.
 *
 * <h3>Administration (ADMIN)</h3>
 * {@link #adminList}, {@link #create}, {@link #update}, {@link #deactivate}, {@link #activate}. A new facility is
 * always active. PUT replaces all details and always stamps {@code updatedAt}. Activation and deactivation that
 * change nothing are silent no-ops (no write, no audit row). Nothing is ever deleted. Every write that changed a
 * row is audited with {@code logClinicalAccess(CREATE|UPDATE, FACILITY, id, null)}; field values are never written
 * to the audit log.
 *
 * Lists are sorted by name (case-insensitive), then id; {@code query} matches name or address, case-insensitive,
 * with {@code %}, {@code _} and {@code \} taken literally.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class FacilityService {

    public static final String RESOURCE = "FACILITY";
    public static final String NOT_FOUND = "Facility not found";

    /** Upper bound of the raw {@code query} parameter (checked by the controllers). */
    public static final int MAX_QUERY_LENGTH = 100;

    private static final String[] SIGNED_IN_ROLES = {"PATIENT", "DOCTOR", "CAREGIVER", "ADMIN"};
    private static final Sort ORDER = Sort.by(Sort.Order.asc("name").ignoreCase(), Sort.Order.asc("facilityId"));

    private final FacilityRepository facilityRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final Clock clock;

    // ==================== SIGNED-IN USERS ====================

    /** Active facilities only, for pickers. Any signed-in user. */
    @Transactional(readOnly = true)
    public PageResponse<FacilityResponse> listActive(String query, int page, int size) {
        authorizationService.requireRole(SIGNED_IN_ROLES);
        return search(Boolean.TRUE, query, page, size);
    }

    /** One facility, active or not. Any signed-in user. 404 when the id does not exist. */
    @Transactional(readOnly = true)
    public FacilityResponse get(Integer facilityId) {
        authorizationService.requireRole(SIGNED_IN_ROLES);
        return toResponse(load(facilityId));
    }

    // ==================== ADMINISTRATION ====================

    /** All facilities, or only active ({@code true}) / inactive ({@code false}) ones. ADMIN only. */
    @Transactional(readOnly = true)
    public PageResponse<FacilityResponse> adminList(Boolean active, String query, int page, int size) {
        authorizationService.requireAdmin();
        return search(active, query, page, size);
    }

    /** Creates an active facility. ADMIN only. Duplicate names are allowed. */
    public FacilityResponse create(FacilityRequest request) {
        authorizationService.requireAdmin();
        LocalDateTime now = now();
        Facility facility = Facility.builder()
                .isActive(Boolean.TRUE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        apply(facility, request);
        Facility saved = facilityRepository.saveAndFlush(facility);
        auditService.logClinicalAccess("CREATE", RESOURCE, saved.getFacilityId(), null);
        return toResponse(saved);
    }

    /** Replaces the five details of a facility (active or not). ADMIN only. Always writes and stamps updatedAt. */
    public FacilityResponse update(Integer facilityId, FacilityRequest request) {
        authorizationService.requireAdmin();
        Facility facility = load(facilityId);
        apply(facility, request);
        facility.setUpdatedAt(now());
        Facility saved = facilityRepository.saveAndFlush(facility);
        auditService.logClinicalAccess("UPDATE", RESOURCE, saved.getFacilityId(), null);
        return toResponse(saved);
    }

    /** Removes the facility from the active list. ADMIN only. No-op when already inactive. */
    public FacilityResponse deactivate(Integer facilityId) {
        return setActive(facilityId, false);
    }

    /** Puts the facility back on the active list. ADMIN only. No-op when already active. */
    public FacilityResponse activate(Integer facilityId) {
        return setActive(facilityId, true);
    }

    // ==================== INTERNALS ====================

    private FacilityResponse setActive(Integer facilityId, boolean active) {
        authorizationService.requireAdmin();
        Facility facility = load(facilityId);
        if (Boolean.valueOf(active).equals(facility.getIsActive())) {
            return toResponse(facility);
        }
        facility.setIsActive(active);
        facility.setUpdatedAt(now());
        Facility saved = facilityRepository.saveAndFlush(facility);
        auditService.logClinicalAccess("UPDATE", RESOURCE, saved.getFacilityId(), null);
        return toResponse(saved);
    }

    /** Clinic wall-clock now, at the precision of a PostgreSQL TIMESTAMP, so responses equal what is stored. */
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private Facility load(Integer facilityId) {
        if (facilityId == null || facilityId <= 0) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        return facilityRepository.findById(facilityId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
    }

    private PageResponse<FacilityResponse> search(Boolean active, String query, int page, int size) {
        Pageable pageable = PageParams.of(page, size, ORDER);
        Page<Facility> rows = facilityRepository.findAll(filter(active, query), pageable);
        return PageResponse.of(rows, FacilityService::toResponse);
    }

    private static Specification<Facility> filter(Boolean active, String query) {
        String text = query == null ? "" : query.trim();
        return (root, criteria, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (active != null) {
                predicates.add(cb.equal(root.get("isActive"), active));
            }
            if (!text.isEmpty()) {
                String pattern = "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern, '\\'),
                        cb.like(cb.lower(root.get("address")), pattern, '\\')));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** {@code %}, {@code _} and {@code \} in the search text are matched literally. */
    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static void apply(Facility facility, FacilityRequest request) {
        facility.setName(request.name());
        facility.setType(request.type());
        facility.setAddress(request.address());
        facility.setPhone(request.phone());
        facility.setEmail(request.email());
    }

    static FacilityResponse toResponse(Facility facility) {
        return new FacilityResponse(
                facility.getFacilityId(),
                facility.getName(),
                facility.getType(),
                facility.getAddress(),
                facility.getPhone(),
                facility.getEmail(),
                Boolean.TRUE.equals(facility.getIsActive()),
                facility.getCreatedAt(),
                facility.getUpdatedAt());
    }
}
