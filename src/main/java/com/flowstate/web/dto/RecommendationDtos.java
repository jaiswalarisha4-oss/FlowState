package com.flowstate.web.dto;

import com.flowstate.domain.Recommendation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class RecommendationDtos {

    private RecommendationDtos() {
    }

    public record RecommendationResponse(Long id, String type, String title, BigDecimal amount,
                                          BigDecimal confidenceLow, BigDecimal confidenceHigh, String rationale,
                                          String benchmarkReference, String modelVersion, Instant generatedAt) {
        public static RecommendationResponse from(Recommendation r) {
            return new RecommendationResponse(r.getId(), r.getType().name(), r.getTitle(), r.getAmount(),
                    r.getConfidenceLow(), r.getConfidenceHigh(), r.getRationale(), r.getBenchmarkReference(),
                    r.getModelVersion(), r.getGeneratedAt());
        }
    }

    public record DecisionTraceResponse(RecommendationResponse recommendation,
                                         List<TransactionDtos.TransactionResponse> supportingTransactions) {
    }
}
