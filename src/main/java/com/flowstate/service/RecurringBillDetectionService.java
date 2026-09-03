package com.flowstate.service;

import com.flowstate.domain.Category;
import com.flowstate.domain.RecurringBill;
import com.flowstate.domain.Transaction;
import com.flowstate.domain.TransactionType;
import com.flowstate.domain.User;
import com.flowstate.util.StringSimilarity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects recurring bills (rent, subscriptions, utilities, ...) from raw
 * expense transactions with no external labels — the foundation the
 * liquidity/recommendation engine builds on.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><b>Category filter.</b> Only expense transactions whose category is
 *       {@link com.flowstate.domain.Category#isBillEligible()} — rent,
 *       utilities, insurance, loan payments, subscriptions — are even
 *       considered. This is a deliberate domain-knowledge signal, not
 *       redundant with the statistics below: some spending is genuinely
 *       regular in both amount and timing (a routine commute's gas
 *       fill-ups, a standing weekly grocery run) without being a bill in
 *       any meaningful sense, and no amount/interval-only formula can tell
 *       those apart from a real bill purely from the numbers — see the
 *       "Why category, not just statistics" section below for the real
 *       case that proved this.</li>
 *   <li><b>Clustering.</b> Group expense transactions by normalized
 *       merchant name (see {@link com.flowstate.util.MerchantNormalizer}),
 *       then greedily merge clusters whose keys are fuzzy-similar
 *       (Levenshtein similarity &ge; {@link #MERCHANT_FUZZY_THRESHOLD}) so
 *       that statement-string drift ("NETFLIX.COM" vs "Netflix") doesn't
 *       split one real bill into two clusters.</li>
 *   <li><b>Scoring.</b> Each cluster with at least {@link #MIN_OCCURRENCES}
 *       transactions is scored on three signals, each normalized to
 *       [0, 1]:
 *       <ul>
 *         <li><i>Amount consistency</i> — {@code 1 - (stddev / mean)} of the
 *             transaction amounts. A bill that's always ~₹1499 scores near 1;
 *             one that swings wildly scores near 0.</li>
 *         <li><i>Interval regularity</i> — the same coefficient-of-variation
 *             formula applied to the day-gaps between consecutive
 *             occurrences. A bill that lands every ~30 days scores near 1.</li>
 *         <li><i>Merchant-name similarity</i> — average pairwise fuzzy
 *             similarity across the distinct normalized strings that were
 *             merged into the cluster (1.0 if they were already identical).</li>
 *       </ul>
 *       These combine as a weighted sum
 *       ({@code 0.45 * amount + 0.40 * interval + 0.15 * merchant}), multiplied
 *       by 100 to land on a 0-100 confidence score. Nothing here is a binary
 *       "is this a bill" classifier by design: every flagged cluster carries
 *       the number, and the UI shows it rather than hiding the uncertainty.</li>
 * </ol>
 *
 * <h2>Why a variance upper-confidence-bound, not a raw coefficient of variation</h2>
 * Amount consistency and interval regularity are never computed from the raw
 * sample standard deviation. A handful of transactions can *look* consistent
 * purely by chance — {@code RecurringBillDetectionBenchmarkTest} originally
 * caught this: a coffee shop visited on random days for random amounts
 * scored a misleadingly high confidence from a 5-transaction sample that
 * happened to have low variance. The fix isn't an arbitrary "needs N
 * occurrences" penalty on the final score — it's the same principle the
 * liquidity buffer already uses elsewhere in this app: never trust a point
 * estimate of variability, use an upper confidence bound on it. Each sample's
 * sum of squared deviations is a chi-squared-distributed quantity with known
 * degrees of freedom (occurrences-1 for amounts, gaps-1 for intervals), so a
 * one-sided 90% upper confidence bound on the true variance is
 * {@code sumSquaredDeviations / chiSquaredLowerQuantile(0.10, df)} — the
 * lower-tail chi-squared quantile shrinks faster than df does as df gets
 * small, so a 3-occurrence sample gets a properly inflated (more cautious)
 * variance estimate, while an 11-occurrence sample's bound sits close to its
 * plain sample variance. This degrades gracefully with history instead of
 * needing a hand-picked "occurrences / 12" cutoff, and — importantly — it
 * inflates the *estimate of uncertainty*, not the score itself, so a
 * genuinely perfect small sample (three identical charges on the same day of
 * three consecutive months) still scores high, while a merely so-so small
 * sample doesn't get to borrow confidence it hasn't earned. See
 * {@code docs/BENCHMARKS.md} for the real external dataset that motivated
 * this over the simpler linear penalty it replaced.
 *
 * <h2>Why category, not just statistics</h2>
 * {@code RealWorldTransactionBenchmarkTest} — see the "History" section of
 * that class and docs/BENCHMARKS.md §2 — surfaced a case the variance bound
 * above cannot fix by construction: gas fill-ups on a routine commute in
 * the real dataset varied by only 7% in amount and landed every ~14 days
 * &plusmn;18%, statistically indistinguishable from a subscription. No
 * tightening of the amount/interval formula can separate "this really is
 * regular" from "this is a bill" using only those two signals, because gas
 * fill-ups genuinely were regular. The category filter above is what
 * actually resolves it — Gas &amp; Fuel isn't bill-eligible regardless of
 * how consistent it looks — and it generalizes rather than special-cases
 * this one dataset: any TRANSPORT/GROCERIES/DINING_OUT/etc. cluster, real
 * or synthetic, is excluded the same way, on the same principle (a bill is
 * a category of obligation, not just a statistical pattern).
 *
 * Precision/recall of this scoring against a labeled synthetic dataset, and
 * against a real external dataset, are measured in
 * {@code RecurringBillDetectionBenchmarkTest} and
 * {@code RealWorldTransactionBenchmarkTest} — see docs/BENCHMARKS.md for the
 * actual numbers from the last run.
 */
@Service
public class RecurringBillDetectionService {

    public static final int MIN_OCCURRENCES = 3;
    public static final double MERCHANT_FUZZY_THRESHOLD = 0.82;
    public static final double DISPLAY_CONFIDENCE_THRESHOLD = 50.0;

    /** One-sided confidence level for the variance upper-confidence-bound (see class Javadoc). */
    private static final double VARIANCE_UCB_ALPHA = 0.10;
    /** Standard normal 10th-percentile quantile, i.e. z such that Phi(z) = VARIANCE_UCB_ALPHA. */
    private static final double Z_ALPHA_10 = -1.2816;

    public List<RecurringBill> detect(User user, List<Transaction> transactions) {
        List<Transaction> expenses = transactions.stream()
                .filter(t -> t.getType() == TransactionType.EXPENSE)
                .filter(t -> t.getCategory().isBillEligible())
                .toList();

        Map<String, List<Transaction>> exactGroups = expenses.stream()
                .collect(Collectors.groupingBy(Transaction::getMerchantNormalized));

        Map<String, String> parent = new HashMap<>();
        for (String key : exactGroups.keySet()) {
            parent.put(key, key);
        }
        List<String> keys = new ArrayList<>(exactGroups.keySet());
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                if (StringSimilarity.similarity(keys.get(i), keys.get(j)) >= MERCHANT_FUZZY_THRESHOLD) {
                    union(parent, keys.get(i), keys.get(j));
                }
            }
        }

        Map<String, List<Transaction>> clusters = new HashMap<>();
        Map<String, Set<String>> clusterKeys = new HashMap<>();
        for (String key : keys) {
            String root = find(parent, key);
            clusters.computeIfAbsent(root, k -> new ArrayList<>()).addAll(exactGroups.get(key));
            clusterKeys.computeIfAbsent(root, k -> new HashSet<>()).add(key);
        }

        List<RecurringBill> results = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> entry : clusters.entrySet()) {
            List<Transaction> txns = entry.getValue();
            if (txns.size() < MIN_OCCURRENCES) {
                continue;
            }
            txns.sort(Comparator.comparing(Transaction::getDate));
            RecurringBill bill = score(user, txns, clusterKeys.get(entry.getKey()));
            results.add(bill);
        }

        results.sort(Comparator.comparingDouble(RecurringBill::getConfidenceScore).reversed());
        return results;
    }

    private RecurringBill score(User user, List<Transaction> txns, Set<String> mergedKeys) {
        double[] amounts = txns.stream().mapToDouble(t -> t.getAmount().doubleValue()).toArray();
        double meanAmount = mean(amounts);
        double stdAmount = stdDev(amounts, meanAmount);
        double amountUcbStdDev = varianceUpperBoundStdDev(amounts, meanAmount, amounts.length - 1);
        double amountConsistency = meanAmount <= 0 ? 0.0 : clamp01(1 - (amountUcbStdDev / meanAmount));

        double[] intervals = new double[txns.size() - 1];
        for (int i = 1; i < txns.size(); i++) {
            intervals[i - 1] = ChronoUnit.DAYS.between(txns.get(i - 1).getDate(), txns.get(i).getDate());
        }
        double meanInterval = mean(intervals);
        double stdInterval = stdDev(intervals, meanInterval);
        double intervalUcbStdDev = varianceUpperBoundStdDev(intervals, meanInterval, intervals.length - 1);
        double intervalRegularity = meanInterval <= 0 ? 0.0 : clamp01(1 - (intervalUcbStdDev / meanInterval));

        double merchantSimilarity = averagePairwiseSimilarity(mergedKeys);

        double confidence = 100.0 * (0.45 * amountConsistency + 0.40 * intervalRegularity + 0.15 * merchantSimilarity);
        confidence = Math.max(0.0, Math.min(100.0, confidence));

        RecurringBill bill = new RecurringBill();
        bill.setUser(user);
        bill.setMerchantDisplayName(mostCommonDisplayName(txns));
        bill.setMerchantNormalized(txns.get(0).getMerchantNormalized());
        bill.setAverageAmount(BigDecimal.valueOf(meanAmount).setScale(2, RoundingMode.HALF_UP));
        bill.setAmountStdDev(BigDecimal.valueOf(stdAmount).setScale(2, RoundingMode.HALF_UP));
        bill.setAverageIntervalDays(meanInterval);
        bill.setIntervalStdDevDays(stdInterval);
        bill.setConfidenceScore(Math.round(confidence * 10.0) / 10.0);
        bill.setOccurrenceCount(txns.size());
        bill.setFirstSeenDate(txns.get(0).getDate());
        LocalDate lastSeen = txns.get(txns.size() - 1).getDate();
        bill.setLastSeenDate(lastSeen);
        long predictedGap = Math.round(meanInterval > 0 ? meanInterval : 30);
        bill.setPredictedNextDate(lastSeen.plusDays(predictedGap));
        bill.setCategory(mostCommonCategory(txns));
        return bill;
    }

    private double averagePairwiseSimilarity(Set<String> keys) {
        if (keys.size() <= 1) {
            return 1.0;
        }
        List<String> list = new ArrayList<>(keys);
        double total = 0;
        int count = 0;
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                total += StringSimilarity.similarity(list.get(i), list.get(j));
                count++;
            }
        }
        return count == 0 ? 1.0 : total / count;
    }

    private String mostCommonDisplayName(List<Transaction> txns) {
        return txns.stream()
                .collect(Collectors.groupingBy(Transaction::getMerchant, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(txns.get(0).getMerchant());
    }

    private Category mostCommonCategory(List<Transaction> txns) {
        return txns.stream()
                .collect(Collectors.groupingBy(Transaction::getCategory, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(Category.OTHER_EXPENSE);
    }

    private static double mean(double[] values) {
        if (values.length == 0) return 0;
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.length;
    }

    private static double stdDev(double[] values, double mean) {
        if (values.length == 0) return 0;
        double sumSq = 0;
        for (double v : values) sumSq += (v - mean) * (v - mean);
        return Math.sqrt(sumSq / values.length);
    }

    /**
     * A one-sided {@code 1 - VARIANCE_UCB_ALPHA} confidence bound on the true standard
     * deviation behind {@code values}, given {@code degreesOfFreedom} (occurrences-1 for
     * amounts, gaps-1 for intervals). See the class Javadoc for why this replaces a plain
     * sample standard deviation in the consistency formulas.
     */
    private static double varianceUpperBoundStdDev(double[] values, double mean, int degreesOfFreedom) {
        if (degreesOfFreedom < 1) {
            return Double.POSITIVE_INFINITY;
        }
        double sumSq = 0;
        for (double v : values) sumSq += (v - mean) * (v - mean);
        double varianceUpperBound = sumSq / chiSquaredLowerQuantile(degreesOfFreedom);
        return Math.sqrt(varianceUpperBound);
    }

    /**
     * Wilson-Hilferty approximation of the chi-squared distribution's {@link #VARIANCE_UCB_ALPHA}
     * lower-tail quantile: the value x such that P(X &le; x) = {@code VARIANCE_UCB_ALPHA} for
     * X ~ chi-squared(df). Accurate to within a few percent for df &ge; 4; for smaller df it
     * under-estimates the true quantile, which makes the resulting variance bound *more*
     * conservative than a true 90% bound — the safe direction for a confidence score.
     */
    private static double chiSquaredLowerQuantile(int df) {
        double h = 2.0 / (9.0 * df);
        double term = Math.max(0.01, 1 - h + Z_ALPHA_10 * Math.sqrt(h));
        return df * term * term * term;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String find(Map<String, String> parent, String key) {
        String root = key;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        String cur = key;
        while (!parent.get(cur).equals(root)) {
            String next = parent.get(cur);
            parent.put(cur, root);
            cur = next;
        }
        return root;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String rootA = find(parent, a);
        String rootB = find(parent, b);
        if (!rootA.equals(rootB)) {
            parent.put(rootA, rootB);
        }
    }
}
