package com.carenexus.api.admin.audit;

import com.carenexus.api.admin.audit.dto.AuditLogResponse;
import com.carenexus.api.admin.audit.repository.AdminAuditRepository;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.model.AuditLog;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only access to the audit log for administrators. Every successful read is itself audited
 * ({@code READ} / {@code AUDIT_LOG}, actor = the administrator, no target), written after the query so the caller's
 * own read shows up at the top of the next unfiltered page, not in the page being returned.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AuditQueryService {

    public static final String RESOURCE = "AUDIT_LOG";
    public static final int DEFAULT_SIZE = 50;

    private static final Set<String> STATUSES = Set.of("SUCCESS", "FAILURE");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "timestamp")
            .and(Sort.by(Sort.Direction.DESC, "auditId"));

    private final AdminAuditRepository auditRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    /** Filters of {@link #search}; every value is optional (null or blank = no filter). */
    public record Criteria(String actor, String patient, String action, String resourceType, String status,
                           String from, String to) {
    }

    /**
     * Filtered audit search, newest first. Ids are matched exactly after trimming and upper-casing; {@code from} and
     * {@code to} accept {@code YYYY-MM-DD} or {@code YYYY-MM-DDTHH:mm[:ss]} (clinic wall-clock); a date-only {@code to}
     * includes the whole day.
     *
     * @throws BadRequestException for an unknown status, an unparseable date or {@code from} after {@code to}
     */
    public PageResponse<AuditLogResponse> search(Criteria criteria, Integer page, Integer size) {
        authorizationService.requireAdmin();
        Criteria c = criteria == null ? new Criteria(null, null, null, null, null, null, null) : criteria;

        String actor = upperOrNull(c.actor());
        String patient = upperOrNull(c.patient());
        String action = upperOrNull(c.action());
        String resourceType = upperOrNull(c.resourceType());
        String status = upperOrNull(c.status());
        if (status != null && !STATUSES.contains(status)) {
            throw new BadRequestException("Invalid status. Must be SUCCESS or FAILURE");
        }
        Bound from = parseBound(c.from(), "from");
        Bound to = parseBound(c.to(), "to");
        LocalDateTime fromInclusive = from == null ? null : from.start();
        LocalDateTime toInclusive = to == null || to.dateOnly() ? null : to.start();
        LocalDateTime toExclusive = to != null && to.dateOnly() ? to.start().plusDays(1) : null;
        if (fromInclusive != null && ((toInclusive != null && fromInclusive.isAfter(toInclusive))
                || (toExclusive != null && !fromInclusive.isBefore(toExclusive)))) {
            throw new BadRequestException("'from' must not be after 'to'");
        }

        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actor != null) predicates.add(cb.equal(root.get("systemId"), actor));
            if (patient != null) predicates.add(cb.equal(root.get("patientSystemId"), patient));
            if (action != null) predicates.add(cb.equal(root.get("action"), action));
            if (resourceType != null) predicates.add(cb.equal(root.get("resourceType"), resourceType));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (fromInclusive != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<LocalDateTime>get("timestamp"), fromInclusive));
            }
            if (toInclusive != null) {
                predicates.add(cb.lessThanOrEqualTo(root.<LocalDateTime>get("timestamp"), toInclusive));
            }
            if (toExclusive != null) {
                predicates.add(cb.lessThan(root.<LocalDateTime>get("timestamp"), toExclusive));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };

        PageResponse<AuditLogResponse> result = page(spec, page, size == null ? DEFAULT_SIZE : size);
        auditRead();
        return result;
    }

    /**
     * Audit rows that concern one account: the account is the actor, the target of an account event or the patient
     * whose data was touched. Newest first. The caller has already checked that the account exists.
     */
    public PageResponse<AuditLogResponse> forAccount(String systemId, Integer page, Integer size) {
        authorizationService.requireAdmin();
        Specification<AuditLog> spec = (root, query, cb) -> cb.or(
                cb.equal(root.get("systemId"), systemId),
                cb.equal(root.get("targetSystemId"), systemId),
                cb.equal(root.get("patientSystemId"), systemId));
        PageResponse<AuditLogResponse> result = page(spec, page, size == null ? PageParams.DEFAULT_SIZE : size);
        auditRead();
        return result;
    }

    /** One audit row. */
    public AuditLogResponse get(Long auditId) {
        authorizationService.requireAdmin();
        AuditLog row = auditRepository.findById(auditId)
                .orElseThrow(() -> new ResourceNotFoundException("Audit log entry not found"));
        AuditLogResponse response = toResponses(List.of(row)).get(0);
        auditRead();
        return response;
    }

    // ==================== HELPERS ====================

    private PageResponse<AuditLogResponse> page(Specification<AuditLog> spec, Integer page, int size) {
        Pageable pageable = PageParams.of(page, size, NEWEST_FIRST);
        Page<AuditLog> rows = auditRepository.findAll(spec, pageable);
        return PageResponse.of(rows, toResponses(rows.getContent()));
    }

    /** Maps rows with the actor names looked up in one query. */
    private List<AuditLogResponse> toResponses(List<AuditLog> rows) {
        Set<String> actors = rows.stream().map(AuditLog::getSystemId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, String> names = actors.isEmpty() ? Map.of() : auditRepository.findActorNames(actors).stream()
                .collect(Collectors.toMap(AdminAuditRepository.ActorName::getSystemId,
                        n -> fullName(n.getFirstName(), n.getLastName()), (a, b) -> a));
        Function<AuditLog, AuditLogResponse> map = row ->
                AuditLogResponse.of(row, row.getSystemId() == null ? null : names.get(row.getSystemId()));
        return rows.stream().map(map).toList();
    }

    private void auditRead() {
        auditService.logAccountEvent(authorizationService.currentSystemId(), "READ", RESOURCE, null, "SUCCESS");
    }

    private static String fullName(String first, String last) {
        String name = ((first == null ? "" : first.trim()) + " " + (last == null ? "" : last.trim())).trim();
        return name.isEmpty() ? null : name;
    }

    private static String upperOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    /** A parsed {@code from}/{@code to} value: the instant it names and whether only a date was given. */
    private record Bound(LocalDateTime start, boolean dateOnly) {
    }

    private static Bound parseBound(String raw, String parameter) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            if (value.length() == 10) {
                return new Bound(LocalDate.parse(value).atStartOfDay(), true);
            }
            if (value.length() == 16 || value.length() == 19) {
                return new Bound(LocalDateTime.parse(value), false);
            }
        } catch (DateTimeParseException e) {
            // reported below
        }
        throw new BadRequestException("Invalid value for parameter '" + parameter + "'");
    }
}
