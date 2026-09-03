package com.flowstate.web.dto;

import com.flowstate.domain.Transaction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class TransactionDtos {

    private TransactionDtos() {
    }

    public record TransactionResponse(Long id, Long accountId, String accountName, LocalDate date,
                                       BigDecimal amount, String type, String category, String merchant,
                                       String description) {
        public static TransactionResponse from(Transaction t) {
            return new TransactionResponse(t.getId(), t.getAccount().getId(), t.getAccount().getName(),
                    t.getDate(), t.getAmount(), t.getType().name(), t.getCategory().name(),
                    t.getMerchant(), t.getDescription());
        }
    }

    public record CreateTransactionRequest(
            @NotNull Long accountId,
            @NotNull LocalDate date,
            @NotNull @Positive BigDecimal amount,
            @NotBlank String type,
            @NotBlank String category,
            @NotBlank String merchant,
            String description
    ) {
    }
}
