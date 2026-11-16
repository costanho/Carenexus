package com.carenexus.api.account.datarights.service;

import com.carenexus.api.account.datarights.dto.request.RejectAccountRequest;
import com.carenexus.api.account.datarights.dto.response.AdminAccountRequestResponse;
import com.carenexus.api.account.datarights.model.AccountRequest;
import com.carenexus.api.account.datarights.repository.AccountRequestRepository;
import com.carenexus.api.account.datarights.repository.DataRightsUserRepository;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.event.UserDeactivatedEvent;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Administrator side of the account requests (endpoints 8 to 11): the queue of every user's exports, closures and
 * deletion requests, and the decision on deletion requests. Approval anonymises the users row; nothing is ever
 * cascade-deleted. An administrator never decides their own request.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AccountRequestAdminService {

    static final String MSG_NOT_FOUND = "Account request not found";
    static final String MSG_NOT_DELETION = "Only deletion requests can be approved or rejected";
    static final String MSG_ALREADY_REJECTED = "This request was already rejected";
    static final String MSG_ALREADY_APPROVED = "This request was already approved";
    static final String MSG_CANCELLED = "This request was cancelled by the user";
    static final String MSG_OWN_REQUEST = "You cannot decide your own request";
    static final String MSG_INVALID_TYPE = "Invalid type. Must be EXPORT, CLOSURE or DELETION";
    static final String MSG_INVALID_STATUS =
            "Invalid status. Must be REQUESTED, PROCESSING, READY, COMPLETED, REJECTED or CANCELLED";

    private final AccountRequestRepository requests;
    private final DataRightsUserRepository users;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final AuthService authService;
    private final ApplicationEventPublisher eventPublisher;
    private final DataRightsMessages messages;
    private final AccountRequestViews views;

    /** Every account request, optionally filtered, newest first. Not audited (no patient data). */
    @Transactional(readOnly = true)
    public PageResponse<AdminAccountRequestResponse> list(String type, String status, int page, int size) {
        authorizationService.requireAdmin();
        String typeFilter = normalize(type, AccountRequest.TYPES, MSG_INVALID_TYPE);
        String statusFilter = normalize(status, AccountRequest.STATUSES, MSG_INVALID_STATUS);
        Page<AccountRequest> rows = requests.search(typeFilter, statusFilter, PageParams.of(page, size, Sort.unsorted()));
        Map<String, User> accounts = accountsOf(rows.getContent());
        return PageResponse.of(rows, rows.getContent().stream()
                .map(row -> views.toAdminResponse(row, accounts.get(row.getSystemId())))
                .toList());
    }

    @Transactional(readOnly = true)
    public AdminAccountRequestResponse get(Integer requestId) {
        authorizationService.requireAdmin();
        AccountRequest row = requests.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_NOT_FOUND));
        return view(row);
    }

    /**
     * Approves a deletion request: the account is anonymised (neutral name, email, phone and password removed,
     * inactive, closed), every session is revoked and the request becomes COMPLETED. Already COMPLETED: unchanged.
     */
    public AdminAccountRequestResponse approve(Integer requestId) {
        authorizationService.requireAdmin();
        String adminSystemId = authorizationService.currentSystemId();
        AccountRequest request = lockDeletionRequest(requestId);
        if (request.hasStatus(AccountRequest.STATUS_COMPLETED)) {
            return view(request);
        }
        if (request.hasStatus(AccountRequest.STATUS_REJECTED)) {
            throw new ConflictException(MSG_ALREADY_REJECTED);
        }
        if (request.hasStatus(AccountRequest.STATUS_CANCELLED)) {
            throw new ConflictException(MSG_CANCELLED);
        }
        if (adminSystemId.equals(request.getSystemId())) {
            throw new ConflictException(MSG_OWN_REQUEST);
        }

        String systemId = request.getSystemId();
        User account = users.lockBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_NOT_FOUND));
        LocalDateTime now = views.now();

        // 1. The goodbye email goes to the address the account has right now.
        messages.accountDeleted(systemId, account.getEmail(), account.getFirstName(), request);

        // 2. Anonymise the users row only. The system id stays; every other table keeps pointing at it.
        boolean wasActive = Boolean.TRUE.equals(account.getIsActive());
        account.setFirstName(DataRightsConstants.ANONYMISED_FIRST_NAME);
        account.setLastName(DataRightsConstants.ANONYMISED_LAST_NAME);
        account.setEmail(null);
        account.setPhone(null);
        account.setPasswordHash(null);
        account.setIsActive(false);
        if (account.getClosedAt() == null) {
            account.setClosedAt(now);
        }
        account.setClosureReason(DataRightsConstants.ANONYMISED_CLOSURE_REASON);
        users.saveAndFlush(account);
        if (AuthorizationService.DOCTOR.equals(account.getRole())) {
            users.deactivateDoctorProfile(systemId);
        }
        if (AuthorizationService.PATIENT.equals(account.getRole())) {
            users.clearPatientHealthStatusNote(systemId);
        }

        // 3. End every session; downstream clean-up only when this approval is what deactivated the account.
        authService.revokeAllSessions(systemId);
        if (wasActive) {
            eventPublisher.publishEvent(new UserDeactivatedEvent(systemId, account.getRole(), adminSystemId,
                    UserDeactivatedEvent.CLOSURE));
        }

        // 4. The request is done.
        request.setStatus(AccountRequest.STATUS_COMPLETED);
        request.setProcessedAt(now);
        request.setProcessedBySystemId(adminSystemId);
        request.setUpdatedAt(now);
        requests.saveAndFlush(request);

        auditService.logAccountEvent(adminSystemId, DataRightsConstants.ACTION_DELETION_APPROVE,
                DataRightsConstants.RESOURCE_ACCOUNT, systemId, "SUCCESS");
        return views.toAdminResponse(request, account);
    }

    /**
     * Declines a deletion request; the account is not changed and the requester is notified. Already REJECTED:
     * unchanged (the first reason is kept, no second notification).
     */
    public AdminAccountRequestResponse reject(Integer requestId, RejectAccountRequest body) {
        authorizationService.requireAdmin();
        String adminSystemId = authorizationService.currentSystemId();
        AccountRequest request = lockDeletionRequest(requestId);
        if (request.hasStatus(AccountRequest.STATUS_REJECTED)) {
            return view(request);
        }
        if (request.hasStatus(AccountRequest.STATUS_COMPLETED)) {
            throw new ConflictException(MSG_ALREADY_APPROVED);
        }
        if (request.hasStatus(AccountRequest.STATUS_CANCELLED)) {
            throw new ConflictException(MSG_CANCELLED);
        }
        if (adminSystemId.equals(request.getSystemId())) {
            throw new ConflictException(MSG_OWN_REQUEST);
        }

        LocalDateTime now = views.now();
        request.setStatus(AccountRequest.STATUS_REJECTED);
        request.setDecisionReason(AccountDataRightsService.trimToNull(body.getReason()));
        request.setProcessedAt(now);
        request.setProcessedBySystemId(adminSystemId);
        request.setUpdatedAt(now);
        requests.saveAndFlush(request);

        messages.deletionRejected(request, adminSystemId);
        auditService.logAccountEvent(adminSystemId, DataRightsConstants.ACTION_DELETION_REJECT,
                DataRightsConstants.RESOURCE_ACCOUNT, request.getSystemId(), "SUCCESS");
        return view(request);
    }

    // ==================== HELPERS ====================

    private AccountRequest lockDeletionRequest(Integer requestId) {
        AccountRequest request = requests.findByIdForUpdate(requestId)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_NOT_FOUND));
        if (!request.isType(AccountRequest.TYPE_DELETION)) {
            throw new ConflictException(MSG_NOT_DELETION);
        }
        return request;
    }

    private AdminAccountRequestResponse view(AccountRequest row) {
        return views.toAdminResponse(row, users.findBySystemId(row.getSystemId()).orElse(null));
    }

    /** The users rows of a page, read with one IN query. */
    private Map<String, User> accountsOf(List<AccountRequest> rows) {
        List<String> systemIds = rows.stream().map(AccountRequest::getSystemId).distinct().toList();
        if (systemIds.isEmpty()) {
            return Map.of();
        }
        return users.findBySystemIdIn(systemIds).stream()
                .collect(Collectors.toMap(User::getSystemId, Function.identity(), (a, b) -> a));
    }

    /** Trimmed, upper-cased filter value; blank means no filter; anything else must be one of {@code allowed}. */
    private static String normalize(String value, List<String> allowed, String error) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BadRequestException(error);
        }
        return normalized;
    }
}
