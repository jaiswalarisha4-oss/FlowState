package com.flowstate.web;

import com.flowstate.domain.*;
import com.flowstate.repository.AccountRepository;
import com.flowstate.repository.RecommendationRepository;
import com.flowstate.repository.TransactionRepository;
import com.flowstate.security.CurrentUserService;
import com.flowstate.web.dto.DashboardDtos.*;
import com.flowstate.web.dto.RecommendationDtos.RecommendationResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final int TREND_MONTHS = 9;

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final RecommendationRepository recommendationRepository;
    private final CurrentUserService currentUserService;

    public DashboardController(AccountRepository accountRepository, TransactionRepository transactionRepository,
                                RecommendationRepository recommendationRepository, CurrentUserService currentUserService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.recommendationRepository = recommendationRepository;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/summary")
    public DashboardSummaryResponse summary() {
        User user = currentUserService.getCurrentUser();
        List<Account> accounts = accountRepository.findByUser(user);

        List<AccountSummary> accountSummaries = accounts.stream()
                .map(a -> new AccountSummary(a.getId(), a.getName(), a.getType().name(), a.getBalance()))
                .toList();
        BigDecimal netWorth = accounts.stream().map(Account::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDate trendStart = LocalDate.now().minusMonths(TREND_MONTHS).withDayOfMonth(1);
        List<Transaction> trendTxns = transactionRepository
                .findByAccountInAndDateAfterOrderByDateAsc(accounts, trendStart.minusDays(1));

        Map<YearMonth, BigDecimal> incomeByMonth = new TreeMap<>();
        Map<YearMonth, BigDecimal> expenseByMonth = new TreeMap<>();
        for (Transaction t : trendTxns) {
            YearMonth ym = YearMonth.from(t.getDate());
            if (t.getType() == TransactionType.INCOME) {
                incomeByMonth.merge(ym, t.getAmount(), BigDecimal::add);
            } else {
                expenseByMonth.merge(ym, t.getAmount(), BigDecimal::add);
            }
        }
        List<MonthlyPoint> trend = new ArrayList<>();
        YearMonth cursor = YearMonth.from(trendStart);
        YearMonth end = YearMonth.from(LocalDate.now());
        while (!cursor.isAfter(end)) {
            BigDecimal income = incomeByMonth.getOrDefault(cursor, BigDecimal.ZERO);
            BigDecimal expense = expenseByMonth.getOrDefault(cursor, BigDecimal.ZERO);
            trend.add(new MonthlyPoint(cursor.format(MONTH_FMT), income, expense, income.subtract(expense)));
            cursor = cursor.plusMonths(1);
        }

        LocalDate quarterStart = LocalDate.now().minusDays(90);
        List<Transaction> quarterTxns = transactionRepository
                .findByAccountInAndDateAfterOrderByDateAsc(accounts, quarterStart);
        Map<Category, BigDecimal> totalsByCategory = quarterTxns.stream()
                .filter(t -> t.getType() == TransactionType.EXPENSE)
                .collect(Collectors.groupingBy(Transaction::getCategory,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));
        List<CategoryTotal> categoryBreakdown = totalsByCategory.entrySet().stream()
                .map(e -> new CategoryTotal(e.getKey().name(), e.getKey().getGroup().name(),
                        e.getValue().divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP)))
                .sorted(Comparator.comparing(CategoryTotal::monthlyAverageAmount).reversed())
                .toList();

        RecommendationResponse safeToInvest = recommendationRepository.findByUserOrderByGeneratedAtDesc(user).stream()
                .filter(r -> r.getType() == RecommendationType.SAFE_TO_INVEST)
                .findFirst()
                .map(RecommendationResponse::from)
                .orElse(null);

        return new DashboardSummaryResponse(accountSummaries, netWorth, trend, categoryBreakdown, safeToInvest);
    }
}
