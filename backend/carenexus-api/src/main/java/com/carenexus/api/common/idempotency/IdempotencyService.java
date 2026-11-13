package com.carenexus.api.common.idempotency;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.service.AuthorizationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Duplicate-request protection for create endpoints that accept an {@code Idempotency-Key} header.
 *
 * <pre>
 * IdempotentResult&lt;PrescriptionResponse&gt; result = idempotencyService.execute(
 *         "POST /api/prescriptions", idempotencyKey, PrescriptionResponse.class,
 *         () -&gt; prescriptionService.createPrescription(request));
 * return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
 * </pre>
 *
 * Behaviour, per (caller system id, scope, key):
 * <ul>
 *   <li>blank key: the action just runs;</li>
 *   <li>first call: the key is claimed (committed at once, so a concurrent repeat can see it), the action runs,
 *       and its JSON response is stored in the same transaction as the action's own writes;</li>
 *   <li>repeat after completion: the stored body is returned with {@code replayed = true}; the action does not run;</li>
 *   <li>repeat while the first call is still running: {@link ConflictException} with code
 *       {@code IDEMPOTENCY_IN_PROGRESS} (409);</li>
 *   <li>the action throws: the claim is released, so the client can retry with the same key.</li>
 * </ul>
 * Rows expire after 24 hours ({@link #purgeExpired()} is called by the retention sweeper); an expired key is free
 * again. A claim whose request died without releasing it (process crash) can be taken over after
 * {@link #IN_PROGRESS_TIMEOUT}.
 *
 * Call it from the controller, around the service call. The owner is always the authenticated caller.
 */
@Service
@Slf4j
public class IdempotencyService {

    /** Header carrying the key. */
    public static final String HEADER = "Idempotency-Key";

    static final Duration TTL = Duration.ofHours(24);
    static final Duration IN_PROGRESS_TIMEOUT = Duration.ofMinutes(5);
    static final int MAX_SCOPE_LENGTH = 160;

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final String IN_PROGRESS_MESSAGE = "This request is already being processed. Please wait a moment.";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuthorizationService authorizationService;
    private final Clock clock;
    private final TransactionTemplate requiresNew;
    private final TransactionTemplate required;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper objectMapper, AuthorizationService authorizationService,
                              Clock clock, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.authorizationService = authorizationService;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.required = new TransactionTemplate(transactionManager);
    }

    /**
     * Runs {@code action} at most once per (caller, scope, key).
     *
     * @param scope        {@code "<METHOD> <path template>"}, for example {@code "POST /api/prescriptions"}
     * @param key          value of the Idempotency-Key header; null or blank disables the protection
     * @param responseType type the stored JSON is read back into on a replay
     * @param action       the create operation; its return value is the response body
     * @throws BadRequestException when the key is not 8-64 characters of {@code [A-Za-z0-9_-]}
     * @throws ConflictException   (code IDEMPOTENCY_IN_PROGRESS) when the same key is still being processed
     */
    public <T> IdempotentResult<T> execute(String scope, String key, Class<T> responseType, Supplier<T> action) {
        if (key == null || key.isBlank()) {
            return new IdempotentResult<>(action.get(), false);
        }
        String idemKey = key.trim();
        if (!KEY_PATTERN.matcher(idemKey).matches()) {
            throw new BadRequestException("Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'");
        }
        if (scope == null || scope.isBlank() || scope.length() > MAX_SCOPE_LENGTH) {
            throw new IllegalArgumentException("Idempotency scope must be 1 to " + MAX_SCOPE_LENGTH + " characters");
        }
        String owner = authorizationService.currentSystemId();

        Claim claim = requiresNew.execute(tx -> claim(owner, scope, idemKey));
        if (claim.storedJson() != null) {
            return new IdempotentResult<>(read(claim.storedJson(), responseType), true);
        }

        // If the caller already runs in a transaction, the stored response rolls back with it: free the key then.
        boolean outerTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        if (outerTransaction && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        release(claim.keyId());
                    }
                }
            });
        }

        try {
            T body = required.execute(tx -> {
                T result = action.get();
                jdbc.update("UPDATE idempotency_keys SET response_json = ? WHERE key_id = ?", write(result), claim.keyId());
                return result;
            });
            return new IdempotentResult<>(body, false);
        } catch (RuntimeException | Error failure) {
            if (!outerTransaction) {
                release(claim.keyId());
            }
            throw failure;
        }
    }

    /**
     * Two-phase variant for actions with slow work before their writes (a file scan and upload, for example).
     * The key is claimed and the replay / in-progress checks run first, exactly as in
     * {@link #execute(String, String, Class, Supplier)}. Then {@code prepare} runs with no transaction of this
     * service open, so it holds no pooled connection; then {@code commit} runs together with the stored response in
     * one transaction. When {@code commit} (or that transaction) fails, {@code discard(prepared)} is called and the
     * key is released. When {@code prepare} fails, the key is released and nothing is discarded.
     *
     * @param prepare work done outside the transaction; its result is handed to commit or discard
     * @param commit  the writes; its return value is the response body
     * @param discard undoes the prepared work after a failed commit (best effort; must not throw)
     */
    public <P, T> IdempotentResult<T> execute(String scope, String key, Class<T> responseType, Supplier<P> prepare,
                                              Function<P, T> commit, Consumer<P> discard) {
        if (key == null || key.isBlank()) {
            P prepared = prepare.get();
            try {
                return new IdempotentResult<>(commit.apply(prepared), false);
            } catch (RuntimeException | Error failure) {
                discard.accept(prepared);
                throw failure;
            }
        }
        String idemKey = key.trim();
        if (!KEY_PATTERN.matcher(idemKey).matches()) {
            throw new BadRequestException("Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'");
        }
        if (scope == null || scope.isBlank() || scope.length() > MAX_SCOPE_LENGTH) {
            throw new IllegalArgumentException("Idempotency scope must be 1 to " + MAX_SCOPE_LENGTH + " characters");
        }
        String owner = authorizationService.currentSystemId();

        Claim claim = requiresNew.execute(tx -> claim(owner, scope, idemKey));
        if (claim.storedJson() != null) {
            return new IdempotentResult<>(read(claim.storedJson(), responseType), true);
        }

        boolean outerTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        if (outerTransaction && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        release(claim.keyId());
                    }
                }
            });
        }

        P prepared;
        try {
            prepared = prepare.get();
        } catch (RuntimeException | Error failure) {
            if (!outerTransaction) {
                release(claim.keyId());
            }
            throw failure;
        }
        try {
            T body = required.execute(tx -> {
                T result = commit.apply(prepared);
                jdbc.update("UPDATE idempotency_keys SET response_json = ? WHERE key_id = ?", write(result), claim.keyId());
                return result;
            });
            return new IdempotentResult<>(body, false);
        } catch (RuntimeException | Error failure) {
            discard.accept(prepared);
            if (!outerTransaction) {
                release(claim.keyId());
            }
            throw failure;
        }
    }

    /**
     * Deletes rows past their expiry. Returns the number of rows removed. Called by the retention sweeper.
     */
    public int purgeExpired() {
        Integer removed = requiresNew.execute(tx ->
                jdbc.update("DELETE FROM idempotency_keys WHERE expires_at <= ?", Timestamp.valueOf(now())));
        return removed == null ? 0 : removed;
    }

    // ==================== INTERNALS ====================

    /** The claimed row: {@code storedJson} is non-null when the key was already completed (replay). */
    private record Claim(long keyId, String storedJson) {
    }

    private record Row(long keyId, String responseJson, LocalDateTime createdAt, LocalDateTime expiresAt) {
    }

    /**
     * Claims the key in its own transaction. The UNIQUE constraint decides between concurrent first calls: the
     * loser of the insert reads the winner's row and reports "in progress".
     */
    private Claim claim(String owner, String scope, String idemKey) {
        LocalDateTime now = now();
        Timestamp createdAt = Timestamp.valueOf(now);
        Timestamp expiresAt = Timestamp.valueOf(now.plus(TTL));

        List<Long> inserted = jdbc.queryForList(
                "INSERT INTO idempotency_keys (owner_system_id, scope, idem_key, response_json, created_at, expires_at) "
                        + "VALUES (?, ?, ?, NULL, ?, ?) "
                        + "ON CONFLICT (owner_system_id, scope, idem_key) DO NOTHING RETURNING key_id",
                Long.class, owner, scope, idemKey, createdAt, expiresAt);
        if (!inserted.isEmpty()) {
            return new Claim(inserted.get(0), null);
        }

        List<Row> rows = jdbc.query(
                "SELECT key_id, response_json, created_at, expires_at FROM idempotency_keys "
                        + "WHERE owner_system_id = ? AND scope = ? AND idem_key = ? FOR UPDATE",
                (rs, i) -> new Row(rs.getLong("key_id"), rs.getString("response_json"),
                        rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("expires_at").toLocalDateTime()),
                owner, scope, idemKey);
        if (rows.isEmpty()) {
            // The other request failed and released the key between our insert and our read: the client may retry.
            throw new ConflictException(ErrorResponse.IDEMPOTENCY_IN_PROGRESS, IN_PROGRESS_MESSAGE);
        }
        Row row = rows.get(0);
        boolean expired = !row.expiresAt().isAfter(now);
        if (!expired && row.responseJson() != null) {
            return new Claim(row.keyId(), row.responseJson());
        }
        boolean abandoned = row.responseJson() == null && !row.createdAt().plus(IN_PROGRESS_TIMEOUT).isAfter(now);
        if (expired || abandoned) {
            // Take the stale row over (we hold its row lock) and run the action as a first call.
            jdbc.update("UPDATE idempotency_keys SET response_json = NULL, created_at = ?, expires_at = ? WHERE key_id = ?",
                    createdAt, expiresAt, row.keyId());
            return new Claim(row.keyId(), null);
        }
        throw new ConflictException(ErrorResponse.IDEMPOTENCY_IN_PROGRESS, IN_PROGRESS_MESSAGE);
    }

    /** Frees a claim that never completed, so the same key can be retried. Never masks the original failure. */
    private void release(long keyId) {
        try {
            requiresNew.executeWithoutResult(tx ->
                    jdbc.update("DELETE FROM idempotency_keys WHERE key_id = ? AND response_json IS NULL", keyId));
        } catch (RuntimeException e) {
            log.warn("Could not release idempotency key {}: {}", keyId, e.getClass().getName());
        }
    }

    private String write(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the response of an idempotent request", e);
        }
    }

    private <T> T read(String json, Class<T> responseType) {
        try {
            return objectMapper.readValue(json, responseType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the stored response of an idempotent request", e);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
