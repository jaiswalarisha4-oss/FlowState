package com.flowstate.service;

import com.flowstate.domain.*;
import com.flowstate.repository.AccountRepository;
import com.flowstate.repository.TransactionRepository;
import com.flowstate.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates {@code RecommendationEngine}'s output against the external,
 * independently-documented rules it's meant to check spending against:
 * the 50/30/20 budgeting rule, the 3-6 month emergency-fund guideline,
 * and the 36% debt-to-income ceiling. Each test constructs a transaction
 * history whose totals hit an exact, hand-computed percentage against the
 * benchmark and asserts the engine reproduces that number — this is what
 * "tested against a proven external benchmark" means concretely for a
 * rules-based recommendation engine (as opposed to a black-box ML model,
 * where the benchmark would instead be a labeled backtest — see
 * {@code RecurringBillDetectionBenchmarkTest} for that flavor).
 *
 * A fifth test ({@link #shockScenario}) is a miniature version of the
 * original project brief's "financial shock simulator": it compares a
 * stable recurring-bill history against a volatile one and checks that
 * the safe-to-invest confidence interval widens and the recommended
 * amount drops accordingly — i.e. the engine gets more conservative, not
 * less, when the data underneath it gets noisier.
 */
@SpringBootTest
@Transactional
class RecommendationEngineBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(RecommendationEngineBenchmarkTest.class);
    private static final double DELTA = 0.01;

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private RecommendationEngine recommendationEngine;

    @Test
    void budgetCheck_exactlyOnTarget_isFlaggedWithinBand() {
        User user = newUser("exact-target@flowstate.app");
        Account checking = newAccount(user, AccountType.CHECKING, "50000.00");

        // monthlyIncome = total / 3 (trailing-90-day window)
        txn(checking, Category.SALARY, TransactionType.INCOME, "180000.00");   // -> 60000/mo
        txn(checking, Category.RENT_OR_MORTGAGE, TransactionType.EXPENSE, "90000.00"); // -> 30000/mo = 50%
        txn(checking, Category.DINING_OUT, TransactionType.EXPENSE, "54000.00");       // -> 18000/mo = 30%
        txn(checking, Category.SAVINGS_TRANSFER, TransactionType.EXPENSE, "36000.00"); // -> 12000/mo = 20%

        List<Recommendation> recs = recommendationEngine.generateRecommendations(user);

        Recommendation needs = findAlert(recs, "Needs");
        Recommendation wants = findAlert(recs, "Wants");
        Recommendation savings = findAlert(recs, "Savings");

        assertEquals(30000.0, needs.getAmount().doubleValue(), DELTA);
        assertEquals(18000.0, wants.getAmount().doubleValue(), DELTA);
        assertEquals(12000.0, savings.getAmount().doubleValue(), DELTA);
        assertTrue(needs.getRationale().contains("50.0%"), needs.getRationale());
        assertTrue(wants.getRationale().contains("30.0%"), wants.getRationale());
        assertTrue(savings.getRationale().contains("20.0%"), savings.getRationale());
        assertEquals(RecommendationEngine.BENCHMARK_50_30_20, needs.getBenchmarkReference());
        log.info("50/30/20 exact-target test: needs={} wants={} savings={}",
                needs.getAmount(), wants.getAmount(), savings.getAmount());
    }

    @Test
    void budgetCheck_overspendingOnWants_isFlaggedAboveTarget() {
        User user = newUser("overspend@flowstate.app");
        Account checking = newAccount(user, AccountType.CHECKING, "50000.00");

        txn(checking, Category.SALARY, TransactionType.INCOME, "180000.00");          // 60000/mo
        txn(checking, Category.ENTERTAINMENT, TransactionType.EXPENSE, "300000.00");  // 100000/mo = 166.7%

        List<Recommendation> recs = recommendationEngine.generateRecommendations(user);
        Recommendation wants = findAlert(recs, "Wants");

        assertTrue(wants.getRationale().contains("above"), wants.getRationale());
        assertEquals(166.7, extractPercent(wants.getRationale()), 0.2);
        log.info("Overspend test rationale: {}", wants.getRationale());
    }

    @Test
    void emergencyFund_belowThreeMonths_flagsShortfallAgainstGuideline() {
        User user = newUser("emergency@flowstate.app");
        Account checking = newAccount(user, AccountType.CHECKING, "10000.00");
        Account savings = newAccount(user, AccountType.SAVINGS, "50000.00");

        txn(checking, Category.RENT_OR_MORTGAGE, TransactionType.EXPENSE, "90000.00"); // needs = 30000/mo

        List<Recommendation> recs = recommendationEngine.generateRecommendations(user);
        Recommendation ef = findFirst(recs, RecommendationType.EMERGENCY_FUND);

        assertEquals(RecommendationEngine.BENCHMARK_EMERGENCY_FUND, ef.getBenchmarkReference());
        assertEquals(90000.0, ef.getConfidenceLow().doubleValue(), DELTA);  // 3 months
        assertEquals(180000.0, ef.getConfidenceHigh().doubleValue(), DELTA); // 6 months
        assertEquals(40000.0, ef.getAmount().doubleValue(), DELTA); // shortfall = 90000 - 50000
        assertTrue(ef.getRationale().contains("below the 3-month minimum"), ef.getRationale());
        log.info("Emergency fund test rationale: {}", ef.getRationale());
    }

    @Test
    void debtToIncome_aboveGuideline_isFlaggedAbove36Percent() {
        User user = newUser("dti@flowstate.app");
        Account checking = newAccount(user, AccountType.CHECKING, "50000.00");

        txn(checking, Category.SALARY, TransactionType.INCOME, "180000.00");            // 60000/mo
        txn(checking, Category.LOAN_OR_DEBT_PAYMENT, TransactionType.EXPENSE, "75000.00"); // 25000/mo -> 41.7%

        List<Recommendation> recs = recommendationEngine.generateRecommendations(user);
        Recommendation dti = findFirst(recs, RecommendationType.DEBT_RATIO);

        assertEquals(RecommendationEngine.BENCHMARK_DTI, dti.getBenchmarkReference());
        assertEquals(41.7, dti.getAmount().doubleValue(), 0.1);
        assertTrue(dti.getRationale().contains("above"), dti.getRationale());
        log.info("DTI test rationale: {}", dti.getRationale());
    }

    @Test
    void shockScenario_volatileBillHistoryWidensIntervalAndLowersSafeToInvest() {
        User stableUser = newUser("stable@flowstate.app");
        Account stableAccount = newAccount(stableUser, AccountType.CHECKING, "50000.00");
        seedBill(stableAccount, 2000, 2000, 2000, 2000, 2000, 2000);
        List<Recommendation> stableRecs = recommendationEngine.generateRecommendations(stableUser);
        Recommendation stableSafe = findFirst(stableRecs, RecommendationType.SAFE_TO_INVEST);

        User shockUser = newUser("shock@flowstate.app");
        Account shockAccount = newAccount(shockUser, AccountType.CHECKING, "50000.00");
        seedBill(shockAccount, 1000, 2000, 4000, 1000, 2000, 4000); // same average-ish, much higher variance
        List<Recommendation> shockRecs = recommendationEngine.generateRecommendations(shockUser);
        Recommendation shockSafe = findFirst(shockRecs, RecommendationType.SAFE_TO_INVEST);

        double stableRange = stableSafe.getConfidenceHigh().doubleValue() - stableSafe.getConfidenceLow().doubleValue();
        double shockRange = shockSafe.getConfidenceHigh().doubleValue() - shockSafe.getConfidenceLow().doubleValue();

        log.info("Shock scenario: stable safe-to-invest={} (range {}), volatile safe-to-invest={} (range {})",
                stableSafe.getAmount(), stableRange, shockSafe.getAmount(), shockRange);

        assertTrue(shockRange > stableRange,
                "Volatile bill history should widen the confidence interval: stable=" + stableRange + " shock=" + shockRange);
        assertTrue(shockSafe.getAmount().doubleValue() < stableSafe.getAmount().doubleValue(),
                "Volatile bill history should lower the conservative safe-to-invest figure");
    }

    // ---- helpers ------------------------------------------------------------

    /** Seeds one occurrence every 30 days, most recent 1 day ago, oldest first in {@code amounts}. */
    private void seedBill(Account account, double... amounts) {
        LocalDate lastSeen = LocalDate.now().minusDays(1);
        int n = amounts.length;
        for (int i = 0; i < n; i++) {
            LocalDate date = lastSeen.minusDays((long) (n - 1 - i) * 30);
            transactionRepository.save(new Transaction(account, date, bd(amounts[i]), TransactionType.EXPENSE,
                    Category.UTILITIES, "Utility Co"));
        }
    }

    private User newUser(String email) {
        User user = new User(email, "Test User", "x");
        return userRepository.save(user);
    }

    private Account newAccount(User user, AccountType type, String balance) {
        return accountRepository.save(new Account(user, type.name(), type, new BigDecimal(balance)));
    }

    private void txn(Account account, Category category, TransactionType type, String amount) {
        transactionRepository.save(new Transaction(account, LocalDate.now().minusDays(10), bd(Double.parseDouble(amount)),
                type, category, category.name() + " Merchant"));
    }

    private BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private Recommendation findAlert(List<Recommendation> recs, String titlePrefix) {
        return recs.stream()
                .filter(r -> r.getType() == RecommendationType.BUDGET_ALERT && r.getTitle().startsWith(titlePrefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No budget alert titled like: " + titlePrefix));
    }

    private Recommendation findFirst(List<Recommendation> recs, RecommendationType type) {
        return recs.stream().filter(r -> r.getType() == type).findFirst()
                .orElseThrow(() -> new AssertionError("No recommendation of type " + type));
    }

    private double extractPercent(String rationale) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("is (\\d+\\.\\d+)% of").matcher(rationale);
        assertTrue(m.find(), "Could not find percentage in rationale: " + rationale);
        return Double.parseDouble(m.group(1));
    }
}
