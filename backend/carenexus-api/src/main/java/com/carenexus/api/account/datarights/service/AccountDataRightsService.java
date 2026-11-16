package com.carenexus.api.account.datarights.service;

import com.carenexus.api.account.datarights.dto.request.CloseAccountRequest;
import com.carenexus.api.account.datarights.dto.request.DataExportRequest;
import com.carenexus.api.account.datarights.dto.request.DeletionRequest;
import com.carenexus.api.account.datarights.dto.response.AccountClosureResponse;
import com.carenexus.api.account.datarights.dto.response.AccountRequestResponse;
import com.carenexus.api.account.datarights.model.AccountRequest;
import com.carenexus.api.account.datarights.repository.AccountRequestRepository;
import com.carenexus.api.account.datarights.repository.DataRightsUserRepository;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.event.UserDeactivatedEvent;
import com.carenexus.api.common.exception.ApiException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.storage.FileStorage;
import com.carenexus.api.storage.StorageException;
import com.carenexus.api.storage.StoredFileNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The caller's own data rights (endpoints 1 to 7 of the API contract): data export and download, self-service
 * closure, and deletion requests. Every method acts on the authenticated caller only; no request names another
 * user, and a row of another user answers 404. Caregiver grants change nothing here.
 */
@Service
@Slf4j
@Transactional
public class AccountDataRightsService {

    static final String MSG_EXPORT_IN_PROGRESS = "A data export is already being prepared";
    static final String MSG_EXPORT_NOT_FOUND = "Export not found";
    static final String MSG_EXPORT_NOT_READY = "This export is not ready yet";
    static final String MSG_EXPORT_GONE = "This export is no longer available. Request a new one.";
    static final String MSG_STORAGE_UNAVAILABLE = "Data exports are temporarily unavailable. Please try again later.";
    static final String MSG_LAST_ADMIN = "The last active administrator cannot close their account";
    static final String MSG_DELETION_PENDING = "You already have a deletion request awaiting review";
    static final String MSG_DELETION_NOT_FOUND = "Deletion request not found";
    static final String MSG_DELETION_DECIDED = "This request has already been decided";

    private final AccountRequestRepository requests;
    private final DataRightsUserRepository users;
    private final AuthorizationService authorizationService;
    private final PasswordConfirmation passwordConfirmation;
    private final RateLimitGuard rateLimitGuard;
    private final DataExportBundleBuilder bundleBuilder;
    private final FileStorage fileStorage;
    private final DataRightsMessages messages;
    private final AuditService auditService;
    private final AuthService authService;
    private final ApplicationEventPublisher eventPublisher;
    private final AccountRequestViews views;
    private final int exportDays;

    public AccountDataRightsService(AccountRequestRepository requests, DataRightsUserRepository users,
                                    AuthorizationService authorizationService,
                                    PasswordConfirmation passwordConfirmation, RateLimitGuard rateLimitGuard,
                                    DataExportBundleBuilder bundleBuilder, FileStorage fileStorage,
                                    DataRightsMessages messages, AuditService auditService, AuthService authService,
                                    ApplicationEventPublisher eventPublisher, AccountRequestViews views,
                                    @Value("${carenexus.retention.export-days:7}") int exportDays) {
        if (exportDays < 1) {
            throw new IllegalStateException("carenexus.retention.export-days must be at least 1");
        }
        this.requests = requests;
        this.users = users;
        this.authorizationService = authorizationService;
        this.passwordConfirmation = passwordConfirmation;
        this.rateLimitGuard = rateLimitGuard;
        this.bundleBuilder = bundleBuilder;
        this.fileStorage = fileStorage;
        this.messages = messages;
        this.auditService = auditService;
        this.authService = authService;
        this.eventPublisher = eventPublisher;
        this.views = views;
        this.exportDays = exportDays;
    }

    /** The open download in a successful {@link #openExport} call; the caller closes the stream. */
    public record ExportDownload(String fileName, InputStream content) {
    }

    // ==================== EXPORT ====================

    /**
     * Builds the caller's data bundle synchronously, stores it for {@code export-days} days and answers READY.
     * Earlier READY exports of the caller become COMPLETED and their files are deleted after commit; a failed build
     * leaves no row and no file.
     */
    public AccountRequestResponse requestExport(DataExportRequest body) {
        String caller = authorizationService.currentSystemId();
        passwordConfirmation.countAttempt(caller);
        User user = currentUser(caller);
        passwordConfirmation.verify(user, body.getPassword(), DataRightsConstants.ACTION_EXPORT_REQUEST,
                DataRightsConstants.RESOURCE_DATA_EXPORT);

        // Serialises requests of this user: a second export waits for the first to finish.
        users.lockBySystemId(caller);
        if (requests.existsBySystemIdAndTypeAndStatusIn(caller, AccountRequest.TYPE_EXPORT,
                AccountRequest.EXPORT_IN_PROGRESS)) {
            throw new ConflictException(MSG_EXPORT_IN_PROGRESS);
        }
        rateLimitGuard.check(DataRightsConstants.BUCKET_EXPORT, caller);

        LocalDateTime requestedAt = views.now();
        AccountRequest export = requests.saveAndFlush(AccountRequest.builder()
                .systemId(caller)
                .type(AccountRequest.TYPE_EXPORT)
                .status(AccountRequest.STATUS_PROCESSING)
                .requestedAt(requestedAt)
                .updatedAt(requestedAt)
                .build());

        byte[] bundle = bundleBuilder.build(user, views.toInstant(requestedAt));
        String key = DataRightsConstants.EXPORT_STORAGE_AREA + "/" + requestedAt.getYear() + "/"
                + String.format(Locale.ROOT, "%02d", requestedAt.getMonthValue()) + "/" + UUID.randomUUID();
        deleteOnRollback(key);
        fileStorage.put(key, DataRightsConstants.EXPORT_CONTENT_TYPE, new ByteArrayInputStream(bundle), bundle.length);

        LocalDateTime readyAt = views.now();
        export.setStatus(AccountRequest.STATUS_READY);
        export.setResultRef(key);
        export.setExpiresAt(requestedAt.plusDays(exportDays));
        export.setProcessedAt(readyAt);
        export.setUpdatedAt(readyAt);

        List<String> supersededKeys = new ArrayList<>();
        for (AccountRequest older : requests.findReadyExportsExcept(caller, export.getRequestId())) {
            older.setStatus(AccountRequest.STATUS_COMPLETED);
            older.setUpdatedAt(readyAt);
            if (older.getResultRef() != null) {
                supersededKeys.add(older.getResultRef());
            }
        }
        deleteAfterCommit(supersededKeys);

        messages.exportReady(user, export, views.clinicZone());
        auditService.logAccountEvent(caller, DataRightsConstants.ACTION_EXPORT_REQUEST,
                DataRightsConstants.RESOURCE_DATA_EXPORT, caller, "SUCCESS");
        return views.toResponse(export);
    }

    /** The caller's exports, newest first, at most 20. Read-only. */
    @Transactional(readOnly = true)
    public List<AccountRequestResponse> listExports() {
        return ownRows(AccountRequest.TYPE_EXPORT);
    }

    /**
     * Opens the file of one of the caller's exports and audits the download. Another user's export, ADMIN
     * included, answers 404 exactly like a missing one.
     */
    public ExportDownload openExport(Integer requestId) {
        String caller = authorizationService.currentSystemId();
        AccountRequest export = requests.findById(requestId)
                .filter(row -> caller.equals(row.getSystemId()) && row.isType(AccountRequest.TYPE_EXPORT))
                .orElseThrow(() -> new ResourceNotFoundException(MSG_EXPORT_NOT_FOUND));
        if (AccountRequest.EXPORT_IN_PROGRESS.contains(export.getStatus())) {
            throw new ConflictException(MSG_EXPORT_NOT_READY);
        }
        if (!views.isDownloadable(export) || export.getResultRef() == null) {
            throw new ConflictException(MSG_EXPORT_GONE);
        }
        // Audited first: if the file cannot be opened the exception rolls this row back.
        auditService.logAccountEvent(caller, DataRightsConstants.ACTION_EXPORT_DOWNLOAD,
                DataRightsConstants.RESOURCE_DATA_EXPORT, caller, "SUCCESS");
        try {
            return new ExportDownload(views.fileName(export), fileStorage.open(export.getResultRef()));
        } catch (StoredFileNotFoundException e) {
            log.warn("Data export {} has no stored file", export.getRequestId());
            throw new ConflictException(MSG_EXPORT_GONE);
        } catch (StorageException e) {
            log.error("Data export {} could not be opened ({})", export.getRequestId(), e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorResponse.PROVIDER_UNAVAILABLE,
                    MSG_STORAGE_UNAVAILABLE);
        }
    }

    // ==================== CLOSURE ====================

    /**
     * Closes the caller's account at once: inactive, {@code closed_at} and {@code closure_reason} set, every session
     * revoked, {@link UserDeactivatedEvent} (cause CLOSURE) published. Clinical records, messages, audit rows,
     * exports and a pending deletion request are left as they are. Refused for the last active ADMIN.
     */
    public AccountClosureResponse closeAccount(CloseAccountRequest body) {
        String caller = authorizationService.currentSystemId();
        passwordConfirmation.countAttempt(caller);
        User user = currentUser(caller);
        passwordConfirmation.verify(user, body.getPassword(), DataRightsConstants.ACTION_CLOSE,
                DataRightsConstants.RESOURCE_ACCOUNT);

        if (AuthorizationService.ADMIN.equals(user.getRole())) {
            // Locks every active administrator, so two administrators cannot close the last two accounts at once.
            boolean anotherAdmin = users.lockActiveAdmins().stream()
                    .anyMatch(admin -> !caller.equals(admin.getSystemId()));
            if (!anotherAdmin) {
                throw new ConflictException(MSG_LAST_ADMIN);
            }
        } else {
            users.lockBySystemId(caller);
        }

        LocalDateTime now = views.now();
        String reason = trimToNull(body.getReason());
        user.setIsActive(false);
        user.setClosedAt(now);
        user.setClosureReason(reason);
        users.saveAndFlush(user);
        if (AuthorizationService.DOCTOR.equals(user.getRole())) {
            users.deactivateDoctorProfile(caller);
        }

        AccountRequest closure = requests.saveAndFlush(AccountRequest.builder()
                .systemId(caller)
                .type(AccountRequest.TYPE_CLOSURE)
                .status(AccountRequest.STATUS_COMPLETED)
                .reason(reason)
                .requestedAt(now)
                .processedAt(now)
                .processedBySystemId(caller)
                .updatedAt(now)
                .build());

        authService.revokeAllSessions(caller);
        eventPublisher.publishEvent(new UserDeactivatedEvent(caller, user.getRole(), caller, UserDeactivatedEvent.CLOSURE));
        messages.accountClosed(user, closure);
        auditService.logAccountEvent(caller, DataRightsConstants.ACTION_CLOSE, DataRightsConstants.RESOURCE_ACCOUNT,
                caller, "SUCCESS");
        return new AccountClosureResponse(closure.getRequestId(), views.toInstant(now), true,
                AccountClosureResponse.MESSAGE);
    }

    // ==================== DELETION REQUESTS ====================

    /**
     * Files a deletion request for administrator review. Nothing is deleted or deactivated; every other active
     * administrator is notified.
     */
    public AccountRequestResponse requestDeletion(DeletionRequest body) {
        String caller = authorizationService.currentSystemId();
        passwordConfirmation.countAttempt(caller);
        User user = currentUser(caller);
        passwordConfirmation.verify(user, body.getPassword(), DataRightsConstants.ACTION_DELETION_REQUEST,
                DataRightsConstants.RESOURCE_ACCOUNT);

        users.lockBySystemId(caller);
        if (requests.existsBySystemIdAndTypeAndStatusIn(caller, AccountRequest.TYPE_DELETION,
                List.of(AccountRequest.STATUS_REQUESTED))) {
            throw new ConflictException(MSG_DELETION_PENDING);
        }
        LocalDateTime now = views.now();
        AccountRequest request = requests.saveAndFlush(AccountRequest.builder()
                .systemId(caller)
                .type(AccountRequest.TYPE_DELETION)
                .status(AccountRequest.STATUS_REQUESTED)
                .reason(trimToNull(body.getReason()))
                .requestedAt(now)
                .updatedAt(now)
                .build());

        messages.deletionRequested(request, users.findActiveAdminSystemIdsExcept(caller));
        auditService.logAccountEvent(caller, DataRightsConstants.ACTION_DELETION_REQUEST,
                DataRightsConstants.RESOURCE_ACCOUNT, caller, "SUCCESS");
        return views.toResponse(request);
    }

    /** The caller's deletion requests, newest first, at most 20. Read-only. */
    @Transactional(readOnly = true)
    public List<AccountRequestResponse> listDeletionRequests() {
        return ownRows(AccountRequest.TYPE_DELETION);
    }

    /** Withdraws the caller's pending deletion request. Already CANCELLED: unchanged, no audit row. */
    public AccountRequestResponse cancelDeletion(Integer requestId) {
        String caller = authorizationService.currentSystemId();
        AccountRequest request = requests.findByIdForUpdate(requestId)
                .filter(row -> caller.equals(row.getSystemId()) && row.isType(AccountRequest.TYPE_DELETION))
                .orElseThrow(() -> new ResourceNotFoundException(MSG_DELETION_NOT_FOUND));
        if (request.hasStatus(AccountRequest.STATUS_CANCELLED)) {
            return views.toResponse(request);
        }
        if (!request.hasStatus(AccountRequest.STATUS_REQUESTED)) {
            throw new ConflictException(MSG_DELETION_DECIDED);
        }
        LocalDateTime now = views.now();
        request.setStatus(AccountRequest.STATUS_CANCELLED);
        request.setProcessedAt(now);
        request.setProcessedBySystemId(caller);
        request.setUpdatedAt(now);
        requests.saveAndFlush(request);
        auditService.logAccountEvent(caller, DataRightsConstants.ACTION_DELETION_CANCEL,
                DataRightsConstants.RESOURCE_ACCOUNT, caller, "SUCCESS");
        return views.toResponse(request);
    }

    // ==================== HELPERS ====================

    private List<AccountRequestResponse> ownRows(String type) {
        String caller = authorizationService.currentSystemId();
        return requests.findOwn(caller, type, PageRequest.of(0, DataRightsConstants.OWN_LIST_CAP)).stream()
                .map(views::toResponse)
                .toList();
    }

    /** The caller's account; a token whose account vanished is treated as unauthenticated. */
    private User currentUser(String systemId) {
        return users.findBySystemId(systemId)
                .orElseThrow(() -> new InsufficientAuthenticationException("Authentication required"));
    }

    private void deleteOnRollback(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(key);
                }
            }
        });
    }

    private void deleteAfterCommit(List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            keys.forEach(this::deleteQuietly);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                keys.forEach(AccountDataRightsService.this::deleteQuietly);
            }
        });
    }

    private void deleteQuietly(String key) {
        try {
            fileStorage.delete(key);
        } catch (RuntimeException e) {
            // The expiry sweeper cannot find it again (the row no longer points at it); log the class only.
            log.warn("Could not delete a data export file ({})", e.getClass().getSimpleName());
        }
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
