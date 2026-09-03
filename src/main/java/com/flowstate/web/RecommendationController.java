package com.flowstate.web;

import com.flowstate.domain.Recommendation;
import com.flowstate.domain.Transaction;
import com.flowstate.domain.User;
import com.flowstate.repository.RecommendationRepository;
import com.flowstate.repository.TransactionRepository;
import com.flowstate.security.CurrentUserService;
import com.flowstate.service.RecommendationEngine;
import com.flowstate.web.dto.RecommendationDtos.DecisionTraceResponse;
import com.flowstate.web.dto.RecommendationDtos.RecommendationResponse;
import com.flowstate.web.dto.TransactionDtos.TransactionResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private static final int LATEST_BATCH_SIZE = 6;

    private final RecommendationRepository recommendationRepository;
    private final TransactionRepository transactionRepository;
    private final RecommendationEngine recommendationEngine;
    private final CurrentUserService currentUserService;

    public RecommendationController(RecommendationRepository recommendationRepository,
                                     TransactionRepository transactionRepository,
                                     RecommendationEngine recommendationEngine,
                                     CurrentUserService currentUserService) {
        this.recommendationRepository = recommendationRepository;
        this.transactionRepository = transactionRepository;
        this.recommendationEngine = recommendationEngine;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public List<RecommendationResponse> latest() {
        User user = currentUserService.getCurrentUser();
        return recommendationRepository.findByUserOrderByGeneratedAtDesc(user).stream()
                .limit(LATEST_BATCH_SIZE)
                .map(RecommendationResponse::from)
                .toList();
    }

    @PostMapping("/generate")
    public List<RecommendationResponse> generate() {
        User user = currentUserService.getCurrentUser();
        return recommendationEngine.generateRecommendations(user).stream()
                .map(RecommendationResponse::from)
                .toList();
    }

    @GetMapping("/{id}/trace")
    public DecisionTraceResponse trace(@PathVariable Long id) {
        User user = currentUserService.getCurrentUser();
        Recommendation rec = recommendationRepository.findById(id)
                .filter(r -> r.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Recommendation not found"));

        List<TransactionResponse> supporting = transactionRepository.findAllById(rec.getSupportingTransactionIds())
                .stream()
                .map(TransactionResponse::from)
                .toList();

        return new DecisionTraceResponse(RecommendationResponse.from(rec), supporting);
    }
}
