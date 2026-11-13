package com.carenexus.api.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Validator of {@link NoCardNumber}.
 * <p>
 * The text is split into digit runs. A run is a sequence of digit groups joined by short separators (one or two
 * characters out of space, hyphen, dot and slash), as people type card numbers in groups ("4111 1111 1111 1111",
 * "4111.1111.1111.1111", "4111/1111/..."), so a card number followed by an expiry or other digits stays one run
 * ("4111 1111 1111 1111 1229").
 * <ul>
 *   <li>A run of 13 to 19 digits holds a card when a stretch of whole digit groups of 13 to 19 digits passes the Luhn
 *   check (the whole run is one; "4111 1111 1111 1111 5" holds the first four groups).</li>
 *   <li>A run longer than 19 digits holds a card when any contiguous window of 13 to 19 digits passes it.</li>
 *   <li>{@link NoCardNumber#requireCardNetworkPrefix()} (identifiers such as policy or member numbers, where long digit
 *   strings are normal): the window must also begin with a card network prefix ({@link #hasCardNetworkPrefix}), and in
 *   a run longer than 19 digits it must start where a digit group starts.</li>
 * </ul>
 */
public class NoCardNumberValidator implements ConstraintValidator<NoCardNumber, String> {

    static final int MIN_DIGITS = 13;
    static final int MAX_DIGITS = 19;
    static final int MAX_SEPARATOR_LENGTH = 2;

    private boolean requireCardNetworkPrefix;

    @Override
    public void initialize(NoCardNumber annotation) {
        this.requireCardNetworkPrefix = annotation.requireCardNetworkPrefix();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return !containsCardNumber(value, requireCardNetworkPrefix);
    }

    /** Default mode (see the class description). */
    public static boolean containsCardNumber(String text) {
        return containsCardNumber(text, false);
    }

    /**
     * True when {@code text} holds a card number (see the class description). With {@code requireCardNetworkPrefix}
     * only numbers that begin with a card network prefix count.
     */
    public static boolean containsCardNumber(String text, boolean requireCardNetworkPrefix) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        StringBuilder digits = new StringBuilder();
        List<Integer> groupStarts = new ArrayList<>();
        int i = 0;
        int length = text.length();
        while (i < length) {
            char c = text.charAt(i);
            if (isDigit(c)) {
                if (digits.isEmpty()) {
                    groupStarts.add(0);
                }
                digits.append(c);
                i++;
                continue;
            }
            if (!digits.isEmpty() && isSeparator(c)) {
                int j = i;
                while (j < length && isSeparator(text.charAt(j))) {
                    j++;
                }
                if (j - i <= MAX_SEPARATOR_LENGTH && j < length && isDigit(text.charAt(j))) {
                    groupStarts.add(digits.length());
                    i = j;
                    continue;
                }
            }
            if (runHoldsCard(digits, groupStarts, requireCardNetworkPrefix)) {
                return true;
            }
            digits.setLength(0);
            groupStarts.clear();
            i++;
        }
        return runHoldsCard(digits, groupStarts, requireCardNetworkPrefix);
    }

    private static boolean runHoldsCard(CharSequence run, List<Integer> groupStarts, boolean requirePrefix) {
        int n = run.length();
        if (n < MIN_DIGITS) {
            return false;
        }
        if (n <= MAX_DIGITS) {
            // Stretches of whole digit groups: start where a group starts, end where a group (or the run) ends.
            for (int start : groupStarts) {
                for (int end : groupStarts) {
                    if (end > start && isCard(run, start, end, requirePrefix)) {
                        return true;
                    }
                }
                if (isCard(run, start, n, requirePrefix)) {
                    return true;
                }
            }
            return false;
        }
        if (requirePrefix) {
            for (int start : groupStarts) {
                for (int end = start + MIN_DIGITS; end <= Math.min(n, start + MAX_DIGITS); end++) {
                    if (isCard(run, start, end, true)) {
                        return true;
                    }
                }
            }
            return false;
        }
        // Longer than any card number: a card may be hidden anywhere inside it.
        for (int start = 0; start + MIN_DIGITS <= n; start++) {
            for (int end = start + MIN_DIGITS; end <= Math.min(n, start + MAX_DIGITS); end++) {
                if (isCard(run, start, end, false)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isCard(CharSequence run, int start, int end, boolean requirePrefix) {
        int size = end - start;
        if (size < MIN_DIGITS || size > MAX_DIGITS) {
            return false;
        }
        CharSequence window = run.subSequence(start, end);
        return passesLuhn(window) && (!requirePrefix || hasCardNetworkPrefix(window));
    }

    /**
     * Issuer prefixes of the card networks: Visa 4, Mastercard 51-55 and 2221-2720, American Express 34 and 37,
     * Discover 6011, 644-649 and 65, JCB 3528-3589, UnionPay 62.
     */
    static boolean hasCardNetworkPrefix(CharSequence digits) {
        if (digits.length() < 4) {
            return false;
        }
        int two = Integer.parseInt(digits.subSequence(0, 2).toString());
        int three = Integer.parseInt(digits.subSequence(0, 3).toString());
        int four = Integer.parseInt(digits.subSequence(0, 4).toString());
        return digits.charAt(0) == '4'
                || (two >= 51 && two <= 55)
                || (four >= 2221 && four <= 2720)
                || two == 34 || two == 37
                || four == 6011 || (three >= 644 && three <= 649) || two == 65
                || (four >= 3528 && four <= 3589)
                || two == 62;
    }

    static boolean passesLuhn(CharSequence digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isSeparator(char c) {
        return c == ' ' || c == '-' || c == '.' || c == '/';
    }
}
