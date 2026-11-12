package com.carenexus.api.account.mfa.service;

import com.carenexus.api.account.mfa.crypto.MfaTokens;
import com.carenexus.api.account.mfa.model.MfaRecoveryCode;
import com.carenexus.api.account.mfa.repository.MfaRecoveryCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One-time recovery codes of an account. Internal to the MFA module: callers are {@link MfaAccountService} and
 * {@link MfaChallengeService}, which have already established who the account is, so every method joins the
 * caller's transaction ({@code MANDATORY}) and performs no authorization of its own.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RecoveryCodeService {

    private final MfaRecoveryCodeRepository recoveryCodeRepository;
    private final Clock clock;

    /**
     * Replaces every code of the account (used and unused) with {@link MfaTokens#RECOVERY_CODE_COUNT} new ones.
     *
     * @return the new codes in display form; only their hashes are stored
     */
    public List<String> replace(String systemId) {
        recoveryCodeRepository.deleteBySystemId(systemId);
        List<String> codes = MfaTokens.newRecoveryCodes();
        LocalDateTime now = LocalDateTime.now(clock);
        recoveryCodeRepository.saveAll(codes.stream()
                .map(code -> MfaRecoveryCode.builder()
                        .systemId(systemId)
                        .codeHash(MfaTokens.recoveryCodeHash(code))
                        .createdAt(now)
                        .build())
                .toList());
        return codes;
    }

    /**
     * Uses one recovery code: true when it is an unused code of the account (it is then marked used), false when it
     * is wrong, malformed or was already used.
     */
    public boolean consume(String systemId, String input) {
        String hash = MfaTokens.recoveryCodeHash(input);
        if (hash == null) {
            return false;
        }
        return recoveryCodeRepository.markUsed(systemId, hash, LocalDateTime.now(clock)) == 1;
    }

    /** Unused codes of the account. */
    public int remaining(String systemId) {
        return (int) recoveryCodeRepository.countBySystemIdAndUsedAtIsNull(systemId);
    }

    public void deleteAll(String systemId) {
        recoveryCodeRepository.deleteBySystemId(systemId);
    }
}
