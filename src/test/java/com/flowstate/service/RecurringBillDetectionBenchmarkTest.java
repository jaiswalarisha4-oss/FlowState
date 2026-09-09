package com.flowstate.service;

import com.flowstate.domain.*;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates {@link RecurringBillDetectionService} against a hand-labeled
 * synthetic dataset: transactions are generated with a KNOWN ground truth
 * (which merchant clusters are genuinely recurring bills vs. which are
 * irregular/one-off noise), and the detector's output is scored against
 * that label with standard precision/recall/F1 — the same metric the
 * original project brief's "shock simulator backtest" concept calls for,
 * scaled down to something a solo student build can run in milliseconds
 * and reason about with an exact number, not a vague claim.
 *
 * Results from the last run of this test are copied into
 * docs/BENCHMARKS.md — see that file for the actual measured numbers.
 */
class RecurringBillDetectionBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(RecurringBillDetectionBenchmarkTest.class);

    private final RecurringBillDetectionService service = new RecurringBillDetectionService();

    @Test
    void detectorAchievesHighPrecisionAndRecallOnLabeledSyntheticDataset() {
        User user = new User("bench@flowstate.app", "Bench User", "x");
        Account account = new Account(user, "Checking", AccountType.CHECKING, BigDecimal.ZERO);
        Random random = new Random(7);

        Set<String> groundTruthBillMerchants = new HashSet<>();
        List<Transaction> transactions = new ArrayList<>();

        // ---- 6 genuinely recurring bills: regular amount + regular interval ----
        record BillDef(String merchant, double amount, double amountJitter, int intervalDays, int intervalJitter) {
        }
        List<BillDef> realBills = List.of(
                new BillDef("Netflix", 199, 0, 30, 1),
                new BillDef("Rent - Lakeview Apartments", 18000, 0, 30, 0),
                new BillDef("Spotify", 119, 0, 30, 1),
                new BillDef("FitLife Gym", 1200, 0, 30, 2),
                new BillDef("Airtel Postpaid", 499, 0.03, 30, 2),
                new BillDef("City Power & Electric", 1400, 0.30, 30, 3)
        );
        for (BillDef def : realBills) {
            groundTruthBillMerchants.add(def.merchant());
            LocalDate date = LocalDate.now().minusMonths(11);
            for (int i = 0; i < 11; i++) {
                double amount = def.amount() * (1 + (random.nextDouble() - 0.5) * 2 * def.amountJitter());
                transactions.add(new Transaction(account, date, bd(amount), TransactionType.EXPENSE,
                        Category.SUBSCRIPTIONS, def.merchant()));
                int jitter = def.intervalJitter() == 0 ? 0 : random.nextInt(2 * def.intervalJitter() + 1) - def.intervalJitter();
                date = date.plusDays(def.intervalDays() + jitter);
            }
        }

        // ---- 4 non-bill merchants that recur but with NO real pattern: occurrence dates are
        // picked independently at random across the whole window (so gaps are exponential-ish,
        // not roughly constant) and amounts are bimodal (mostly small, occasionally a big
        // impulse buy) rather than clustered around one value — genuinely irregular spending,
        // as opposed to a bill that happens to have some month-to-month amount drift. ----
        List<String> noiseMerchants = List.of("Cafe Coffee Beans", "Urban Bowl Restaurant", "GameZone Arcade", "StyleHub");
        for (String merchant : noiseMerchants) {
            int occurrences = 4 + random.nextInt(4);
            for (int i = 0; i < occurrences; i++) {
                LocalDate date = LocalDate.now().minusDays(random.nextInt(330));
                double amount = random.nextDouble() < 0.7 ? 150 + random.nextInt(350) : 3000 + random.nextInt(5000);
                transactions.add(new Transaction(account, date, bd(amount), TransactionType.EXPENSE,
                        Category.DINING_OUT, merchant));
            }
        }

        // ---- pure one-off noise: distinct, unrelated merchant names (never repeats -> filtered
        // by MIN_OCCURRENCES before scoring; using genuinely different names also avoids
        // accidentally fuzzy-matching into each other, unlike e.g. "Store1".."Store15") ----
        List<String> oneOffMerchants = List.of("BlueLeaf Electronics", "Riverside Hardware", "Momo House",
                "QuickPrint Shop", "Greentrail Outdoors", "Zenith Opticians", "Marble Bath Co",
                "Cedar Furniture Co", "Solstice Books", "Ember Grill", "Nimbus Tech", "Wanderlust Travel",
                "Pearl Jewelers", "Foundry Coffee Roasters", "Halcyon Spa");
        for (String merchant : oneOffMerchants) {
            transactions.add(new Transaction(account, LocalDate.now().minusDays(random.nextInt(300)),
                    bd(100 + random.nextInt(3000)), TransactionType.EXPENSE, Category.SHOPPING, merchant));
        }

        List<RecurringBill> detected = service.detect(user, transactions);
        Set<String> flagged = new HashSet<>();
        for (RecurringBill b : detected) {
            if (b.getConfidenceScore() >= RecurringBillDetectionService.DISPLAY_CONFIDENCE_THRESHOLD) {
                flagged.add(b.getMerchantDisplayName());
            }
        }

        long truePositives = flagged.stream().filter(groundTruthBillMerchants::contains).count();
        long falsePositives = flagged.stream().filter(m -> !groundTruthBillMerchants.contains(m)).count();
        long falseNegatives = groundTruthBillMerchants.stream().filter(m -> !flagged.contains(m)).count();

        double precision = truePositives / (double) Math.max(1, truePositives + falsePositives);
        double recall = truePositives / (double) Math.max(1, truePositives + falseNegatives);
        double f1 = (precision + recall) == 0 ? 0 : 2 * precision * recall / (precision + recall);

        log.info("=== RecurringBillDetectionService benchmark ===");
        log.info("Ground truth bills: {}", groundTruthBillMerchants);
        log.info("Flagged (confidence >= {}): {}", RecurringBillDetectionService.DISPLAY_CONFIDENCE_THRESHOLD, flagged);
        log.info("TP={} FP={} FN={}", truePositives, falsePositives, falseNegatives);
        log.info("Precision={} Recall={} F1={}", round(precision), round(recall), round(f1));
        for (RecurringBill b : detected) {
            double amountCons = b.getAverageAmount().doubleValue() <= 0 ? 0
                    : 1 - b.getAmountStdDev().doubleValue() / b.getAverageAmount().doubleValue();
            double intervalReg = b.getAverageIntervalDays() <= 0 ? 0
                    : 1 - b.getIntervalStdDevDays() / b.getAverageIntervalDays();
            log.info("  {} -> confidence={} occurrences={} amountConsistency={} intervalRegularity={}",
                    b.getMerchantDisplayName(), round(b.getConfidenceScore()), b.getOccurrenceCount(),
                    round(amountCons), round(intervalReg));
        }

        assertTrue(precision >= 0.9, "Precision should be >= 0.90, was " + precision);
        assertTrue(recall >= 0.9, "Recall should be >= 0.90, was " + recall);
    }

    private BigDecimal bd(double v) {
        return BigDecimal.valueOf(Math.max(1, v)).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
