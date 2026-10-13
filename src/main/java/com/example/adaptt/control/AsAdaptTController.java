package com.example.adaptt.control;

import com.example.adaptt.config.AppConfig;
import com.example.adaptt.metrics.LatencyWindowSnapshot;
import com.example.adaptt.metrics.PoolWindowSnapshot;
import com.example.adaptt.server.AdjustableWorkerPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Adaptive-step ADAPT-T controller with direction memory, non-inferiority
 * guards, bidirectional convergence, and stable-load change detection.
 */
public final class AsAdaptTController implements WindowController {
    private static final Logger LOGGER = LoggerFactory.getLogger(AsAdaptTController.class);

    private final AdjustableWorkerPool workerPool;
    private final AsAdaptTConfig config;

    private AsAdaptTState state = AsAdaptTState.INITIAL_BASELINE;
    private int bestPoolSize;
    private int trialPoolSize = ControllerTelemetry.NOT_AVAILABLE;
    private int direction = 1;
    private int stepSize;
    private boolean increaseFailed;
    private boolean decreaseFailed;
    private boolean recoverySearchActive;

    private double bestMeanLatencyMs;
    private double bestP99LatencyMs;
    private double bestThroughputRps;

    private int referenceWindowCount;
    private double referenceLatencySum;
    private double referenceP99Sum;
    private double referenceThroughputSum;
    private double referenceQueueSum;
    private double stableMeanLatencyMs;
    private double stableP99LatencyMs;
    private double stableThroughputRps;
    private double stableQueueSize;

    private int changeDetectionCount;
    private double changeLatencySum;
    private double changeP99Sum;
    private double changeThroughputSum;
    private double changeQueueSum;
    private int previousQueueSize;

    private long adjustmentCount;
    private long rollbackCount;
    private long directionReversalCount;
    private long totalVariation;
    private long stableWindowCount;
    private long searchWindowCount;

    public AsAdaptTController(
            AdjustableWorkerPool workerPool,
            AsAdaptTConfig config) {
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool");
        this.config = Objects.requireNonNull(config, "config");
        if (config.minimumPoolSize() < workerPool.minimumSize()
                || config.maximumPoolSize() > workerPool.maximumSize()) {
            throw new IllegalArgumentException(
                    "AS-ADAPT-T pool bounds must be inside worker pool bounds");
        }
        if (workerPool.configuredSize() != config.minimumPoolSize()) {
            throw new IllegalArgumentException(
                    "AS-ADAPT-T must start at its configured minimum pool size");
        }
        this.bestPoolSize = workerPool.configuredSize();
        this.stepSize = config.maximumStepSize();
    }

    public static AsAdaptTController from(
            AppConfig config,
            AdjustableWorkerPool workerPool) {
        return new AsAdaptTController(workerPool, AsAdaptTConfig.from(config));
    }

    @Override
    public synchronized ControlDecision onWindow(
            LatencyWindowSnapshot latency,
            PoolWindowSnapshot pool) {
        Objects.requireNonNull(latency, "latency");
        Objects.requireNonNull(pool, "pool");

        WindowMetrics metrics = WindowMetrics.from(latency, pool);
        if (!metrics.valid()) {
            if (state == AsAdaptTState.STABLE) {
                resetChangeDetection();
                previousQueueSize = metrics.queueSize();
            }
            return decision(
                    "EMPTY_WINDOW_DEFERRED",
                    actionFor(direction),
                    pool.configuredSize(),
                    metrics.meanLatencyMs(),
                    ControlDecision.NOT_AVAILABLE);
        }

        ControlDecision result = switch (state) {
            case INITIAL_BASELINE -> establishInitialBaseline(metrics, pool);
            case SEARCH -> beginTrial(metrics, pool);
            case TRIAL -> evaluateTrial(metrics, pool);
            case REFERENCE_COLLECTION -> collectStableReference(metrics, pool);
            case RECOVERY_BASELINE_COLLECTION ->
                    collectRecoveryBaseline(metrics, pool);
            case STABLE -> monitorStableLoad(metrics, pool);
        };
        log(result);
        return result;
    }

    private ControlDecision establishInitialBaseline(
            WindowMetrics metrics,
            PoolWindowSnapshot pool) {
        bestPoolSize = workerPool.configuredSize();
        updateBestMetrics(metrics);
        state = AsAdaptTState.SEARCH;
        searchWindowCount++;
        return decision(
                "INITIAL_BASELINE_ESTABLISHED",
                actionFor(direction),
                pool.configuredSize(),
                metrics.meanLatencyMs(),
                ControlDecision.NOT_AVAILABLE);
    }

    private ControlDecision beginTrial(
            WindowMetrics metrics,
            PoolWindowSnapshot pool) {
        searchWindowCount++;
        int poolBefore = workerPool.configuredSize();
        int candidate = candidate(direction, stepSize);

        if (candidate == bestPoolSize) {
            markDirectionUnavailableIfMinimumStep();
            int opposite = -direction;
            int oppositeCandidate = candidate(opposite, stepSize);
            if (oppositeCandidate != bestPoolSize
                    && !directionFailed(opposite)) {
                setDirection(opposite);
                candidate = oppositeCandidate;
            } else {
                markDirectionUnavailable(opposite);
                boolean collectingReference = beginReferenceCollection();
                return decision(
                        collectingReference
                                ? "SEARCH_CONVERGED_AT_BOUND"
                                : "ABLATION_SEARCH_RESTARTED_AT_BOUND",
                        actionFor(direction),
                        poolBefore,
                        metrics.meanLatencyMs(),
                        ControlDecision.NOT_AVAILABLE);
            }
        }

        trialPoolSize = candidate;
        resizeTracked(candidate);
        state = AsAdaptTState.TRIAL;
        return decision(
                "TRIAL_APPLIED",
                actionFor(direction),
                poolBefore,
                metrics.meanLatencyMs(),
                ControlDecision.NOT_AVAILABLE);
    }

    private ControlDecision evaluateTrial(
            WindowMetrics metrics,
            PoolWindowSnapshot pool) {
        searchWindowCount++;
        int poolBefore = workerPool.configuredSize();
        int attemptedDirection = direction;
        double meanImprovement = percentDecrease(
                bestMeanLatencyMs, metrics.meanLatencyMs());
        double throughputDegradation = percentDecrease(
                bestThroughputRps, metrics.throughputRps());
        double p99Degradation = percentIncrease(
                bestP99LatencyMs, metrics.p99LatencyMs());

        boolean accepted = meanImprovement >= config.meanImprovementPercent()
                && throughputDegradation
                        <= config.maximumThroughputDegradationPercent()
                && p99Degradation <= config.maximumP99DegradationPercent();

        if (accepted) {
            bestPoolSize = trialPoolSize;
            updateBestMetrics(metrics);
            trialPoolSize = ControllerTelemetry.NOT_AVAILABLE;
            increaseFailed = false;
            decreaseFailed = false;
            state = AsAdaptTState.SEARCH;
            return decision(
                    "TRIAL_ACCEPTED",
                    actionFor(attemptedDirection),
                    poolBefore,
                    metrics.meanLatencyMs(),
                    meanImprovement);
        }

        resizeTracked(bestPoolSize);
        rollbackCount++;
        trialPoolSize = ControllerTelemetry.NOT_AVAILABLE;

        if (stepSize > config.minimumStepSize()) {
            stepSize = Math.max(
                    config.minimumStepSize(),
                    (stepSize + 1) / 2);

            if (recoverySearchActive
                    && candidate(direction, stepSize) != bestPoolSize) {
                state = AsAdaptTState.SEARCH;
                return decision(
                        "TRIAL_REJECTED_RECOVERY_STEP_REDUCED",
                        actionFor(attemptedDirection),
                        poolBefore,
                        metrics.meanLatencyMs(),
                        meanImprovement);
            }

            int opposite = -direction;
            if (candidate(opposite, stepSize) != bestPoolSize) {
                setDirection(opposite);
            } else if (candidate(direction, stepSize) == bestPoolSize) {
                markDirectionUnavailable(opposite);
                markDirectionUnavailable(direction);
                boolean collectingReference = beginReferenceCollection();
                return decision(
                        collectingReference
                                ? "TRIAL_REJECTED_CONVERGED_AT_BOUND"
                                : "ABLATION_SEARCH_RESTARTED_AT_BOUND",
                        actionFor(attemptedDirection),
                        poolBefore,
                        metrics.meanLatencyMs(),
                        meanImprovement);
            }
            state = AsAdaptTState.SEARCH;
            return decision(
                    "TRIAL_REJECTED_STEP_REDUCED",
                    actionFor(attemptedDirection),
                    poolBefore,
                    metrics.meanLatencyMs(),
                    meanImprovement);
        }

        markDirectionFailed(attemptedDirection);
        int opposite = -attemptedDirection;
        if (!directionFailed(opposite)
                && candidate(opposite, stepSize) != bestPoolSize) {
            setDirection(opposite);
            state = AsAdaptTState.SEARCH;
            return decision(
                    "TRIAL_REJECTED_DIRECTION_REVERSED",
                    actionFor(attemptedDirection),
                    poolBefore,
                    metrics.meanLatencyMs(),
                    meanImprovement);
        }

        markDirectionUnavailable(opposite);
        boolean collectingReference = beginReferenceCollection();
        return decision(
                collectingReference
                        ? "TRIAL_REJECTED_CONVERGED"
                        : "ABLATION_SEARCH_RESTARTED",
                actionFor(attemptedDirection),
                poolBefore,
                metrics.meanLatencyMs(),
                meanImprovement);
    }

    private ControlDecision collectStableReference(
            WindowMetrics metrics,
            PoolWindowSnapshot pool) {
        referenceLatencySum += metrics.meanLatencyMs();
        referenceP99Sum += metrics.p99LatencyMs();
        referenceThroughputSum += metrics.throughputRps();
        referenceQueueSum += metrics.queueSize();
        referenceWindowCount++;

        String stage = "REFERENCE_WINDOW_COLLECTED";
        if (referenceWindowCount >= config.referenceWindows()) {
            stableMeanLatencyMs = referenceLatencySum / referenceWindowCount;
            stableP99LatencyMs = referenceP99Sum / referenceWindowCount;
            stableThroughputRps = referenceThroughputSum / referenceWindowCount;
            stableQueueSize = referenceQueueSum / referenceWindowCount;
            previousQueueSize = metrics.queueSize();
            resetChangeDetection();
            recoverySearchActive = false;
            state = AsAdaptTState.STABLE;
            stage = "STABLE_REFERENCE_ESTABLISHED";
        }

        return decision(
                stage,
                actionFor(direction),
                pool.configuredSize(),
                metrics.meanLatencyMs(),
                ControlDecision.NOT_AVAILABLE);
    }

    private ControlDecision monitorStableLoad(
            WindowMetrics metrics,
            PoolWindowSnapshot pool) {
        stableWindowCount++;
        boolean strongChange = metrics.meanLatencyMs()
                    > multiplyPercent(stableMeanLatencyMs,
                            config.meanChangePercent())
                || metrics.p99LatencyMs()
                    > multiplyPercent(stableP99LatencyMs,
                            config.p99ChangePercent())
                || metrics.throughputRps()
                    < reducePercent(stableThroughputRps,
                            config.throughputChangePercent());

        double queueThreshold = Math.max(
                multiplyPercent(stableQueueSize, config.queueGrowthPercent()),
                stableQueueSize + 1.0);
        boolean queueAssistedChange = metrics.queueSize() > queueThreshold
                && metrics.queueSize() > previousQueueSize
                && (metrics.meanLatencyMs()
                        > multiplyPercent(stableMeanLatencyMs,
                                config.queueAssistedMeanChangePercent())
                    || metrics.throughputRps()
                        < reducePercent(stableThroughputRps,
                                config.queueAssistedThroughputChangePercent()));

        previousQueueSize = metrics.queueSize();
        if (strongChange || queueAssistedChange) {
            changeDetectionCount++;
            changeLatencySum += metrics.meanLatencyMs();
            changeP99Sum += metrics.p99LatencyMs();
            changeThroughputSum += metrics.throughputRps();
            changeQueueSum += metrics.queueSize();
        } else {
            resetChangeDetection();
        }

        if (changeDetectionCount < config.changeDetectionWindows()) {
            return decision(
                    changeDetectionCount == 0
                            ? "STABLE_HELD"
                            : "LOAD_CHANGE_PENDING",
                    actionFor(direction),
                    pool.configuredSize(),
                    metrics.meanLatencyMs(),
                    ControlDecision.NOT_AVAILABLE);
        }

        double changedMeanLatencyMs = changeLatencySum / changeDetectionCount;
        double changedP99LatencyMs = changeP99Sum / changeDetectionCount;
        double changedThroughputRps = changeThroughputSum / changeDetectionCount;
        double changedQueueSize = changeQueueSum / changeDetectionCount;
        int recoveryDirection = recoveryDirection(
                changedMeanLatencyMs,
                changedP99LatencyMs,
                changedThroughputRps,
                changedQueueSize);

        bestPoolSize = workerPool.configuredSize();
        stepSize = config.maximumStepSize();
        increaseFailed = false;
        decreaseFailed = false;
        resetReferenceCollection();
        resetChangeDetection();
        setDirection(recoveryDirection);
        recoverySearchActive = true;
        state = AsAdaptTState.RECOVERY_BASELINE_COLLECTION;

        return decision(
                "LOAD_CHANGE_DETECTED",
                actionFor(direction),
                pool.configuredSize(),
                metrics.meanLatencyMs(),
                ControlDecision.NOT_AVAILABLE);
    }

    private ControlDecision collectRecoveryBaseline(
            WindowMetrics metrics,
            PoolWindowSnapshot pool) {
        referenceLatencySum += metrics.meanLatencyMs();
        referenceP99Sum += metrics.p99LatencyMs();
        referenceThroughputSum += metrics.throughputRps();
        referenceQueueSum += metrics.queueSize();
        referenceWindowCount++;

        String stage = "LOAD_CHANGE_BASELINE_COLLECTED";
        if (referenceWindowCount >= config.referenceWindows()) {
            bestPoolSize = workerPool.configuredSize();
            bestMeanLatencyMs = referenceLatencySum / referenceWindowCount;
            bestP99LatencyMs = referenceP99Sum / referenceWindowCount;
            bestThroughputRps = referenceThroughputSum / referenceWindowCount;
            previousQueueSize = metrics.queueSize();
            state = AsAdaptTState.SEARCH;
            stage = "LOAD_CHANGE_BASELINE_ESTABLISHED";
        }

        return decision(
                stage,
                actionFor(direction),
                pool.configuredSize(),
                metrics.meanLatencyMs(),
                ControlDecision.NOT_AVAILABLE);
    }

    private int recoveryDirection(
            double changedMeanLatencyMs,
            double changedP99LatencyMs,
            double changedThroughputRps,
            double changedQueueSize) {
        if (bestPoolSize <= config.minimumPoolSize()) {
            return 1;
        }
        if (bestPoolSize >= config.maximumPoolSize()) {
            return -1;
        }

        boolean pressureIncreased = changedMeanLatencyMs > stableMeanLatencyMs
                || changedP99LatencyMs > stableP99LatencyMs
                || changedQueueSize > stableQueueSize;
        boolean lowerDemand = changedThroughputRps < stableThroughputRps
                && changedMeanLatencyMs < stableMeanLatencyMs
                && changedP99LatencyMs < stableP99LatencyMs
                && changedQueueSize < stableQueueSize;
        return !pressureIncreased && lowerDemand ? -1 : 1;
    }

    private void updateBestMetrics(WindowMetrics metrics) {
        bestMeanLatencyMs = metrics.meanLatencyMs();
        bestP99LatencyMs = metrics.p99LatencyMs();
        bestThroughputRps = metrics.throughputRps();
    }

    private boolean beginReferenceCollection() {
        trialPoolSize = ControllerTelemetry.NOT_AVAILABLE;
        resetReferenceCollection();
        if (!config.stableHoldingEnabled()) {
            stepSize = config.maximumStepSize();
            increaseFailed = false;
            decreaseFailed = false;
            recoverySearchActive = false;
            state = AsAdaptTState.SEARCH;
            return false;
        }
        state = AsAdaptTState.REFERENCE_COLLECTION;
        return true;
    }

    private void resetReferenceCollection() {
        referenceWindowCount = 0;
        referenceLatencySum = 0.0;
        referenceP99Sum = 0.0;
        referenceThroughputSum = 0.0;
        referenceQueueSum = 0.0;
        stableMeanLatencyMs = 0.0;
        stableP99LatencyMs = 0.0;
        stableThroughputRps = 0.0;
        stableQueueSize = 0.0;
    }

    private void resetChangeDetection() {
        changeDetectionCount = 0;
        changeLatencySum = 0.0;
        changeP99Sum = 0.0;
        changeThroughputSum = 0.0;
        changeQueueSum = 0.0;
    }

    private int candidate(int requestedDirection, int requestedStep) {
        long raw = (long) bestPoolSize
                + (long) requestedDirection * requestedStep;
        return (int) Math.max(
                config.minimumPoolSize(),
                Math.min(config.maximumPoolSize(), raw));
    }

    private void resizeTracked(int newSize) {
        int oldSize = workerPool.configuredSize();
        if (oldSize == newSize) {
            return;
        }
        workerPool.resize(newSize);
        adjustmentCount++;
        totalVariation += Math.abs((long) newSize - oldSize);
    }

    private void setDirection(int newDirection) {
        if (newDirection != 1 && newDirection != -1) {
            throw new IllegalArgumentException("direction must be +1 or -1");
        }
        if (direction != newDirection) {
            direction = newDirection;
            directionReversalCount++;
        }
    }

    private void markDirectionUnavailableIfMinimumStep() {
        if (stepSize == config.minimumStepSize()) {
            markDirectionUnavailable(direction);
        }
    }

    private void markDirectionUnavailable(int unavailableDirection) {
        if (unavailableDirection > 0) {
            increaseFailed = true;
        } else {
            decreaseFailed = true;
        }
    }

    private void markDirectionFailed(int failedDirection) {
        markDirectionUnavailable(failedDirection);
    }

    private boolean directionFailed(int checkedDirection) {
        return checkedDirection > 0 ? increaseFailed : decreaseFailed;
    }

    private ControlDecision decision(
            String stage,
            AdaptAction action,
            int poolBefore,
            double observedMeanLatency,
            double meanImprovement) {
        return new ControlDecision(
                stage,
                action,
                poolBefore,
                workerPool.configuredSize(),
                0,
                0,
                observedMeanLatency,
                meanImprovement,
                telemetry());
    }

    private ControllerTelemetry telemetry() {
        return new ControllerTelemetry(
                state.name(),
                bestPoolSize,
                trialPoolSize,
                direction,
                stepSize,
                increaseFailed,
                decreaseFailed,
                referenceWindowCount,
                changeDetectionCount,
                adjustmentCount,
                rollbackCount,
                directionReversalCount,
                totalVariation,
                stableWindowCount,
                searchWindowCount);
    }

    private static AdaptAction actionFor(int searchDirection) {
        return searchDirection > 0
                ? AdaptAction.INCREASE
                : AdaptAction.DECREASE;
    }

    private static double percentDecrease(double baseline, double value) {
        return ((baseline - value) / baseline) * 100.0;
    }

    private static double percentIncrease(double baseline, double value) {
        return ((value - baseline) / baseline) * 100.0;
    }

    private static double multiplyPercent(double value, double percent) {
        return value * (1.0 + percent / 100.0);
    }

    private static double reducePercent(double value, double percent) {
        return value * (1.0 - percent / 100.0);
    }

    private static void log(ControlDecision decision) {
        ControllerTelemetry telemetry = decision.telemetry();
        LOGGER.info(
                "AS-ADAPT-T stage={} state={} action={} pool={}->{} best={} trial={} step={} meanMs={} improvementPct={} incFailed={} decFailed={}",
                decision.stage(),
                telemetry.state(),
                decision.action(),
                decision.poolBefore(),
                decision.poolAfter(),
                telemetry.bestPoolSize(),
                telemetry.trialPoolSize(),
                telemetry.stepSize(),
                format(decision.observedMetricMs()),
                format(decision.improvementPercent()),
                telemetry.increaseFailed(),
                telemetry.decreaseFailed());
    }

    private static String format(double value) {
        return Double.isNaN(value)
                ? "n/a"
                : String.format(Locale.ROOT, "%.3f", value);
    }

    public AsAdaptTState state() {
        return state;
    }

    public int bestPoolSize() {
        return bestPoolSize;
    }

    public int direction() {
        return direction;
    }

    public int stepSize() {
        return stepSize;
    }

    public boolean increaseFailed() {
        return increaseFailed;
    }

    public boolean decreaseFailed() {
        return decreaseFailed;
    }

    private record WindowMetrics(
            boolean valid,
            double meanLatencyMs,
            double p99LatencyMs,
            double throughputRps,
            int queueSize) {

        private static WindowMetrics from(
                LatencyWindowSnapshot latency,
                PoolWindowSnapshot pool) {
            double seconds = Duration.between(
                    latency.startedAt(), latency.endedAt()).toNanos()
                    / 1_000_000_000.0;
            double throughput = seconds > 0.0
                    ? latency.successfulRequests() / seconds
                    : 0.0;
            boolean valid = latency.hasLatencySamples()
                    && seconds > 0.0
                    && Double.isFinite(latency.meanLatencyMs())
                    && latency.meanLatencyMs() > 0.0
                    && Double.isFinite(latency.p99LatencyMs())
                    && latency.p99LatencyMs() > 0.0
                    && Double.isFinite(throughput)
                    && throughput > 0.0;
            return new WindowMetrics(
                    valid,
                    latency.meanLatencyMs(),
                    latency.p99LatencyMs(),
                    throughput,
                    pool.queueSize());
        }
    }
}
