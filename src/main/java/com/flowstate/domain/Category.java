package com.flowstate.domain;

/**
 * Spending / income categories. Each category is pre-mapped to a
 * {@link CategoryGroup} so category totals can be rolled up into the
 * needs / wants / savings split used by the 50/30/20 benchmark, and to a
 * {@code billEligible} flag used by {@code RecurringBillDetectionService}
 * (see that class's Javadoc for why a category-level signal is needed
 * alongside pure amount/interval statistics — some spending is regular
 * enough to look exactly like a bill without conceptually being one).
 */
public enum Category {
    SALARY(CategoryGroup.INCOME, false),
    FREELANCE_INCOME(CategoryGroup.INCOME, false),
    OTHER_INCOME(CategoryGroup.INCOME, false),

    RENT_OR_MORTGAGE(CategoryGroup.NEEDS, true),
    UTILITIES(CategoryGroup.NEEDS, true),
    GROCERIES(CategoryGroup.NEEDS, false),
    TRANSPORT(CategoryGroup.NEEDS, false),
    INSURANCE(CategoryGroup.NEEDS, true),
    HEALTHCARE(CategoryGroup.NEEDS, false),
    LOAN_OR_DEBT_PAYMENT(CategoryGroup.NEEDS, true),

    DINING_OUT(CategoryGroup.WANTS, false),
    ENTERTAINMENT(CategoryGroup.WANTS, false),
    SUBSCRIPTIONS(CategoryGroup.WANTS, true),
    SHOPPING(CategoryGroup.WANTS, false),
    TRAVEL(CategoryGroup.WANTS, false),
    OTHER_EXPENSE(CategoryGroup.WANTS, false),

    SAVINGS_TRANSFER(CategoryGroup.SAVINGS_AND_DEBT, false),
    INVESTMENT(CategoryGroup.SAVINGS_AND_DEBT, false);

    private final CategoryGroup group;
    private final boolean billEligible;

    Category(CategoryGroup group, boolean billEligible) {
        this.group = group;
        this.billEligible = billEligible;
    }

    public CategoryGroup getGroup() {
        return group;
    }

    public boolean isIncome() {
        return group == CategoryGroup.INCOME;
    }

    /**
     * Whether this category is the kind of fixed obligation a "recurring bill" means
     * (rent, a utility, insurance, a loan payment, a subscription) as opposed to spending
     * that can *look* statistically regular without being a bill in any meaningful sense
     * (gas fill-ups on a routine commute, a weekly grocery run). See
     * {@code RecurringBillDetectionService}.
     */
    public boolean isBillEligible() {
        return billEligible;
    }
}
