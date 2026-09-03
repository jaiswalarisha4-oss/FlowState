package com.flowstate.web.dto;

import com.flowstate.domain.AuditLog;

import java.time.Instant;

public final class AuditDtos {

    private AuditDtos() {
    }

    public record AuditLogResponse(Long id, Long recommendationId, String action, String modelVersion,
                                    String details, Instant timestamp) {
        public static AuditLogResponse from(AuditLog a) {
            return new AuditLogResponse(a.getId(), a.getRecommendationId(), a.getAction(), a.getModelVersion(),
                    a.getDetails(), a.getTimestamp());
        }
    }
}
