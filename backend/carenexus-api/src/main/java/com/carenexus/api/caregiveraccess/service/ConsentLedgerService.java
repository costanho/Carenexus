package com.carenexus.api.caregiveraccess.service;

import com.carenexus.api.caregiveraccess.dto.response.ConsentRecordResponse;
import com.carenexus.api.caregiveraccess.dto.response.ConsentScopeResponse;
import com.carenexus.api.caregiveraccess.model.ConsentRecord;
import com.carenexus.api.caregiveraccess.repository.AccessGrantRepository;
import com.carenexus.api.caregiveraccess.repository.AccessPeopleRepository.Person;
import com.carenexus.api.caregiveraccess.repository.ConsentRecordRepository;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.event.CaregiverGrantRecordedEvent;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.core.model.DependentAccess;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The consent ledger: one immutable {@code consent_records} row per lifecycle step of a caregiver grant, with a
 * snapshot of the grant right after the step, and the patient's paged view of it.
 *
 * Rows are written by {@link #record} from the CaregiverGrantRecordedEvent listener, inside the transaction of the
 * grant change (a failure rolls the change back). Writing a row is not audited separately: the grant change is
 * already audited as DEPENDENT_ACCESS and the row is itself the record. Reads are audited as CONSENT.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsentLedgerService {

    static final String RESOURCE = "CONSENT";

    private final ConsentRecordRepository consentRecordRepository;
    private final AccessGrantRepository accessGrantRepository;
    private final AccessPeople people;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Writes the ledger row of one grant step. The grant is read as it is now, inside the publisher's transaction,
     * that is right after the step.
     *
     * @return the stored row, or empty when the grant does not exist (nothing to snapshot; logged)
     */
    @Transactional
    public Optional<ConsentRecord> record(CaregiverGrantRecordedEvent event) {
        if (event == null || event.accessId() == null) {
            return Optional.empty();
        }
        DependentAccess grant = accessGrantRepository.findById(event.accessId()).orElse(null);
        if (grant == null) {
            log.warn("Consent ledger: grant {} of a {} step does not exist; no row written", event.accessId(), event.kind());
            return Optional.empty();
        }
        boolean ended = CaregiverGrantRecordedEvent.REVOKED.equals(event.kind())
                || CaregiverGrantRecordedEvent.EXPIRED.equals(event.kind());
        LocalDateTime now = LocalDateTime.now(clock);
        ConsentRecord row = ConsentRecord.builder()
                .patientSystemId(grant.getPatientSystemId())
                .caregiverSystemId(grant.getCaregiverSystemId())
                .grantedBySystemId(event.actorSystemId())
                .accessId(grant.getAccessId())
                .eventKind(event.kind())
                .consentType(ConsentRecord.CONSENT_TYPE_DATA_ACCESS)
                .scope(toJson(snapshot(grant, event.guardianAttested())))
                .signedDate(now)
                .expiresAt(grant.getExpiresAt())
                .isActive(!ended)
                .revokedAt(CaregiverGrantRecordedEvent.REVOKED.equals(event.kind())
                        ? (grant.getRevokedAt() != null ? grant.getRevokedAt() : now) : null)
                .build();
        return Optional.of(consentRecordRepository.save(row));
    }

    /**
     * The caller's ledger, newest first. PATIENT only. {@code accessId} narrows it to one grant; an id that is not a
     * grant of the caller simply gives an empty page. Audited as one READ CONSENT row when the page is not empty.
     */
    public PageResponse<ConsentRecordResponse> listForCaller(Integer accessId, Integer page, Integer size) {
        authorizationService.requireRole(AuthorizationService.PATIENT);
        String patientSystemId = authorizationService.currentSystemId();
        Page<ConsentRecord> rows = consentRecordRepository.findLedger(patientSystemId, accessId,
                PageParams.of(page, size, Sort.unsorted()));
        Map<String, Person> caregivers = people.byId(rows.getContent().stream()
                .map(ConsentRecord::getCaregiverSystemId).toList());
        List<ConsentRecordResponse> content = rows.getContent().stream()
                .map(row -> toResponse(row, caregivers.get(row.getCaregiverSystemId())))
                .toList();
        if (!content.isEmpty()) {
            auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        }
        return PageResponse.of(rows, content);
    }

    // ==================== SNAPSHOT ====================

    /**
     * Wall-clock format of {@code scope.expiresAt}: always with seconds, like every other date-time of the API
     * ({@code LocalDateTime.toString()} drops {@code :00} seconds, so 23:59:00 and 10:00 would differ in shape).
     */
    static final DateTimeFormatter SCOPE_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** The grant's state as the ledger stores it (stored flags, as on the grant row; see ConsentScopeResponse). */
    static ConsentScopeResponse snapshot(DependentAccess grant, boolean guardianAttested) {
        boolean messaging = Boolean.TRUE.equals(grant.getCanMessageCareTeam());
        boolean calls = Boolean.TRUE.equals(grant.getCanJoinCalls());
        return ConsentScopeResponse.builder()
                .accessLevel(grant.getAccessLevel())
                .canViewRecords(Boolean.TRUE.equals(grant.getCanViewRecords()))
                .canViewAppointments(Boolean.TRUE.equals(grant.getCanViewAppointments()))
                .canViewMedications(Boolean.TRUE.equals(grant.getCanViewMedications()))
                .canViewLabResults(Boolean.TRUE.equals(grant.getCanViewLabResults()))
                .canViewImaging(Boolean.TRUE.equals(grant.getCanViewImaging()))
                .canViewConsultations(Boolean.TRUE.equals(grant.getCanViewConsultations()))
                .canViewBilling(Boolean.TRUE.equals(grant.getCanViewBilling()))
                .canScheduleAppointments(Boolean.TRUE.equals(grant.getCanScheduleAppointments()))
                .canManageCarePlan(Boolean.TRUE.equals(grant.getCanManageCarePlan()))
                .canManageBilling(Boolean.TRUE.equals(grant.getCanManageBilling()))
                .canMessageCareTeam(messaging)
                .canJoinCalls(calls)
                .messagingApproved(messaging && grant.getMessagingApprovedAt() != null)
                .callsApproved(calls && grant.getCallsApprovedAt() != null)
                .expiresAt(grant.getExpiresAt() == null ? null
                        : grant.getExpiresAt().truncatedTo(ChronoUnit.SECONDS).format(SCOPE_DATE_TIME))
                .guardianAttested(guardianAttested)
                .build();
    }

    private String toJson(ConsentScopeResponse scope) {
        try {
            return objectMapper.writeValueAsString(scope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the consent scope", e);
        }
    }

    private ConsentScopeResponse fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ConsentScopeResponse.class);
        } catch (JsonProcessingException e) {
            log.warn("Consent ledger: unreadable scope ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    private ConsentRecordResponse toResponse(ConsentRecord row, Person caregiver) {
        return ConsentRecordResponse.builder()
                .consentId(row.getConsentId())
                .eventKind(row.getEventKind())
                .accessId(row.getAccessId())
                .patientSystemId(row.getPatientSystemId())
                .caregiverSystemId(row.getCaregiverSystemId())
                .caregiverName(AccessPeople.fullName(caregiver))
                .grantedBySystemId(row.getGrantedBySystemId())
                .scope(fromJson(row.getScope()))
                .recordedAt(row.getSignedDate())
                .build();
    }
}
