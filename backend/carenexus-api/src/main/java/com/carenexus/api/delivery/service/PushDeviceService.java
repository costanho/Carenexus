package com.carenexus.api.delivery.service;

import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.delivery.dto.request.RegisterPushDeviceRequest;
import com.carenexus.api.delivery.dto.response.PushDeviceResponse;
import com.carenexus.api.delivery.model.PushDevice;
import com.carenexus.api.delivery.repository.PushDeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Push device tokens of the signed-in user. The owner is always the JWT caller: a user registers, refreshes and
 * removes only their own devices, and a token that is registered to someone else is reported as not found.
 * No patient data is involved, so no caregiver permission applies and nothing is audited.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PushDeviceService {

    private static final Pattern TOKEN = Pattern.compile(RegisterPushDeviceRequest.TOKEN_PATTERN);

    /** xmax = 0 only for a freshly inserted row, so one statement tells an insert from an update. */
    private static final String UPSERT_SQL = "INSERT INTO push_devices (system_id, platform, token, last_seen_at, created_at) "
            + "VALUES (?, ?, ?, ?, ?) "
            + "ON CONFLICT (token) DO UPDATE SET system_id = EXCLUDED.system_id, platform = EXCLUDED.platform, "
            + "last_seen_at = EXCLUDED.last_seen_at "
            + "RETURNING device_id, (xmax = 0) AS inserted";
    private static final int[] UPSERT_TYPES = {Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
            Types.TIMESTAMP_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE};

    private final PushDeviceRepository pushDevices;
    private final AuthorizationService authorizationService;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** The stored device and whether this call created the row (201) or refreshed / re-assigned it (200). */
    public record Registration(PushDeviceResponse device, boolean created) {
    }

    /**
     * Registers or refreshes a token for the caller (upsert by token). A token registered to another account moves
     * to the caller: a device follows the account signed in on it. Afterwards the caller keeps only the
     * {@link PushDevice#MAX_DEVICES_PER_USER} most recently seen devices.
     */
    public Registration register(RegisterPushDeviceRequest request) {
        String systemId = authorizationService.currentSystemId();
        if (request == null || request.getPlatform() == null) {
            throw new BadRequestException("platform: must not be null");
        }
        String platform = request.getPlatform().trim().toUpperCase(Locale.ROOT);
        if (!PushDevice.PLATFORMS.contains(platform)) {
            throw new BadRequestException("platform: " + RegisterPushDeviceRequest.PLATFORM_MESSAGE);
        }
        String token = request.getToken();
        if (token == null) {
            throw new BadRequestException("token: must not be null");
        }
        if (!TOKEN.matcher(token).matches()) {
            throw new BadRequestException("token: " + RegisterPushDeviceRequest.TOKEN_MESSAGE);
        }

        // PostgreSQL keeps microseconds; answer with exactly what was stored.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime stamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        Map<String, Object> row = jdbc.queryForMap(UPSERT_SQL,
                new Object[]{systemId, platform, token, stamp, stamp}, UPSERT_TYPES);
        Integer deviceId = ((Number) row.get("device_id")).intValue();
        boolean created = Boolean.TRUE.equals(row.get("inserted"));

        int dropped = pushDevices.deleteBeyondNewest(systemId, PushDevice.MAX_DEVICES_PER_USER);
        if (dropped > 0) {
            log.debug("Dropped {} least recently seen push device(s) of {}", dropped, systemId);
        }
        return new Registration(new PushDeviceResponse(deviceId, platform, now), created);
    }

    /**
     * Removes one of the caller's tokens (hard delete).
     *
     * @throws ResourceNotFoundException when the token is not registered, or is registered to another user
     */
    public void remove(String token) {
        String systemId = authorizationService.currentSystemId();
        if (token == null || token.isEmpty() || pushDevices.deleteOwn(systemId, token) == 0) {
            throw new ResourceNotFoundException("Device not found");
        }
    }

    // ==================== FOR BACKEND CALLERS (no caller identity) ====================

    /** True when the account has at least one registered device (the push channel has an address). */
    @Transactional(readOnly = true)
    public boolean hasDevice(String systemId) {
        return systemId != null && pushDevices.existsBySystemId(systemId);
    }

    /**
     * Removes tokens a push provider reported as no longer registered. For provider adapters only: it is not tied
     * to a caller and is never exposed over HTTP.
     *
     * @return how many rows were removed
     */
    public int forgetTokens(Collection<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return 0;
        }
        return pushDevices.deleteByTokenIn(tokens);
    }
}
