package com.example.adaptt.control;

import com.example.adaptt.config.AppConfig;
import com.example.adaptt.metrics.LatencyWindowSnapshot;
import com.example.adaptt.metrics.PoolWindowSnapshot;
import com.example.adaptt.server.AdjustableWorkerPool;

import java.util.Objects;

/**
 * Transparent online baseline that resizes from queue-length hysteresis only.
 * It deliberately performs no latency optimization or workload prediction.
 */
public final class QueueHysteresisController implements WindowController {
    private final AdjustableWorkerPool workerPool;
    private final int minimumPoolSize;
    private final int maximumPoolSize;
    private final int stepSize;
    private final int lowQueueThreshold;
    private final int highQueueThreshold;
    private final int consecutiveWindows;

    private int consecutiveHighWindows;
    private int consecutiveLowWindows;
    private long adjustmentCount;
    private long totalVariation;
    private long searchWindowCount;

    public QueueHysteresisController(
            AdjustableWorkerPool workerPool,
            int minimumPoolSize,
            int maximumPoolSize,
            int stepSize,
            int lowQueueThreshold,
            int highQueueThreshold,
            int consecutiveWindows) {
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool");
        if (minimumPoolSize < workerPool.minimumSize()
                || maximumPoolSize > workerPool.maximumSize()
                || maximumPoolSize < minimumPoolSize) {
            throw new IllegalArgumentException(
                    "Queue baseline pool range is outside worker-pool bounds");
        }
        if (workerPool.configuredSize() < minimumPoolSize
                || workerPool.configuredSize() > maximumPoolSize) {
            throw new IllegalArgumentException(
                    "Initial worker size is outside queue baseline bounds");
        }
        if (stepSize < 1) {
            throw new IllegalArgumentException("stepSize must be at least 1");
        }
        if (lowQueueThreshold < 0
                || highQueueThreshold <= lowQueueThreshold) {
            throw new IllegalArgumentException(
                    "Queue thresholds require 0 <= low < high");
        }
        if (consecutiveWindows < 1) {
            throw new IllegalArgumentException(
                    "consecutiveWindows must be at least 1");
        }
        this.minimumPoolSize = minimumPoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.stepSize = stepSize;
        this.lowQueueThreshold = lowQueueThreshold;
        this.highQueueThreshold = highQueueThreshold;
        this.consecutiveWindows = consecutiveWindows;
    }

    public static QueueHysteresisController from(
            AppConfig config,
            AdjustableWorkerPool workerPool) {
        Objects.requireNonNull(config, "config");
        return new QueueHysteresisController(
                workerPool,
                config.getInt("queueBaseline.minPoolSize"),
                config.getInt("queueBaseline.maxPoolSize"),
                config.getInt("queueBaseline.stepSize"),
                config.getInt("queueBaseline.lowQueueThreshold"),
                config.getInt("queueBaseline.highQueueThreshold"),
                config.getInt("queueBaseline.consecutiveWindows"));
    }

    @Override
    public synchronized ControlDecision onWindow(
            LatencyWindowSnapshot latency,
            PoolWindowSnapshot pool) {
        Objects.requireNonNull(latency, "latency");
        Objects.requireNonNull(pool, "pool");
        searchWindowCount++;

        int queueSize = pool.queueSize();
        int before = workerPool.configuredSize();
        if (queueSize > highQueueThreshold) {
            consecutiveHighWindows++;
            consecutiveLowWindows = 0;
            if (consecutiveHighWindows >= consecutiveWindows) {
                consecutiveHighWindows = 0;
                int after = Math.min(maximumPoolSize, before + stepSize);
                if (after > before) {
                    return resize(
                            "QUEUE_INCREASE_APPLIED",
                            AdaptAction.INCREASE,
                            before,
                            after,
                            latency.meanLatencyMs());
                }
                return decision(
                        "QUEUE_HIGH_BOUND_HOLD",
                        AdaptAction.NONE,
                        before,
                        before,
                        latency.meanLatencyMs());
            }
            return decision(
                    "QUEUE_HIGH_PENDING",
                    AdaptAction.NONE,
                    before,
                    before,
                    latency.meanLatencyMs());
        }

        if (queueSize <= lowQueueThreshold) {
            consecutiveLowWindows++;
            consecutiveHighWindows = 0;
            if (consecutiveLowWindows >= consecutiveWindows) {
                consecutiveLowWindows = 0;
                int after = Math.max(minimumPoolSize, before - stepSize);
                if (after < before) {
                    return resize(
                            "QUEUE_DECREASE_APPLIED",
                            AdaptAction.DECREASE,
                            before,
                            after,
                            latency.meanLatencyMs());
                }
                return decision(
                        "QUEUE_LOW_BOUND_HOLD",
                        AdaptAction.NONE,
                        before,
                        before,
                        latency.meanLatencyMs());
            }
            return decision(
                    "QUEUE_LOW_PENDING",
                    AdaptAction.NONE,
                    before,
                    before,
                    latency.meanLatencyMs());
        }

        consecutiveHighWindows = 0;
        consecutiveLowWindows = 0;
        return decision(
                "QUEUE_HYSTERESIS_HOLD",
                AdaptAction.NONE,
                before,
                before,
                latency.meanLatencyMs());
    }

    private ControlDecision resize(
            String stage,
            AdaptAction action,
            int before,
            int after,
            double observedMetricMs) {
        workerPool.resize(after);
        adjustmentCount++;
        totalVariation += Math.abs(after - before);
        return decision(stage, action, before, after, observedMetricMs);
    }

    private ControlDecision decision(
            String stage,
            AdaptAction action,
            int before,
            int after,
            double observedMetricMs) {
        int direction = switch (action) {
            case INCREASE -> 1;
            case DECREASE -> -1;
            case NONE -> 0;
        };
        ControllerTelemetry telemetry = new ControllerTelemetry(
                "QUEUE_HYSTERESIS",
                after,
                ControllerTelemetry.NOT_AVAILABLE,
                direction,
                stepSize,
                false,
                false,
                0,
                0,
                adjustmentCount,
                0L,
                0L,
                totalVariation,
                0L,
                searchWindowCount);
        return new ControlDecision(
                stage,
                action,
                before,
                after,
                0,
                0,
                observedMetricMs,
                ControlDecision.NOT_AVAILABLE,
                telemetry);
    }
}
