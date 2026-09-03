package com.flowstate.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An explainable recommendation. Every recommendation is generated with its
 * full "decision trace" attached — the transactions that fed it, the
 * confidence interval where applicable, the external benchmark it was
 * checked against, and a plain-language rationale — so nothing is a black
 * box. See {@code RecommendationEngine} and docs/ARCHITECTURE.md.
 */
@Entity
@Table(name = "recommendation")
@Getter
@Setter
@NoArgsConstructor
public class Recommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecommendationType type;

    @Column(nullable = false)
    private String title;

    /** Primary figure the recommendation is about (e.g. safe-to-invest amount). Nullable for qualitative alerts. */
    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(precision = 14, scale = 2)
    private BigDecimal confidenceLow;

    @Column(precision = 14, scale = 2)
    private BigDecimal confidenceHigh;

    /** Plain-language "why" — the human-readable half of the decision trace. */
    @Lob
    @Column(nullable = false)
    private String rationale;

    /** The external, established rule this recommendation was validated against, e.g. "50/30/20 Rule". */
    @Column(nullable = false)
    private String benchmarkReference;

    @Column(nullable = false)
    private String modelVersion;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "recommendation_supporting_txn", joinColumns = @JoinColumn(name = "recommendation_id"))
    @Column(name = "transaction_id")
    private List<Long> supportingTransactionIds = new ArrayList<>();

    @Column(nullable = false)
    private Instant generatedAt = Instant.now();
}
