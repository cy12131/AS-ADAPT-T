package com.example.adaptt.control;

import org.HdrHistogram.Histogram;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Observes controller decisions without participating in control logic. This
 * gives ADAPT-T and AS-ADAPT-T identical definitions for candidate, accepted,
 * rejected, rollback, and decision-time metrics.
 */
public final class ControllerMetricsTracker {
    private static final long MAX_TRACKABLE_DECISION_NANOS =
            TimeUnit.SECONDS.toNanos(10);
    private static final int SIGNIFICANT_DIGITS = 3;

    private final boolean available;
    private final Histogram decisionTimes = new Histogram(
            1L,
            MAX_TRACKABLE_DECISION_NANOS,
            SIGNIFICANT_DIGITS);

    private long decisionCount;
    private long candidateCount;
    private long acceptedCount;
    private long rejectedCount;
    private long rollbackCount;
    private long stableEnterCount;
    private long recoveryCount;
    private long decisionTotalNanos;

    public ControllerMetricsTracker(boolean available) {
        this.available = available;
    }

    public synchronized void record(
            ControlDecision decision,
            long elapsedNanos) {
        if (!available) {
            return;
        }
        Objects.requireNonNull(decision, "decision");
        long boundedElapsed = Math.max(
                1L,
                Math.min(elapsedNanos, MAX_TRACKABLE_DECISION_NANOS));
        decisionTimes.recordValue(boundedElapsed);
        decisionTotalNanos += Math.max(0L, elapsedNanos);
        decisionCount++;

        String stage = decision.stage();
        if (isCandidateApplied(stage)) {
            candidateCount++;
        }
        if (isAccepted(stage)) {
            acceptedCount++;
        }
        if (isRejected(stage)) {
            rejectedCount++;
            if (decision.poolBefore() != decision.poolAfter()) {
                rollbackCount++;
            }
        }
        if ("STABLE_REFERENCE_ESTABLISHED".equals(stage)) {
            stableEnterCount++;
        }
        if ("LOAD_CHANGE_DETECTED".equals(stage)) {
            recoveryCount++;
        }
    }

    public synchronized ControllerMetricsSnapshot snapshot() {
        if (!available) {
            return ControllerMetricsSnapshot.unavailable();
        }
        return new ControllerMetricsSnapshot(
                true,
                decisionCount,
                candidateCount,
                acceptedCount,
                rejectedCount,
                rollbackCount,
                stableEnterCount,
                recoveryCount,
                decisionTotalNanos,
                decisionCount == 0L ? 0.0 : decisionTimes.getMean(),
                decisionCount == 0L
                        ? 0L
                        : decisionTimes.getValueAtPercentile(95.0),
                decisionCount == 0L ? 0L : decisionTimes.getMaxValue());
    }

    private static boolean isCandidateApplied(String stage) {
        return "TRIAL_APPLIED".equals(stage)
                || "INCREASE_APPLIED".equals(stage)
                || "DECREASE_APPLIED".equals(stage)
                || "QUEUE_INCREASE_APPLIED".equals(stage)
                || "QUEUE_DECREASE_APPLIED".equals(stage);
    }

    private static boolean isAccepted(String stage) {
        return "TRIAL_ACCEPTED".equals(stage)
                || "INCREASE_ACCEPTED".equals(stage)
                || "DECREASE_ACCEPTED".equals(stage)
                || "QUEUE_INCREASE_APPLIED".equals(stage)
                || "QUEUE_DECREASE_APPLIED".equals(stage);
    }

    private static boolean isRejected(String stage) {
        return stage.startsWith("TRIAL_REJECTED")
                || "INCREASE_ROLLED_BACK".equals(stage)
                || "DECREASE_ROLLED_BACK".equals(stage);
    }
}
