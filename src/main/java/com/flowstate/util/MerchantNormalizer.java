package com.flowstate.util;

import java.util.List;
import java.util.Locale;

/**
 * Normalizes raw merchant strings (as they'd appear on a bank statement,
 * e.g. "NETFLIX.COM 866-579-7172", "Netflix*8827") down to a comparable
 * key, stripping the noise that would otherwise defeat exact-match
 * clustering: case, punctuation, trailing reference numbers, common
 * payment-processor prefixes, and generic corporate/domain suffixes.
 */
public final class MerchantNormalizer {

    private static final List<String> TRAILING_SUFFIXES = List.of("com", "inc", "llc", "ltd", "corp", "co", "www");

    private MerchantNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toLowerCase(Locale.ROOT).trim();
        // strip common POS/processor prefixes
        s = s.replaceAll("^(sq \\*|tst\\*|pos |pp\\*|paypal \\*)", "");
        // strip trailing reference numbers / store numbers / phone numbers
        s = s.replaceAll("[#*]?\\d{3,}.*$", "");
        // strip non-alphanumeric
        s = s.replaceAll("[^a-z0-9 ]", " ");
        s = s.replaceAll("\\s+", " ").trim();

        // strip generic trailing corporate/domain tokens ("netflix com" -> "netflix"),
        // but never down to nothing (e.g. a merchant literally just named "Co").
        String[] words = s.split(" ");
        int end = words.length;
        while (end > 1 && TRAILING_SUFFIXES.contains(words[end - 1])) {
            end--;
        }
        return String.join(" ", java.util.Arrays.copyOfRange(words, 0, end));
    }
}
