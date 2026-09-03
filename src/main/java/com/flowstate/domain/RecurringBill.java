package com.flowstate.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A merchant cluster that the {@code RecurringBillDetectionService} has
 * flagged as a likely recurring bill, carrying a confidence score rather
 * than a binary yes/no label (see docs/ARCHITECTURE.md for the scoring
 * formula).
 */
@Entity
@Table(name = "recurring_bill")
@Getter
@Setter
@NoArgsConstructor
public class RecurringBill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String merchantDisplayName;

    @Column(nullable = false)
    private String merchantNormalized;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal averageAmount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amountStdDev;

    @Column(nullable = false)
    private double averageIntervalDays;

    @Column(nullable = false)
    private double intervalStdDevDays;

    /** 0-100. See RecurringBillDetectionService for the weighted formula. */
    @Column(nullable = false)
    private double confidenceScore;

    @Column(nullable = false)
    private int occurrenceCount;

    @Column(nullable = false)
    private LocalDate firstSeenDate;

    @Column(nullable = false)
    private LocalDate lastSeenDate;

    @Column(nullable = false)
    private LocalDate predictedNextDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(nullable = false)
    private Instant detectedAt = Instant.now();
}
