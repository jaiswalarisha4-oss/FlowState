package com.flowstate.repository;

import com.flowstate.domain.RecurringBill;
import com.flowstate.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecurringBillRepository extends JpaRepository<RecurringBill, Long> {
    List<RecurringBill> findByUserOrderByConfidenceScoreDesc(User user);
    void deleteByUser(User user);
}
