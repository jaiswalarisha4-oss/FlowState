package com.flowstate.service;

import com.flowstate.domain.Account;
import com.flowstate.domain.Category;
import com.flowstate.domain.Transaction;
import com.flowstate.domain.TransactionType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates a realistic synthetic transaction history — no real bank
 * connection is used anywhere in this project (see README "Scope &
 * Disclaimers"). Deterministic given a seed, so the same seed used in the
 * demo seeder and in {@code RecurringBillDetectionBenchmarkTest} /
 * {@code RecommendationEngineBenchmarkTest} produces reproducible,
 * independently-checkable numbers.
 *
 * The history includes: a monthly salary, a set of recurring bills with
 * small realistic amount/date jitter (and occasional merchant-string
 * variants, e.g. "NETFLIX.COM" vs "Netflix", to exercise fuzzy merchant
 * matching), everyday discretionary spending, and — unless disabled — one
 * injected "shock" month with a large one-off medical expense, standing in
 * for the kind of income-disruption scenario a real shock-simulation
 * backtest would replay at scale (see {@code RecommendationEngineShockTest}
 * for the automated check of how the recommendation engine responds).
 */
public class SyntheticTransactionGenerator {

    public record BillSpec(String merchant, Category category, double baseAmount, double amountJitterPct,
                            int dayOfMonth, int dayJitter, List<String> nameVariants) {
    }

    private final Random random;

    public SyntheticTransactionGenerator(long seed) {
        this.random = new Random(seed);
    }

    public List<Transaction> generateHistory(Account checking, Account savings, LocalDate start, LocalDate end,
                                              boolean includeShockMonth) {
        List<Transaction> out = new ArrayList<>();

        List<BillSpec> bills = List.of(
                new BillSpec("Rent - Lakeview Apartments", Category.RENT_OR_MORTGAGE, 18000, 0.0, 1, 1, List.of()),
                new BillSpec("Netflix", Category.SUBSCRIPTIONS, 199, 0.0, 5, 1,
                        List.of("Netflix", "NETFLIX.COM")),
                new BillSpec("Spotify", Category.SUBSCRIPTIONS, 119, 0.0, 7, 1,
                        List.of("Spotify", "Spotify Premium")),
                new BillSpec("Airtel Postpaid", Category.UTILITIES, 499, 0.03, 10, 2, List.of()),
                new BillSpec("City Power & Electric", Category.UTILITIES, 1400, 0.35, 13, 3, List.of()),
                new BillSpec("FitLife Gym", Category.SUBSCRIPTIONS, 1200, 0.0, 15, 1, List.of()),
                new BillSpec("Term Life Insurance", Category.INSURANCE, 2200, 0.0, 20, 1, List.of())
        );

        int monthIndex = 0;
        LocalDate cursor = start.withDayOfMonth(1);
        int shockMonthIndex = monthsBetween(start, end) / 2;

        while (!cursor.isAfter(end)) {
            boolean isShockMonth = includeShockMonth && monthIndex == shockMonthIndex;
            int daysInMonth = cursor.lengthOfMonth();

            if (!isShockMonth) {
                int salaryDay = clampDay(cursor, 1 + random.nextInt(3));
                double salaryJitter = 1.0 + (random.nextDouble() - 0.5) * 0.02;
                out.add(txn(checking, cursor.withDayOfMonth(salaryDay), 62000 * salaryJitter,
                        TransactionType.INCOME, Category.SALARY, "Acme Corp Payroll"));
            }

            for (BillSpec bill : bills) {
                if (bill.category() == Category.INSURANCE && monthIndex % 3 != 0) {
                    continue; // quarterly
                }
                int day = clampDay(cursor, bill.dayOfMonth() + jitterDays(bill.dayJitter()));
                double amount = bill.baseAmount() * (1.0 + (random.nextDouble() - 0.5) * 2 * bill.amountJitterPct());
                String merchant = pickMerchantVariant(bill);
                out.add(txn(checking, cursor.withDayOfMonth(day), amount, TransactionType.EXPENSE,
                        bill.category(), merchant));
            }

            addDiscretionarySpending(out, checking, cursor, daysInMonth);

            if (random.nextDouble() < 0.5) {
                double transfer = 3000 + random.nextInt(4000);
                LocalDate transferDate = cursor.withDayOfMonth(clampDay(cursor, 4));
                out.add(txn(checking, transferDate, transfer, TransactionType.EXPENSE,
                        Category.SAVINGS_TRANSFER, "Transfer to Savings"));
                out.add(txn(savings, transferDate, transfer, TransactionType.INCOME,
                        Category.SAVINGS_TRANSFER, "Transfer from Checking"));
            }

            if (isShockMonth) {
                int day = clampDay(cursor, 8 + random.nextInt(10));
                out.add(txn(checking, cursor.withDayOfMonth(day), 24000 + random.nextInt(9000),
                        TransactionType.EXPENSE, Category.HEALTHCARE, "City General Hospital"));
            }

            monthIndex++;
            cursor = cursor.plusMonths(1);
        }

        return out;
    }

    private void addDiscretionarySpending(List<Transaction> out, Account checking, LocalDate monthStart, int daysInMonth) {
        int groceryTrips = 4;
        for (int i = 0; i < groceryTrips; i++) {
            int day = 1 + random.nextInt(daysInMonth);
            double amount = 900 + random.nextInt(1400);
            out.add(txn(checking, monthStart.withDayOfMonth(day), amount, TransactionType.EXPENSE,
                    Category.GROCERIES, pick("FreshMart", "GreenBasket Grocers", "Daily Needs Store")));
        }
        int diningTrips = 6 + random.nextInt(5);
        for (int i = 0; i < diningTrips; i++) {
            int day = 1 + random.nextInt(daysInMonth);
            double amount = 250 + random.nextInt(900);
            out.add(txn(checking, monthStart.withDayOfMonth(day), amount, TransactionType.EXPENSE,
                    Category.DINING_OUT, pick("Cafe Coffee Beans", "Spice Route Restaurant", "Urban Bowl", "Pizza Corner")));
        }
        int entertainmentTrips = 1 + random.nextInt(3);
        for (int i = 0; i < entertainmentTrips; i++) {
            int day = 1 + random.nextInt(daysInMonth);
            double amount = 300 + random.nextInt(1200);
            out.add(txn(checking, monthStart.withDayOfMonth(day), amount, TransactionType.EXPENSE,
                    Category.ENTERTAINMENT, pick("PVR Cinemas", "GameZone Arcade", "BookNook Store")));
        }
        int shoppingTrips = random.nextInt(3);
        for (int i = 0; i < shoppingTrips; i++) {
            int day = 1 + random.nextInt(daysInMonth);
            double amount = 500 + random.nextInt(3500);
            out.add(txn(checking, monthStart.withDayOfMonth(day), amount, TransactionType.EXPENSE,
                    Category.SHOPPING, pick("StyleHub", "TechBazaar", "HomeEssentials")));
        }
        int transportTrips = 3 + random.nextInt(3);
        for (int i = 0; i < transportTrips; i++) {
            int day = 1 + random.nextInt(daysInMonth);
            double amount = 150 + random.nextInt(600);
            out.add(txn(checking, monthStart.withDayOfMonth(day), amount, TransactionType.EXPENSE,
                    Category.TRANSPORT, pick("MetroRide", "CityCab", "FuelPoint")));
        }
    }

    private String pickMerchantVariant(BillSpec bill) {
        if (bill.nameVariants().isEmpty()) {
            return bill.merchant();
        }
        if (random.nextDouble() < 0.25) {
            return bill.nameVariants().get(random.nextInt(bill.nameVariants().size()));
        }
        return bill.merchant();
    }

    private String pick(String... options) {
        return options[random.nextInt(options.length)];
    }

    private int jitterDays(int max) {
        if (max <= 0) return 0;
        return random.nextInt(2 * max + 1) - max;
    }

    private int clampDay(LocalDate month, int day) {
        return Math.max(1, Math.min(month.lengthOfMonth(), day));
    }

    private int monthsBetween(LocalDate start, LocalDate end) {
        return (int) java.time.temporal.ChronoUnit.MONTHS.between(start.withDayOfMonth(1), end.withDayOfMonth(1));
    }

    private Transaction txn(Account account, LocalDate date, double amount, TransactionType type,
                             Category category, String merchant) {
        BigDecimal bd = BigDecimal.valueOf(Math.max(1, amount)).setScale(2, RoundingMode.HALF_UP);
        return new Transaction(account, date, bd, type, category, merchant);
    }
}
