package com.flowstate.domain;

/**
 * Spending / income categories. Each category is pre-mapped to a
 * {@link CategoryGroup} so category totals can be rolled up into the
 * needs / wants / savings split used by the 50/30/20 benchmark.
 */
public enum Category {
    SALARY(CategoryGroup.INCOME),
    FREELANCE_INCOME(CategoryGroup.INCOME),
    OTHER_INCOME(CategoryGroup.INCOME),

    RENT_OR_MORTGAGE(CategoryGroup.NEEDS),
    UTILITIES(CategoryGroup.NEEDS),
    GROCERIES(CategoryGroup.NEEDS),
    TRANSPORT(CategoryGroup.NEEDS),
    INSURANCE(CategoryGroup.NEEDS),
    HEALTHCARE(CategoryGroup.NEEDS),
    LOAN_OR_DEBT_PAYMENT(CategoryGroup.NEEDS),

    DINING_OUT(CategoryGroup.WANTS),
    ENTERTAINMENT(CategoryGroup.WANTS),
    SUBSCRIPTIONS(CategoryGroup.WANTS),
    SHOPPING(CategoryGroup.WANTS),
    TRAVEL(CategoryGroup.WANTS),
    OTHER_EXPENSE(CategoryGroup.WANTS),

    SAVINGS_TRANSFER(CategoryGroup.SAVINGS_AND_DEBT),
    INVESTMENT(CategoryGroup.SAVINGS_AND_DEBT);

    private final CategoryGroup group;

    Category(CategoryGroup group) {
        this.group = group;
    }

    public CategoryGroup getGroup() {
        return group;
    }

    public boolean isIncome() {
        return group == CategoryGroup.INCOME;
    }
}
