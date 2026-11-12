package com.carenexus.api.account.datarights.service;

import com.carenexus.api.account.datarights.dto.response.AccountRequestResponse;
import com.carenexus.api.account.datarights.dto.response.AdminAccountRequestResponse;
import com.carenexus.api.account.datarights.model.AccountRequest;
import com.carenexus.api.auth.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Builds the API views of {@link AccountRequest} rows. {@code downloadable} and {@code fileName} are computed here,
 * at response time; {@code resultRef} is never exposed.
 *
 * Stored times are clinic wall-clock values (the application clock's zone); they are returned as UTC instants.
 */
@Component
@RequiredArgsConstructor
public class AccountRequestViews {

    static final String FILE_NAME_PREFIX = "carenexus-data-export-";
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE; // yyyyMMdd

    private final Clock clock;

    public AccountRequestResponse toResponse(AccountRequest row) {
        return new AccountRequestResponse(row.getRequestId(), row.getType(), row.getStatus(), row.getReason(),
                row.getDecisionReason(), toInstant(row.getRequestedAt()), toInstant(row.getProcessedAt()),
                toInstant(row.getExpiresAt()), isDownloadable(row), fileName(row));
    }

    /**
     * @param account the users row of {@code row.getSystemId()}; null only if it vanished, in which case the account
     *                fields fall back to neutral values
     */
    public AdminAccountRequestResponse toAdminResponse(AccountRequest row, User account) {
        String role = account != null ? account.getRole() : roleOf(row.getSystemId());
        return new AdminAccountRequestResponse(row.getRequestId(), row.getType(), row.getStatus(), row.getReason(),
                row.getDecisionReason(), toInstant(row.getRequestedAt()), toInstant(row.getProcessedAt()),
                toInstant(row.getExpiresAt()), isDownloadable(row), fileName(row), row.getSystemId(),
                account != null ? account.getFirstName() : DataRightsConstants.ANONYMISED_FIRST_NAME,
                account != null ? account.getLastName() : DataRightsConstants.ANONYMISED_LAST_NAME,
                account != null ? account.getEmail() : null,
                role,
                account != null && Boolean.TRUE.equals(account.getIsActive()),
                row.getProcessedBySystemId());
    }

    /** True only for a READY export whose window is still open at this moment. */
    public boolean isDownloadable(AccountRequest row) {
        return row.isType(AccountRequest.TYPE_EXPORT)
                && row.hasStatus(AccountRequest.STATUS_READY)
                && row.getExpiresAt() != null
                && row.getExpiresAt().isAfter(now());
    }

    /** {@code carenexus-data-export-<systemId>-<yyyyMMdd>.json} for an export (clinic date of the request), else null. */
    public String fileName(AccountRequest row) {
        if (!row.isType(AccountRequest.TYPE_EXPORT) || row.getRequestedAt() == null) {
            return null;
        }
        return FILE_NAME_PREFIX + row.getSystemId() + "-" + row.getRequestedAt().toLocalDate().format(FILE_DATE)
                + ".json";
    }

    public LocalDateTime now() {
        // Microsecond precision, as stored by PostgreSQL TIMESTAMP, so a value read back equals the value written.
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /** The zone stored times are written in (the application clock's clinic zone). */
    public ZoneId clinicZone() {
        return clock.getZone();
    }

    public Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toInstant();
    }

    private static String roleOf(String systemId) {
        if (systemId == null) {
            return null;
        }
        if (systemId.startsWith("PAT-")) return "PATIENT";
        if (systemId.startsWith("DOC-")) return "DOCTOR";
        if (systemId.startsWith("CGV-")) return "CAREGIVER";
        if (systemId.startsWith("ADM-")) return "ADMIN";
        return null;
    }
}
