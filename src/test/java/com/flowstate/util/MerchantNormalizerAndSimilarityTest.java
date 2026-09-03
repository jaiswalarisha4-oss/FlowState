package com.flowstate.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantNormalizerAndSimilarityTest {

    @Test
    void normalizeStripsPunctuationCaseAndTrailingReferenceNumbers() {
        assertEquals("netflix", MerchantNormalizer.normalize("Netflix"));
        assertEquals("netflix", MerchantNormalizer.normalize("NETFLIX.COM 866-579-7172"));
        assertEquals("netflix", MerchantNormalizer.normalize("Netflix*8827"));
    }

    @Test
    void similarityOfIdenticalStringsIsOne() {
        assertEquals(1.0, StringSimilarity.similarity("netflix", "netflix"));
    }

    @Test
    void similarityOfCloseVariantsClearsFuzzyThreshold() {
        double sim = StringSimilarity.similarity(
                MerchantNormalizer.normalize("NETFLIX.COM 866-579-7172"),
                MerchantNormalizer.normalize("Netflix"));
        assertTrue(sim >= RecurringBillThresholdRef.MERCHANT_FUZZY_THRESHOLD,
                "Expected normalized Netflix variants to clear the fuzzy-match threshold, got " + sim);
    }

    @Test
    void similarityOfUnrelatedMerchantsIsLow() {
        double sim = StringSimilarity.similarity("netflix", "city power  electric");
        assertTrue(sim < 0.5, "Expected unrelated merchants to score low, got " + sim);
    }

    /** Avoids a hard dependency from this util test onto the service package. */
    private static final class RecurringBillThresholdRef {
        static final double MERCHANT_FUZZY_THRESHOLD = 0.82;
    }
}
