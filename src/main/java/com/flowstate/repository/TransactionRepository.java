package com.flowstate.repository;

import com.flowstate.domain.Account;
import com.flowstate.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    List<Transaction> findByAccountInOrderByDateDesc(List<Account> accounts);

    List<Transaction> findByAccountInAndDateBetweenOrderByDateAsc(List<Account> accounts, LocalDate from, LocalDate to);

    List<Transaction> findByAccountInAndDateAfterOrderByDateAsc(List<Account> accounts, LocalDate after);
}
