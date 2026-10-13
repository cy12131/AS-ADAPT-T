package com.example.adaptt.control;

import com.example.adaptt.config.AppConfig;
import com.example.adaptt.metrics.LatencyWindowSnapshot;
import com.example.adaptt.metrics.PoolWindowSnapshot;
import com.example.adaptt.server.AdjustableWorkerPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Objects;

/**
 * Stage-for-stage implementation of Algorithm 1 from the ADAPT-T paper.
 * One call to {@link #onWindow(LatencyWindowSnapshot)} represents one elapsed
 * T-second measurement stage.
 */
public final class AdaptTController implements WindowController {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdaptTController.class);

    private enum Phase {
        INITIAL_BASELINE,
        CHECK_ACTION,
        LOCKED_ACTION_WAIT,
        END_OF_ITERATION_WAIT
    }

    private final AdjustableWorkerPool workerPool;
    private final int stepSize;
    private final double latencyThresholdPercent;
    private final int incrementLockDuration;
    private final int decrementLockDuration;
    private final AdaptObjective objective;

    // Algorithm 1 line 1.
    private AdaptAction action = AdaptAction.INCREASE;
    private int decrementLock;
    private int incrementLock;

    private Phase phase = Phase.INITIAL_BASELINE;
    private int trialPoolBefore;
    private int trialPoolAfter;
    private double trialMetricBefore;

    public AdaptTController(
            AdjustableWorkerPool workerPool,
            int stepSize,
            double latencyThresholdPercent,
            int incrementLockDuration,
            int decrementLockDuration,
            AdaptObjective objective) {
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool");
        if (stepSize < 1) {
            throw new IllegalArgumentException("stepSize must be at least 1");
        }
        if (!Double.isFinite(latencyThresholdPercent)
                || latencyThresholdPercent < 0.0
                || latencyThresholdPercent > 100.0) {
            throw new IllegalArgumentException(
                    "latencyThresholdPercent must be between 0 and 100");
        }
        if (incrementLockDuration < 0 || decrementLockDuration < 0) {
            throw new IllegalArgumentException("Lock durations must not be negative");
        }
        this.stepSize = stepSize;
        this.latencyThresholdPercent = latencyThresholdPercent;
        this.incrementLockDuration = incrementLockDuration;
        this.decrementLockDuration = decrementLockDuration;
        this.objective = Objects.requireNonNull(objective, "objective");
    }

    public static AdaptTController from(
            AppConfig config,
            AdjustableWorkerPool workerPool) {
        Objects.requireNonNull(config, "config");
        return new AdaptTController(
                workerPool,
                config.getInt("adapt.stepSize"),
                config.getDouble("adapt.latencyThresholdPercent"),
                config.getInt("adapt.incrementLockStages"),
                config.getInt("adapt.decrementLockStages"),
                AdaptObjective.valueOf(
                        config.getString("adapt.objective")
                                .toUpperCase(Locale.ROOT)));
    }

    public synchronized ControlDecision onWindow(LatencyWindowSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        double metric = objective.valueFrom(snapshot);

        if (!snapshot.hasLatencySamples() || !Double.isFinite(metric) || metric <= 0.0) {
            return decision(
                    "EMPTY_WINDOW_DEFERRED",
                    action,
                    workerPool.configuredSize(),
                    workerPool.configuredSize(),
                    metric,
                    ControlDecision.NOT_AVAILABLE);
        }

        ControlDecision result = switch (phase) {
            case INITIAL_BASELINE -> startCurrentAction(metric);
            case CHECK_ACTION -> checkCurrentAction(metric);
            case LOCKED_ACTION_WAIT -> finishLockedActionWait(metric);
            case END_OF_ITERATION_WAIT -> finishIterationAndStartNext(metric);
        };
        log(result);
        return result;
    }

    /** Pool statistics are not used by the paper's original Algorithm 1. */
    @Override
    public ControlDecision onWindow(
            LatencyWindowSnapshot latency,
            PoolWindowSnapshot pool) {
        Objects.requireNonNull(pool, "pool");
        return onWindow(latency);
    }

    /** Algorithm 1 lines 3-6 or 13-16. */
    private ControlDecision startCurrentAction(double metricBefore) {
        int relevantLock = action == AdaptAction.INCREASE
                ? incrementLock
                : decrementLock;
        int currentSize = workerPool.configuredSize();

        if (relevantLock > 0) {
            phase = Phase.LOCKED_ACTION_WAIT;
            return decision(
                    action + "_LOCKED_WAIT",
                    action,
                    currentSize,
                    currentSize,
                    metricBefore,
                    ControlDecision.NOT_AVAILABLE);
        }

        int requestedSize = action == AdaptAction.INCREASE
                ? currentSize + stepSize
                : currentSize - stepSize;
        int boundedSize = Math.max(workerPool.minimumSize(),
                Math.min(workerPool.maximumSize(), requestedSize));

        // The paper does not define values outside legal pool bounds. Treat a
        // bound hit as a temporarily locked direction and preserve its timing.
        if (boundedSize == currentSize) {
            setRelevantLockDuration();
            phase = Phase.LOCKED_ACTION_WAIT;
            return decision(
                    action + "_BOUND_LOCKED_WAIT",
                    action,
                    currentSize,
                    currentSize,
                    metricBefore,
                    ControlDecision.NOT_AVAILABLE);
        }

        trialPoolBefore = currentSize;
        trialPoolAfter = boundedSize;
        trialMetricBefore = metricBefore;
        workerPool.resize(trialPoolAfter);
        phase = Phase.CHECK_ACTION;

        return decision(
                action + "_APPLIED",
                action,
                trialPoolBefore,
                trialPoolAfter,
                metricBefore,
                ControlDecision.NOT_AVAILABLE);
    }

    /** Algorithm 1 lines 7-9 or 17-19. */
    private ControlDecision checkCurrentAction(double metricAfter) {
        double improvementPercent = percentageLatencyDecrement(
                trialMetricBefore, metricAfter);
        int poolBeforeCheck = workerPool.configuredSize();
        String stage;

        if (improvementPercent < latencyThresholdPercent) {
            workerPool.resize(trialPoolBefore);
            setRelevantLockDuration();
            stage = action + "_ROLLED_BACK";
        } else {
            stage = action + "_ACCEPTED";
        }

        AdaptAction completedAction = action;
        action = action.opposite(); // Algorithm 1 lines 12 or 22.
        phase = Phase.END_OF_ITERATION_WAIT; // Algorithm 1 line 23.

        return decision(
                stage,
                completedAction,
                poolBeforeCheck,
                workerPool.configuredSize(),
                metricAfter,
                improvementPercent);
    }

    /** Algorithm 1 lines 10-12 or 20-22. */
    private ControlDecision finishLockedActionWait(double metric) {
        AdaptAction lockedAction = action;
        action = action.opposite();
        phase = Phase.END_OF_ITERATION_WAIT;
        int size = workerPool.configuredSize();
        return decision(
                lockedAction + "_LOCKED_WAIT_COMPLETE",
                lockedAction,
                size,
                size,
                metric,
                ControlDecision.NOT_AVAILABLE);
    }

    /** Algorithm 1 lines 23-25, followed immediately by the next iteration. */
    private ControlDecision finishIterationAndStartNext(double metric) {
        incrementLock = Math.max(0, incrementLock - 2);
        decrementLock = Math.max(0, decrementLock - 2);
        return startCurrentAction(metric);
    }

    private void setRelevantLockDuration() {
        if (action == AdaptAction.INCREASE) {
            incrementLock = incrementLockDuration;
        } else {
            decrementLock = decrementLockDuration;
        }
    }

    static double percentageLatencyDecrement(double before, double after) {
        return ((before - after) / before) * 100.0;
    }

    private ControlDecision decision(
            String stage,
            AdaptAction decisionAction,
            int poolBefore,
            int poolAfter,
            double metric,
            double improvement) {
        return new ControlDecision(
                stage,
                decisionAction,
                poolBefore,
                poolAfter,
                incrementLock,
                decrementLock,
                metric,
                improvement);
    }

    private static void log(ControlDecision decision) {
        LOGGER.info(
                "ADAPT-T stage={} action={} pool={}->{} metricMs={} improvementPct={} Linc={} Ldec={}",
                decision.stage(),
                decision.action(),
                decision.poolBefore(),
                decision.poolAfter(),
                format(decision.observedMetricMs()),
                format(decision.improvementPercent()),
                decision.incrementLock(),
                decision.decrementLock());
    }

    private static String format(double value) {
        return Double.isNaN(value)
                ? "n/a"
                : String.format(Locale.ROOT, "%.3f", value);
    }

    public AdaptAction nextAction() {
        return action;
    }

    public int incrementLock() {
        return incrementLock;
    }

    public int decrementLock() {
        return decrementLock;
    }
}
