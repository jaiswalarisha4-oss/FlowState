package com.flowstate.service;

import com.flowstate.domain.*;
import com.flowstate.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Produces every user-facing recommendation FlowState makes, each one
 * carrying a full decision trace: the transactions behind it, a confidence
 * interval where the underlying quantity is uncertain, the external
 * benchmark it was checked against, and a plain-language rationale. Every
 * call is written to {@link AuditLog} with the {@link #MODEL_VERSION} that
 * produced it — a minimal, student-project-scale stand-in for the kind of
 * model-versioning/disclosure trail real financial-advice regulation
 * increasingly expects (see README "Governance & Explainability").
 *
 * <h2>1. Safe-to-invest surplus</h2>
 * {@code required_buffer} is estimated as an <b>upper confidence bound</b>,
 * not a point estimate: sum the average amount of every recurring bill due
 * in the next 30 days, then add {@code 1.645 * combined_stddev} (the 90%
 * one-sided z-bound, combining each bill's variance under an independence
 * assumption), then scale by the user's {@link RiskTolerance} multiplier.
 * {@code surplus = current_balance - required_buffer}. This deliberately
 * never recommends investing the optimistic average case — it recommends
 * investing what would still be safe if the bills land on the expensive,
 * irregular side of what's been observed.
 *
 * <h2>2. Budget checks vs. the 50/30/20 rule</h2>
 * Trailing-quarter spending is rolled up into needs / wants / savings and
 * compared against the 50/30/20 split (Elizabeth Warren, "All Your
 * Worth", 2005) with a {@link #BUDGET_TOLERANCE_PCT} tolerance band.
 *
 * <h2>3. Emergency fund vs. the 3-6 month guideline</h2>
 * Savings-account balance is compared against 3x trailing monthly
 * essential ("needs") spend — the standard CFPB / personal-finance-101
 * emergency-fund guideline.
 *
 * <h2>4. Debt-to-income vs. the 36% guideline</h2>
 * Monthly debt-payment spend as a fraction of monthly income, compared
 * against the 36% back-end DTI ceiling used in conventional mortgage
 * underwriting.
 *
 * These four external, independently-documented rules are the "proven
 * benchmarks" this engine's output is validated against — see
 * {@code RecommendationEngineBenchmarkTest} for the executable checks and
 * docs/BENCHMARKS.md for the results of the last run.
 */
@Service
public class RecommendationEngine {

    public static final String MODEL_VERSION = "flowstate-rules-v1";
    public static final String BENCHMARK_50_30_20 = "50/30/20 Budgeting Rule (Elizabeth Warren, \"All Your Worth\", 2005)";
    public static final String BENCHMARK_EMERGENCY_FUND = "3-6 Month Emergency Fund Guideline (CFPB / standard personal-finance practice)";
    public static final String BENCHMARK_DTI = "36% Debt-to-Income Guideline (conventional mortgage-underwriting standard)";

    private static final double Z_90_ONE_SIDED = 1.645;
    private static final double BUDGET_TOLERANCE_PCT = 5.0;
    private static final double DTI_CEILING_PCT = 36.0;
    private static final int TRAILING_DAYS = 90;

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final RecurringBillRepository recurringBillRepository;
    private final RecommendationRepository recommendationRepository;
    private final AuditLogRepository auditLogRepository;
    private final RecurringBillDetectionService detectionService;

    public RecommendationEngine(AccountRepository accountRepository,
                                 TransactionRepository transactionRepository,
                                 RecurringBillRepository recurringBillRepository,
                                 RecommendationRepository recommendationRepository,
                                 AuditLogRepository auditLogRepository,
                                 RecurringBillDetectionService detectionService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.recurringBillRepository = recurringBillRepository;
        this.recommendationRepository = recommendationRepository;
        this.auditLogRepository = auditLogRepository;
        this.detectionService = detectionService;
    }

    @Transactional
    public List<Recommendation> generateRecommendations(User user) {
        List<Account> accounts = accountRepository.findByUser(user);
        List<Transaction> allTransactions = transactionRepository.findByAccountInOrderByDateDesc(accounts);

        List<RecurringBill> bills = detectionService.detect(user, allTransactions);
        recurringBillRepository.deleteByUser(user);
        recurringBillRepository.saveAll(bills);

        LocalDate today = LocalDate.now();
        LocalDate windowStart = today.minusDays(TRAILING_DAYS);
        List<Transaction> trailing = allTransactions.stream()
                .filter(t -> !t.getDate().isBefore(windowStart))
                .toList();

        List<Recommendation> results = new ArrayList<>();
        results.add(buildSafeToInvest(user, accounts, bills));
        results.addAll(buildBudgetChecks(user, trailing));
        results.add(buildEmergencyFund(user, accounts, trailing));
        results.add(buildDebtToIncome(user, trailing));

        for (Recommendation r : results) {
            recommendationRepository.save(r);
            auditLogRepository.save(new AuditLog(user.getId(), r.getId(), "RECOMMENDATION_GENERATED",
                    MODEL_VERSION, r.getType() + ": " + r.getTitle()));
        }
        return results;
    }

    // ---- 1. Safe to invest -------------------------------------------------

    private Recommendation buildSafeToInvest(User user, List<Account> accounts, List<RecurringBill> bills) {
        BigDecimal balance = accounts.stream()
                .filter(a -> a.getType() != AccountType.CREDIT_CARD)
                .map(Account::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDate horizon = LocalDate.now().plusDays(30);
        List<RecurringBill> nearTerm = bills.stream()
                .filter(b -> !b.getPredictedNextDate().isAfter(horizon))
                .filter(b -> b.getConfidenceScore() >= RecurringBillDetectionService.DISPLAY_CONFIDENCE_THRESHOLD)
                .toList();

        double basePoint = nearTerm.stream().mapToDouble(b -> b.getAverageAmount().doubleValue()).sum();
        double combinedVariance = nearTerm.stream()
                .mapToDouble(b -> Math.pow(b.getAmountStdDev().doubleValue(), 2))
                .sum();
        double combinedStdDev = Math.sqrt(combinedVariance);
        double upperBoundBuffer = basePoint + Z_90_ONE_SIDED * combinedStdDev;

        double multiplier = user.getRiskTolerance().getBufferMultiplier();
        double appliedUpperBuffer = upperBoundBuffer * multiplier;
        double appliedPointBuffer = basePoint * multiplier;

        double surplusConservative = balance.doubleValue() - appliedUpperBuffer;
        double surplusOptimistic = balance.doubleValue() - appliedPointBuffer;

        String rationale = String.format(Locale.US,
                "Current balance is %.2f. %d recurring bill(s) totaling an average of %.2f are due in the " +
                "next 30 days (%s). Adding a 90%% one-sided safety margin (+%.2f for amount/timing variance) " +
                "and your %s risk-tolerance multiplier (x%.2f) gives a required buffer of %.2f. " +
                "Safe-to-invest = balance - buffer = %.2f. The range shown reflects buffer computed from the " +
                "average bill amount (optimistic bound) vs. the variance-adjusted upper bound (conservative bound).",
                balance.doubleValue(), nearTerm.size(), basePoint,
                nearTerm.stream().map(RecurringBill::getMerchantDisplayName).collect(Collectors.joining(", ")),
                Z_90_ONE_SIDED * combinedStdDev, user.getRiskTolerance().name(), multiplier,
                appliedUpperBuffer, surplusConservative);

        Recommendation rec = new Recommendation();
        rec.setUser(user);
        rec.setType(RecommendationType.SAFE_TO_INVEST);
        rec.setTitle("Safe to invest today");
        rec.setAmount(money(surplusConservative));
        rec.setConfidenceLow(money(surplusConservative));
        rec.setConfidenceHigh(money(surplusOptimistic));
        rec.setRationale(rationale);
        rec.setBenchmarkReference("Upper-confidence-bound liquidity buffer (internal forecasting model, not a third-party rule)");
        rec.setModelVersion(MODEL_VERSION);
        return rec;
    }

    // ---- 2. Budget vs 50/30/20 ---------------------------------------------

    private List<Recommendation> buildBudgetChecks(User user, List<Transaction> trailing) {
        double monthlyIncome = monthlySum(trailing, t -> t.getType() == TransactionType.INCOME);
        if (monthlyIncome <= 0) {
            return List.of();
        }
        Map<CategoryGroup, Double> monthlyByGroup = new EnumMap<>(CategoryGroup.class);
        for (CategoryGroup g : List.of(CategoryGroup.NEEDS, CategoryGroup.WANTS, CategoryGroup.SAVINGS_AND_DEBT)) {
            monthlyByGroup.put(g, monthlySum(trailing,
                    t -> t.getType() == TransactionType.EXPENSE && t.getCategory().getGroup() == g));
        }

        List<Recommendation> alerts = new ArrayList<>();
        alerts.add(budgetAlertFor(user, trailing, CategoryGroup.NEEDS, monthlyByGroup.get(CategoryGroup.NEEDS), monthlyIncome, 50.0));
        alerts.add(budgetAlertFor(user, trailing, CategoryGroup.WANTS, monthlyByGroup.get(CategoryGroup.WANTS), monthlyIncome, 30.0));
        alerts.add(budgetAlertFor(user, trailing, CategoryGroup.SAVINGS_AND_DEBT, monthlyByGroup.get(CategoryGroup.SAVINGS_AND_DEBT), monthlyIncome, 20.0));
        return alerts;
    }

    private Recommendation budgetAlertFor(User user, List<Transaction> trailing, CategoryGroup group,
                                           double monthlyAmount, double monthlyIncome, double targetPct) {
        double actualPct = 100.0 * monthlyAmount / monthlyIncome;
        double deviation = actualPct - targetPct;
        boolean overTarget = group != CategoryGroup.SAVINGS_AND_DEBT
                ? deviation > BUDGET_TOLERANCE_PCT
                : deviation < -BUDGET_TOLERANCE_PCT;

        List<Transaction> supporting = trailing.stream()
                .filter(t -> t.getType() == TransactionType.EXPENSE && t.getCategory().getGroup() == group)
                .sorted(Comparator.comparing(Transaction::getAmount).reversed())
                .limit(5)
                .toList();

        String status = overTarget
                ? (group == CategoryGroup.SAVINGS_AND_DEBT ? "below" : "above")
                : "within";
        String rationale = String.format(Locale.US,
                "%s spending is %.1f%% of trailing-quarter monthly income (target: %.0f%%, tolerance +/-%.0fpp). " +
                "This is %s the 50/30/20 target band. Largest contributing transactions: %s.",
                groupLabel(group), actualPct, targetPct, BUDGET_TOLERANCE_PCT, status,
                supporting.isEmpty() ? "none in this window"
                        : supporting.stream()
                        .map(t -> t.getMerchant() + " (" + t.getAmount() + ")")
                        .collect(Collectors.joining(", ")));

        Recommendation rec = new Recommendation();
        rec.setUser(user);
        rec.setType(RecommendationType.BUDGET_ALERT);
        rec.setTitle(groupLabel(group) + " vs. 50/30/20 target");
        rec.setAmount(money(monthlyAmount));
        rec.setRationale(rationale);
        rec.setBenchmarkReference(BENCHMARK_50_30_20);
        rec.setModelVersion(MODEL_VERSION);
        rec.setSupportingTransactionIds(supporting.stream().map(Transaction::getId).toList());
        return rec;
    }

    private String groupLabel(CategoryGroup g) {
        return switch (g) {
            case NEEDS -> "Needs";
            case WANTS -> "Wants";
            case SAVINGS_AND_DEBT -> "Savings & debt paydown";
            case INCOME -> "Income";
        };
    }

    // ---- 3. Emergency fund vs 3-6 months -----------------------------------

    private Recommendation buildEmergencyFund(User user, List<Account> accounts, List<Transaction> trailing) {
        double savingsBalance = accounts.stream()
                .filter(a -> a.getType() == AccountType.SAVINGS)
                .mapToDouble(a -> a.getBalance().doubleValue())
                .sum();
        double monthlyEssentials = monthlySum(trailing,
                t -> t.getType() == TransactionType.EXPENSE && t.getCategory().getGroup() == CategoryGroup.NEEDS);
        double threeMonths = monthlyEssentials * 3;
        double sixMonths = monthlyEssentials * 6;

        String status;
        if (savingsBalance >= sixMonths) {
            status = "at or above the 6-month upper guideline";
        } else if (savingsBalance >= threeMonths) {
            status = "within the recommended 3-6 month range";
        } else {
            status = "below the 3-month minimum";
        }

        String rationale = String.format(Locale.US,
                "Savings balance is %.2f. Trailing-quarter essential (needs) spend averages %.2f/month, so the " +
                "recommended emergency fund range is %.2f (3 months) to %.2f (6 months). Current savings are %s.",
                savingsBalance, monthlyEssentials, threeMonths, sixMonths, status);

        Recommendation rec = new Recommendation();
        rec.setUser(user);
        rec.setType(RecommendationType.EMERGENCY_FUND);
        rec.setTitle("Emergency fund vs. 3-6 month guideline");
        rec.setAmount(money(Math.max(0, threeMonths - savingsBalance)));
        rec.setConfidenceLow(money(threeMonths));
        rec.setConfidenceHigh(money(sixMonths));
        rec.setRationale(rationale);
        rec.setBenchmarkReference(BENCHMARK_EMERGENCY_FUND);
        rec.setModelVersion(MODEL_VERSION);
        return rec;
    }

    // ---- 4. Debt-to-income vs 36% ------------------------------------------

    private Recommendation buildDebtToIncome(User user, List<Transaction> trailing) {
        double monthlyIncome = monthlySum(trailing, t -> t.getType() == TransactionType.INCOME);
        double monthlyDebt = monthlySum(trailing,
                t -> t.getType() == TransactionType.EXPENSE && t.getCategory() == Category.LOAN_OR_DEBT_PAYMENT);
        double dtiPct = monthlyIncome <= 0 ? 0 : 100.0 * monthlyDebt / monthlyIncome;

        String rationale = String.format(Locale.US,
                "Monthly debt payments average %.2f against monthly income of %.2f, a debt-to-income ratio of " +
                "%.1f%%. The conventional mortgage-underwriting ceiling is %.0f%%; this is %s that ceiling.",
                monthlyDebt, monthlyIncome, dtiPct, DTI_CEILING_PCT, dtiPct > DTI_CEILING_PCT ? "above" : "within");

        Recommendation rec = new Recommendation();
        rec.setUser(user);
        rec.setType(RecommendationType.DEBT_RATIO);
        rec.setTitle("Debt-to-income vs. 36% guideline");
        rec.setAmount(money(dtiPct));
        rec.setRationale(rationale);
        rec.setBenchmarkReference(BENCHMARK_DTI);
        rec.setModelVersion(MODEL_VERSION);
        return rec;
    }

    // ---- helpers ------------------------------------------------------------

    private double monthlySum(List<Transaction> trailing, java.util.function.Predicate<Transaction> filter) {
        double total = trailing.stream().filter(filter).mapToDouble(t -> t.getAmount().doubleValue()).sum();
        return total / (TRAILING_DAYS / 30.0);
    }

    private BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
