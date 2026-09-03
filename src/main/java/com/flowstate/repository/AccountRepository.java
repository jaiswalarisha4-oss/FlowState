package com.flowstate.repository;

import com.flowstate.domain.Account;
import com.flowstate.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findByUser(User user);
}
