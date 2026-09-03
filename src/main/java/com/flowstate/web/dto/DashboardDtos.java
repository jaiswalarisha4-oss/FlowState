package com.flowstate.web.dto;

import java.math.BigDecimal;
import java.util.List;

public final class DashboardDtos {

    private DashboardDtos() {
    }

    public record AccountSummary(Long id, String name, String type, BigDecimal balance) {
    }

    public record MonthlyPoint(String month, BigDecimal income, BigDecimal expense, BigDecimal net) {
    }

    public record CategoryTotal(String category, String group, BigDecimal monthlyAverageAmount) {
    }

    public record DashboardSummaryResponse(List<AccountSummary> accounts, BigDecimal netWorth,
                                            List<MonthlyPoint> monthlyTrend, List<CategoryTotal> categoryBreakdown,
                                            RecommendationDtos.RecommendationResponse safeToInvest) {
    }
}
