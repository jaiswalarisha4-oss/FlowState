package com.flowstate.web;

import com.flowstate.domain.*;
import com.flowstate.repository.AccountRepository;
import com.flowstate.repository.TransactionRepository;
import com.flowstate.security.CurrentUserService;
import com.flowstate.web.dto.TransactionDtos.CreateTransactionRequest;
import com.flowstate.web.dto.TransactionDtos.TransactionResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CurrentUserService currentUserService;

    public TransactionController(TransactionRepository transactionRepository, AccountRepository accountRepository,
                                  CurrentUserService currentUserService) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public List<TransactionResponse> list(@RequestParam(defaultValue = "100") int limit) {
        User user = currentUserService.getCurrentUser();
        List<Account> accounts = accountRepository.findByUser(user);
        return transactionRepository.findByAccountInOrderByDateDesc(accounts).stream()
                .limit(Math.max(1, Math.min(limit, 1000)))
                .map(TransactionResponse::from)
                .toList();
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(@Valid @RequestBody CreateTransactionRequest req) {
        User user = currentUserService.getCurrentUser();
        Account account = accountRepository.findById(req.accountId())
                .filter(a -> a.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown account for current user"));

        TransactionType type = TransactionType.valueOf(req.type().toUpperCase());
        Category category = Category.valueOf(req.category().toUpperCase());

        Transaction txn = new Transaction(account, req.date(), req.amount(), type, category, req.merchant());
        txn.setDescription(req.description());
        transactionRepository.save(txn);

        account.setBalance(account.getBalance().add(txn.signedAmount()));
        accountRepository.save(account);

        return ResponseEntity.ok(TransactionResponse.from(txn));
    }
}
