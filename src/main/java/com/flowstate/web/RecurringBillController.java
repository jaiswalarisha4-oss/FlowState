package com.flowstate.web;

import com.flowstate.domain.User;
import com.flowstate.repository.RecurringBillRepository;
import com.flowstate.security.CurrentUserService;
import com.flowstate.web.dto.RecurringBillDtos.RecurringBillResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/recurring-bills")
public class RecurringBillController {

    private final RecurringBillRepository recurringBillRepository;
    private final CurrentUserService currentUserService;

    public RecurringBillController(RecurringBillRepository recurringBillRepository, CurrentUserService currentUserService) {
        this.recurringBillRepository = recurringBillRepository;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public List<RecurringBillResponse> list() {
        User user = currentUserService.getCurrentUser();
        return recurringBillRepository.findByUserOrderByConfidenceScoreDesc(user).stream()
                .map(RecurringBillResponse::from)
                .toList();
    }
}
