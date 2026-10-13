package com.example.adaptt.control;

/** Cumulative controller search and decision-cost metrics for one run. */
public record ControllerMetricsSnapshot(
        boolean available,
        long decisionCount,
        long candidateCount,
        long acceptedCount,
        long rejectedCount,
        long rollbackCount,
        long stableEnterCount,
        long recoveryCount,
        long decisionTotalNanos,
        double decisionMeanNanos,
        long decisionP95Nanos,
        long decisionMaxNanos) {

    public static ControllerMetricsSnapshot unavailable() {
        return new ControllerMetricsSnapshot(
                false,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0.0,
                0L,
                0L);
    }
}
