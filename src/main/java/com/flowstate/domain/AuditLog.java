package com.flowstate.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Immutable audit trail entry. Every recommendation FlowState generates is
 * logged here with the model version that produced it, giving a governance
 * / compliance layer modeled loosely on the kind of model-versioning +
 * disclosure requirements regulators (e.g. SEBI's AI accountability
 * guidance) are converging on for automated financial advice. This is a
 * design-pattern demonstration for a student project, not a certified
 * compliance system — see the Scope & Disclaimers section of the README.
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    private Long recommendationId;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private String modelVersion;

    @Lob
    private String details;

    @Column(nullable = false)
    private Instant timestamp = Instant.now();

    public AuditLog(Long userId, Long recommendationId, String action, String modelVersion, String details) {
        this.userId = userId;
        this.recommendationId = recommendationId;
        this.action = action;
        this.modelVersion = modelVersion;
        this.details = details;
    }
}
