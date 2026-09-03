package com.flowstate.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "transaction", indexes = {
        @Index(name = "idx_transaction_account_date", columnList = "account_id, date"),
        @Index(name = "idx_transaction_merchant", columnList = "merchant_normalized")
})
@Getter
@Setter
@NoArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false)
    private LocalDate date;

    /** Always a positive magnitude; direction is carried by {@link #type}. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(nullable = false)
    private String merchant;

    /** Lower-cased, punctuation-stripped merchant name used for clustering. */
    @Column(name = "merchant_normalized", nullable = false)
    private String merchantNormalized;

    private String description;

    public Transaction(Account account, LocalDate date, BigDecimal amount, TransactionType type,
                        Category category, String merchant) {
        this.account = account;
        this.date = date;
        this.amount = amount;
        this.type = type;
        this.category = category;
        this.merchant = merchant;
        this.merchantNormalized = com.flowstate.util.MerchantNormalizer.normalize(merchant);
    }

    /** Signed amount: negative for expenses, positive for income. Convenience for aggregation. */
    public BigDecimal signedAmount() {
        return type == TransactionType.EXPENSE ? amount.negate() : amount;
    }
}
