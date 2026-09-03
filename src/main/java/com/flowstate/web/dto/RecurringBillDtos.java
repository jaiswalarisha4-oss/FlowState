package com.flowstate.web.dto;

import com.flowstate.domain.RecurringBill;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class RecurringBillDtos {

    private RecurringBillDtos() {
    }

    public record RecurringBillResponse(Long id, String merchant, String category, BigDecimal averageAmount,
                                         BigDecimal amountStdDev, double averageIntervalDays,
                                         double confidenceScore, int occurrenceCount, LocalDate firstSeenDate,
                                         LocalDate lastSeenDate, LocalDate predictedNextDate) {
        public static RecurringBillResponse from(RecurringBill b) {
            return new RecurringBillResponse(b.getId(), b.getMerchantDisplayName(), b.getCategory().name(),
                    b.getAverageAmount(), b.getAmountStdDev(), Math.round(b.getAverageIntervalDays() * 10) / 10.0,
                    b.getConfidenceScore(), b.getOccurrenceCount(), b.getFirstSeenDate(), b.getLastSeenDate(),
                    b.getPredictedNextDate());
        }
    }
}
