# FlowState — Agentic Wealth Co-Pilot with a Governance Layer
### Project Brief · v2 (Governance-First Redesign)

> **Note (this repository):** this is the *original* project brief, written
> for a Node.js/Python/React/Postgres/Plaid/Alpaca stack. This repository is
> a from-scratch **Java** rebuild that keeps the brief's core ideas
> (predictive liquidity, explainability, governance/audit, external-benchmark
> validation) but deliberately scopes the implementation down to a single
> Spring Boot application with synthetic data instead of live bank/brokerage
> sandboxes. See [`docs/ARCHITECTURE.md`](ARCHITECTURE.md#design-decision-why-this-is-a-single-spring-boot-app-not-the-original-multi-service-plan)
> for exactly what changed and why — that pivot is itself kept here as a
> documented example of a design decision changed for a defensible reason,
> the same way this brief kept its own v1→v2 pivot rationale below.

---

## Elevator Pitch

FlowState is an agentic personal-finance system that forecasts how much of a user's money is genuinely safe to invest — not just what's left over — and surfaces that as an explainable, auditable recommendation rather than a silent autonomous trade. Where most 2026 "agentic finance" products compete on autonomy, FlowState competes on trust: every recommendation carries a visible reasoning trace, a confidence interval, and a compliance/audit layer modeled on real regulatory requirements (SEBI's 2026 AI Accountability Framework), plus a shock-simulation engine that stress-tests the model's safety margins against synthetic income disruptions before a user ever sees a recommendation.

---

## The Problem

- **Passive tracking is a dead end.** Knowing you saved money last month is useless if it sits idle losing value to inflation — but most finance apps stop at the pie chart.
- **"Just automate it" is now the industry default, not a differentiator.** By 2026, autonomous financial agents are being deployed at scale by major banks and embedded into most enterprise finance software. Claiming "we built an autonomous agent" no longer signals anything on its own.
- **The real unsolved problem is trust, not automation.** Industry research (KPMG, Deloitte) consistently shows the gap: most companies plan to deploy autonomous financial agents, but only a small fraction have actually shipped them to production, blocked specifically by governance, explainability, and audit concerns rather than modeling capability.
- **Autonomous trade execution has a real legal wall.** In India, SEBI treats any automated recommendation as regulated investment advice — the adviser is fully liable for AI-generated guidance, and combining advisory + execution under one roof without a separate legal entity is restricted. A silent "auto-sweep into an ETF" feature isn't just hard to license as a student project — it's the wrong feature to be proud of building.

---

## Why This Version (Design Pivot Rationale)

The original concept centered on autonomous one-click trade execution — sweeping detected "surplus" directly into equities. Competitive research showed this is (a) already commoditized across robo-advisors and enterprise agent products, and (b) legally gated in a way that a solo/student build cannot satisfy. The redesign keeps the hard, genuinely differentiated part — predicting *safe-to-invest* liquidity under uncertainty — and replaces the autonomy-as-headline framing with a governance-and-explainability-as-headline framing, which is both more defensible and closer to where the real 2026 industry problem sits.

*(This pivot documents evaluating the market and regulatory landscape rather than just shipping the first idea, not just changing course arbitrarily.)*

---

## System Overview — Core Components

1. **Predictive Liquidity Engine** — forecasts required near-term liquidity as a confidence interval, not a point estimate; surplus = balance minus upper-bound buffer.
2. **Decision Trace / Explainability Layer** — every recommendation ships with its contributing transactions, confidence interval, and a plain-language "why."
3. **Compliance Simulation Layer** — audit log of every recommendation and model version, mandatory AI-disclosure UI, and an agent pause/kill switch — modeled on SEBI's 2026 AI Accountability Framework requirements.
4. **Financial Shock Simulator** — replays synthetic income disruptions (gig income drop, delayed paycheck, medical expense) against the liquidity engine and reports how often "safe" recommendations would have actually held.
5. **Human-Gated Execution** — recommendation card → explicit one-tap confirm → paper-trade execution. No silent trades.

---

## Technical Flow

```
[1] INGESTION
Plaid Sandbox / Sahamati AA Sandbox (synthetic bank data)
        │  webhooks (transactions, balances)
        ▼
Node.js API gateway → BullMQ/Redis queue (async, non-blocking)
        │
        ▼
[2] TRANSACTION UNDERSTANDING
Recurring-bill detection:
  cluster on (amount similarity, interval regularity, merchant string similarity)
  → each flagged bill carries a confidence score, not a binary label
        │
        ▼
[3] PREDICTIVE LIQUIDITY ENGINE
Time-series forecast of required liquidity (Prophet/ARIMA baseline;
LSTM as stretch goal) over income cycle + detected recurring bills
        │
        ▼  outputs: required_buffer (upper confidence bound)
   surplus = current_balance − required_buffer  (at user risk tolerance)
        │
        ▼
[4] EXPLAINABILITY LAYER
Package every output with:
  - contributing transactions
  - confidence interval
  - model version id
  - plain-language rationale
        │
        ▼
[5] COMPLIANCE LAYER
  - audit log entry (recommendation + model version + timestamp)
  - AI-disclosure banner shown to user
  - agent pause/kill switch (global + per-user)
        │
        ▼
[6] FINANCIAL SHOCK SIMULATOR (offline/backtest, feeds engine tuning)
Replay historical + synthetic shock scenarios → measure how often
"safe" surplus calls would have actually caused a shortfall
        │
        ▼
[7] RECOMMENDATION → HUMAN CONFIRMATION
React/Vite dashboard renders investment card + reasoning
        │  user taps "Confirm"
        ▼
Alpaca Paper Trading API executes simulated trade
        │
        ▼
[8] PERSISTENCE
PostgreSQL — ACID-compliant transaction log, encrypted-at-rest
OAuth tokens, full audit trail tying every trade back to the
recommendation and model version that produced it
```

---

## Tech Stack by Layer

| Layer | Tools |
|---|---|
| Frontend | React + Vite dashboard; real-time financial health score, recommendation cards, decision-trace viewer |
| Orchestration / API | Node.js — gateway routing, OAuth 2.0 token management, async webhook processing |
| Queue | BullMQ / Redis for non-blocking ingestion |
| Forecasting | Python service (Prophet/ARIMA baseline, LSTM stretch) exposed via internal API |
| Bank data | Plaid Sandbox (or Sahamati Account Aggregator sandbox) |
| Market data | Financial Modeling Prep API |
| Execution | Alpaca Paper Trading API (explicitly non-production — flag this honestly) |
| Persistence | PostgreSQL — transactions, model version log, audit trail |
| Compliance layer | Custom audit-log service + disclosure middleware, modeled on SEBI's 2026 AI Accountability Framework requirements |

---

## Key Differentiators

- **You're not pitching "we automated investing"** — you're pitching that you identified automation as commoditized and solved the actual bottleneck (trust/governance) that industry research says is blocking real-world deployment.
- **The shock simulator gives you a real, defensible metric** instead of a vague claim — e.g., "backtested against N synthetic income-shock scenarios, X% of 'safe' recommendations held, Y% false-positive rate on scenarios that would have caused a shortfall." This is the single most resume-worthy artifact in the project.
- **You designed against a real regulatory framework**, not a hypothetical one. Naming SEBI's actual 2026 AI Accountability requirements (model versioning, disclosure, human oversight, sandbox testing) and showing you built infrastructure for them demonstrates maturity most student fintech projects don't have.
- **Explainability is a UI-visible feature, not a backend footnote** — the decision-trace viewer is something you can actually demo live, which matters more than describing an accuracy number.

---

## Metrics to Generate Once Built (fill in only with real, self-measured numbers)

Do not put placeholder numbers on your resume. Once you've built and backtested the engine, measure and report:

- Precision/recall of "safe-to-invest" calls against your shock-simulation scenarios
- False-positive rate (recommended surplus that would have caused a shortfall)
- Forecast latency (end-to-end: webhook → recommendation rendered)
- Number of synthetic scenarios covered in your backtest harness
- Recurring-bill detection accuracy against your labeled synthetic dataset

---

## Build Stages

Assumes solo, part-time work alongside coursework (~12-15 hrs/week). Adjust pace to your actual bandwidth, but keep the *order* — each stage depends on the one before it, and the order is deliberately front-loaded so you have a demoable, honestly-describable project at every checkpoint, not just at the very end.

| Stage | Focus | Est. duration | Cumulative |
|---|---|---|---|
| 0 | Scaffolding | 1 week | Week 1 |
| 1 | Ingestion & transaction understanding | 2 weeks | Week 3 |
| 2 | Predictive Liquidity Engine v1 | 2 weeks | Week 5 |
| 3 | Explainability + recommendation flow | 1 week | Week 6 |
| 4 | Shock simulator + backtest metrics | 2 weeks | Week 8 |
| 5 | Compliance/governance layer | 1 week | Week 9 |
| 6 | Polish + final review | 1 week | Week 10 |

*(Full stage-by-stage detail omitted here — see the original brief for the
day-by-day breakdown. This repository's implementation followed the same
priority order: detection + explainability before governance polish, since
those two "are what separate this from a generic budgeting app.")*
