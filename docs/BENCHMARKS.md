# Benchmarks

These are real numbers produced by the project's own automated test suite
(`mvn test`), not placeholders. Every number below can be reproduced by
running the tests named next to it. This file is what backs the "tested
against proven external benchmarks" claim in the README and resume bullets
— see each test's Javadoc for the full methodology.

Last measured: 2026-09-03, on `mvn test` against commit at time of writing.

## 1. Recurring-bill detection — precision/recall on a labeled synthetic dataset

**Test:** `RecurringBillDetectionBenchmarkTest`

A synthetic 11-month transaction history is generated with a **known ground
truth**: 6 merchants are genuinely recurring bills (rent, Netflix, Spotify,
gym, phone, electricity — with realistic amount/interval jitter), 4 merchants
recur but with no real pattern (random dates across the whole window, bimodal
impulse-purchase-style amounts), and 15 merchants never repeat at all. The
detector's output (bills flagged at confidence ≥ 35%) is scored against that
label set with standard information-retrieval metrics.

| Metric | Value |
|---|---|
| True positives | 6 / 6 |
| False positives | 0 |
| False negatives | 0 |
| **Precision** | **1.00** |
| **Recall** | **1.00** |
| **F1** | **1.00** |

Per-merchant detail from the last run (confidence score, occurrence count):

| Merchant | Confidence | Occurrences | Ground truth |
|---|---:|---:|---|
| Rent - Lakeview Apartments | 91.7% | 11 | bill |
| Netflix | 90.8% | 11 | bill |
| Spotify | 90.7% | 11 | bill |
| FitLife Gym | 90.2% | 11 | bill |
| Airtel Postpaid | 89.3% | 11 | bill |
| City Power & Electric | 80.7% | 11 | bill |
| StyleHub (irregular impulse spend) | 32.1% | 5 | noise — correctly below the 35% display threshold |
| Cafe Coffee Beans (irregular) | 17.4% | 6 | noise — correctly excluded |
| GameZone Arcade (irregular) | 14.8% | 4 | noise — correctly excluded |
| Urban Bowl Restaurant (irregular) | 6.7% | 5 | noise — correctly excluded |

Reproduce: `mvn -Dtest=RecurringBillDetectionBenchmarkTest test`

## 2. Recommendation engine vs. external personal-finance benchmarks

**Test:** `RecommendationEngineBenchmarkTest`

Four independently-documented, external rules are checked directly against
hand-computed scenarios (not just spot-checked — every percentage below is
exact arithmetic on constructed transaction totals):

| Benchmark | Scenario | Expected | Engine output |
|---|---|---|---|
| 50/30/20 Rule (needs) | ₹90,000 needs / ₹180,000 income over trailing quarter | 50.0% | **50.0%** ✅ |
| 50/30/20 Rule (wants) | ₹54,000 wants / ₹180,000 income | 30.0% | **30.0%** ✅ |
| 50/30/20 Rule (savings) | ₹36,000 savings / ₹180,000 income | 20.0% | **20.0%** ✅ |
| 50/30/20 Rule (overspend case) | ₹300,000 wants / ₹180,000 income | 166.7%, flagged "above" | **166.7%, flagged "above"** ✅ |
| 3-6 Month Emergency Fund | ₹50,000 savings vs. ₹30,000/mo essentials | range ₹90k–₹180k, shortfall ₹40k | **₹90,000 / ₹180,000 / ₹40,000 shortfall** ✅ |
| 36% Debt-to-Income ceiling | ₹25,000 debt / ₹60,000 income | 41.7%, flagged "above" | **41.7%, flagged "above"** ✅ |

Reproduce: `mvn -Dtest=RecommendationEngineBenchmarkTest#budgetCheck_exactlyOnTarget_isFlaggedWithinBand,RecommendationEngineBenchmarkTest#budgetCheck_overspendingOnWants_isFlaggedAboveTarget,RecommendationEngineBenchmarkTest#emergencyFund_belowThreeMonths_flagsShortfallAgainstGuideline,RecommendationEngineBenchmarkTest#debtToIncome_aboveGuideline_isFlaggedAbove36Percent test`

## 3. Shock scenario — does the engine get more conservative under uncertainty?

**Test:** `RecommendationEngineBenchmarkTest#shockScenario_volatileBillHistoryWidensIntervalAndLowersSafeToInvest`

A miniature version of the original brief's "financial shock simulator": two
otherwise-identical users (same balance, same average bill amount) differ
only in how volatile their bill history is. One has a bill that's always
exactly ₹2,000; the other has the same bill swinging between ₹1,000, ₹2,000
and ₹4,000. If the recommendation engine is doing its job, it should treat
the volatile history as genuinely riskier — a wider confidence interval and
a lower "safe to invest" figure — not just average it away.

| | Stable bill history | Volatile bill history |
|---|---:|---:|
| Safe-to-invest amount | ₹47,600.00 | **₹44,737.99** (−6.0%) |
| Confidence interval width | ₹0.00 | **₹2,462.01** |

The engine correctly widens the interval and lowers the recommended amount
when the underlying data is noisier — it does not just report the average
case as if it were certain.

## 4. Utility-level correctness

**Test:** `MerchantNormalizerAndSimilarityTest` — merchant-string normalization
(case, punctuation, trailing reference numbers, `.com`/`inc`-style suffixes)
and Levenshtein similarity scoring, the two building blocks the recurring-bill
clustering is built on. 4/4 passing.

## Running the full suite

```bash
mvn test
```

11 tests, 0 failures, ~6 seconds on a typical laptop (excluding the first-run
Maven dependency download). CI runs this on every push — see
`.github/workflows/ci.yml`.
