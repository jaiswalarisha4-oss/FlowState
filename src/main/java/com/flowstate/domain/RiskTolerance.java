package com.flowstate.domain;

/**
 * User-selected risk tolerance. Drives the safety-margin multiplier applied
 * to the liquidity buffer in {@code RecommendationEngine} — a lower risk
 * tolerance means a bigger buffer (more conservative "safe to invest" number).
 */
public enum RiskTolerance {
    LOW(1.5),
    MEDIUM(1.2),
    HIGH(1.0);

    private final double bufferMultiplier;

    RiskTolerance(double bufferMultiplier) {
        this.bufferMultiplier = bufferMultiplier;
    }

    public double getBufferMultiplier() {
        return bufferMultiplier;
    }
}
