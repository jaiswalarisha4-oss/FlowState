package com.flowstate.service;

import com.flowstate.domain.Account;
import com.flowstate.domain.AccountType;
import com.flowstate.domain.Transaction;
import com.flowstate.domain.User;
import com.flowstate.repository.AccountRepository;
import com.flowstate.repository.TransactionRepository;
import com.flowstate.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Seeds a demo account with ~9 months of synthetic transaction history on
 * first startup so the app is fully explorable without connecting a real
 * bank. No live bank/brokerage connection exists anywhere in this project
 * — see README "Scope & Disclaimers".
 */
@Component
public class DemoDataSeeder implements CommandLineRunner {

    public static final String DEMO_EMAIL = "demo@flowstate.app";
    public static final String DEMO_PASSWORD = "flowstate123";
    private static final long SEED = 42L;

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final PasswordEncoder passwordEncoder;
    private final RecommendationEngine recommendationEngine;

    public DemoDataSeeder(UserRepository userRepository, AccountRepository accountRepository,
                           TransactionRepository transactionRepository, PasswordEncoder passwordEncoder,
                           RecommendationEngine recommendationEngine) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.passwordEncoder = passwordEncoder;
        this.recommendationEngine = recommendationEngine;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.existsByEmail(DEMO_EMAIL)) {
            log.info("Demo account already present, skipping seed.");
            return;
        }
        log.info("Seeding demo account ({}) with synthetic transaction history...", DEMO_EMAIL);

        User user = new User(DEMO_EMAIL, "Demo User", passwordEncoder.encode(DEMO_PASSWORD));
        userRepository.save(user);

        Account checking = accountRepository.save(new Account(user, "Checking", AccountType.CHECKING, new BigDecimal("20000.00")));
        Account savings = accountRepository.save(new Account(user, "Savings", AccountType.SAVINGS, new BigDecimal("50000.00")));

        LocalDate end = LocalDate.now();
        LocalDate start = end.minusMonths(9).withDayOfMonth(1);

        SyntheticTransactionGenerator generator = new SyntheticTransactionGenerator(SEED);
        List<Transaction> transactions = generator.generateHistory(checking, savings, start, end, true);
        transactionRepository.saveAll(transactions);

        applyBalance(checking, transactions);
        applyBalance(savings, transactions);
        accountRepository.save(checking);
        accountRepository.save(savings);

        recommendationEngine.generateRecommendations(user);

        log.info("Seed complete: {} transactions across 2 accounts for {}.", transactions.size(), DEMO_EMAIL);
        log.info("Demo login -> email: {}  password: {}", DEMO_EMAIL, DEMO_PASSWORD);
    }

    private void applyBalance(Account account, List<Transaction> transactions) {
        BigDecimal delta = transactions.stream()
                .filter(t -> t.getAccount() == account)
                .map(Transaction::signedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        account.setBalance(account.getBalance().add(delta));
    }
}
