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
 *       ({@code 0.45 * amount + 0.40 * interval + 0.15 * merchant}),
 *       then get scaled down by a sample-size factor
 *       ({@code min(1, occurrences / 12)}) so a bill seen only 3-5 times can't
 *       claim the same confidence as one with a full year of history even if
 *       it looks perfectly regular so far — small samples can look regular
 *       by chance — and multiplied by 100 to land on a
 *       0-100 confidence score. Nothing here is a binary "is this a bill"
 *       classifier by design: every flagged cluster carries the number, and
 *       the UI shows it rather than hiding the uncertainty.</li>
 * </ol>
 *
 * Precision/recall of this scoring against a labeled synthetic dataset is
 * measured in {@code RecurringBillDetectionBenchmarkTest} — see
 * docs/BENCHMARKS.md for the actual numbers from the last run.
 */
@Service
public class RecurringBillDetectionService {

    public static final int MIN_OCCURRENCES = 3;
    public static final double MERCHANT_FUZZY_THRESHOLD = 0.82;
    public static final double DISPLAY_CONFIDENCE_THRESHOLD = 35.0;

    /**
     * Occurrence count at which the sample-size factor saturates at 1.0 — i.e. a bill needs
     * roughly a full year of monthly history before amount/interval consistency alone can earn
     * it full confidence. Below this, two merchants with statistically similar-looking
     * consistency can still be told apart by how much evidence backs the number: a handful of
     * occurrences that *happen* to look regular (a real risk with small samples — see
     * {@code RecurringBillDetectionBenchmarkTest}) score lower than the same consistency backed
     * by a year of history.
     */
    private static final double FULL_CONFIDENCE_OCCURRENCE_COUNT = 12.0;

    public List<RecurringBill> detect(User user, List<Transaction> transactions) {
        List<Transaction> expenses = transactions.stream()
                .filter(t -> t.getType() == TransactionType.EXPENSE)
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
        double amountConsistency = meanAmount <= 0 ? 0.0 : clamp01(1 - (stdAmount / meanAmount));

        double[] intervals = new double[txns.size() - 1];
        for (int i = 1; i < txns.size(); i++) {
            intervals[i - 1] = ChronoUnit.DAYS.between(txns.get(i - 1).getDate(), txns.get(i).getDate());
        }
        double meanInterval = mean(intervals);
        double stdInterval = stdDev(intervals, meanInterval);
        double intervalRegularity = meanInterval <= 0 ? 0.0 : clamp01(1 - (stdInterval / meanInterval));

        double merchantSimilarity = averagePairwiseSimilarity(mergedKeys);

        double sampleSizeFactor = Math.min(1.0, txns.size() / FULL_CONFIDENCE_OCCURRENCE_COUNT);
        double confidence = 100.0 * sampleSizeFactor
                * (0.45 * amountConsistency + 0.40 * intervalRegularity + 0.15 * merchantSimilarity);
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
