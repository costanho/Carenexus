package com.carenexus.api.account.mfa;

import com.carenexus.api.account.mfa.crypto.Base32;
import com.carenexus.api.account.mfa.service.TotpService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotpServiceTest {

    /** RFC 6238 appendix B: the SHA-1 seed is the ASCII string "12345678901234567890". */
    private static final byte[] RFC_SEED = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    private static TotpService at(long epochSeconds) {
        return new TotpService(Clock.fixed(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC));
    }

    private static long step(long epochSeconds) {
        return epochSeconds / 30;
    }

    @Test
    void rfc6238_sha1TestVectors_eightDigits() {
        assertEquals("94287082", TotpService.codeAt(RFC_SEED, step(59L), 8));
        assertEquals("07081804", TotpService.codeAt(RFC_SEED, step(1111111109L), 8));
        assertEquals("14050471", TotpService.codeAt(RFC_SEED, step(1111111111L), 8));
        assertEquals("89005924", TotpService.codeAt(RFC_SEED, step(1234567890L), 8));
        assertEquals("69279037", TotpService.codeAt(RFC_SEED, step(2000000000L), 8));
        assertEquals("65353130", TotpService.codeAt(RFC_SEED, step(20000000000L), 8));
    }

    @Test
    void rfc6238_vectorsAreAcceptedAsSixDigitCodesAtTheirTime() {
        long[][] vectors = {
                {59L, 287082}, {1111111109L, 81804}, {1111111111L, 50471},
                {1234567890L, 5924}, {2000000000L, 279037}, {20000000000L, 353130}};
        for (long[] v : vectors) {
            String code = String.format("%06d", v[1]);
            OptionalLong matched = at(v[0]).verify(RFC_SEED, code, null);
            assertEquals(OptionalLong.of(step(v[0])), matched, "time " + v[0]);
        }
    }

    @Test
    void verify_acceptsOneStepEitherSide_andNothingFurther() {
        long now = 1_700_000_000L;
        TotpService totp = at(now);
        long current = step(now);
        assertEquals(OptionalLong.of(current - 1), totp.verify(RFC_SEED, TotpService.codeAt(RFC_SEED, current - 1, 6), null));
        assertEquals(OptionalLong.of(current), totp.verify(RFC_SEED, TotpService.codeAt(RFC_SEED, current, 6), null));
        assertEquals(OptionalLong.of(current + 1), totp.verify(RFC_SEED, TotpService.codeAt(RFC_SEED, current + 1, 6), null));
        assertTrue(totp.verify(RFC_SEED, TotpService.codeAt(RFC_SEED, current - 2, 6), null).isEmpty());
        assertTrue(totp.verify(RFC_SEED, TotpService.codeAt(RFC_SEED, current + 2, 6), null).isEmpty());
    }

    @Test
    void verify_rejectsReplay_ofTheLastAcceptedStepAndOlder() {
        long now = 1_700_000_000L;
        TotpService totp = at(now);
        long current = step(now);
        String code = TotpService.codeAt(RFC_SEED, current, 6);

        OptionalLong first = totp.verify(RFC_SEED, code, null);
        assertEquals(OptionalLong.of(current), first);
        assertTrue(totp.verify(RFC_SEED, code, first.getAsLong()).isEmpty(), "same code twice");
        assertTrue(totp.verify(RFC_SEED, TotpService.codeAt(RFC_SEED, current - 1, 6), current).isEmpty(),
                "an older code after a newer one");
        assertEquals(OptionalLong.of(current + 1),
                totp.verify(RFC_SEED, TotpService.codeAt(RFC_SEED, current + 1, 6), current), "the next step is fine");
    }

    @Test
    void verify_rejectsMalformedCodes() {
        TotpService totp = at(1_700_000_000L);
        assertTrue(totp.verify(RFC_SEED, null, null).isEmpty());
        assertTrue(totp.verify(RFC_SEED, "", null).isEmpty());
        assertTrue(totp.verify(RFC_SEED, "12345", null).isEmpty());
        assertTrue(totp.verify(RFC_SEED, "1234567", null).isEmpty());
        assertTrue(totp.verify(RFC_SEED, "12a456", null).isEmpty());
        assertTrue(totp.verify(RFC_SEED, " 123456", null).isEmpty());
        assertTrue(totp.verify(null, "123456", null).isEmpty());
    }

    @Test
    void codesKeepLeadingZeros() {
        // 1234567890 gives 89005924 with eight digits, so the six-digit code starts with zeros.
        assertEquals("005924", TotpService.codeAt(RFC_SEED, step(1234567890L), 6));
    }

    @Test
    void secrets_are160BitsAnd32Base32Characters() {
        TotpService totp = at(0);
        byte[] secret = totp.newSecret();
        assertEquals(20, secret.length);
        String base32 = totp.toBase32(secret);
        assertTrue(base32.matches("^[A-Z2-7]{32}$"), base32);
        assertArrayEquals(secret, Base32.decode(base32));
    }

    @Test
    void base32_matchesRfc4648Vectors_andDecodesLeniently() {
        assertEquals("", Base32.encode(new byte[0]));
        assertEquals("MY", Base32.encode("f".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("MZXW6", Base32.encode("foo".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII)));
        assertArrayEquals("foobar".getBytes(StandardCharsets.US_ASCII), Base32.decode("mzxw 6ytb-oi======"));
        assertThrows(IllegalArgumentException.class, () -> Base32.decode("MZXW1"));
    }

    @Test
    void otpauthUri_hasTheContractShape() {
        String uri = at(0).otpauthUri("tariro.ncube@carenexus.test", "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP");
        assertEquals("otpauth://totp/CareNexus:tariro.ncube%40carenexus.test?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
                + "&issuer=CareNexus&algorithm=SHA1&digits=6&period=30", uri);
    }
}
