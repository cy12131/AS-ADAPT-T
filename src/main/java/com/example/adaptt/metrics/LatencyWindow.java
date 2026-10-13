package com.example.adaptt.metrics;

import com.example.adaptt.config.AppConfig;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Concurrent latency recorder that emits strict, non-overlapping windows. */
public final class LatencyWindow {
    private static final int SIGNIFICANT_DIGITS = 3;

    private final long highestTrackableNanos;
    private final Recorder recorder;
    private final AtomicLong failedRequests = new AtomicLong();

    private Instant windowStartedAt;

    public LatencyWindow(long highestTrackableLatencySeconds) {
        if (highestTrackableLatencySeconds < 1) {
            throw new IllegalArgumentException(
                    "highestTrackableLatencySeconds must be at least 1");
        }
        this.highestTrackableNanos = TimeUnit.SECONDS.toNanos(
                highestTrackableLatencySeconds);
        this.recorder = new Recorder(
                1L,
                highestTrackableNanos,
                SIGNIFICANT_DIGITS);
        this.windowStartedAt = Instant.now();
    }

    public static LatencyWindow from(AppConfig config) {
        Objects.requireNonNull(config, "config");
        return new LatencyWindow(
                config.getLong("metrics.highestTrackableLatencySeconds"));
    }

    public void recordSuccess(long latencyNanos) {
        long boundedLatency = Math.max(1L,
                Math.min(latencyNanos, highestTrackableNanos));
        recorder.recordValue(boundedLatency);
    }

    /** Failures are counted but excluded from optimization latency. */
    public void recordFailure() {
        failedRequests.incrementAndGet();
    }

    /** Returns and resets one interval while concurrent request recording continues. */
    public synchronized LatencyWindowSnapshot snapshotAndReset() {
        Instant endedAt = Instant.now();
        Histogram histogram = recorder.getIntervalHistogram();
        long failures = failedRequests.getAndSet(0L);
        long samples = histogram.getTotalCount();

        LatencyWindowSnapshot snapshot = new LatencyWindowSnapshot(
                windowStartedAt,
                endedAt,
                samples,
                failures,
                samples == 0 ? 0.0 : nanosToMillis(histogram.getMean()),
                samples == 0 ? 0.0
                        : nanosToMillis(histogram.getValueAtPercentile(99.0)),
                samples == 0 ? 0.0
                        : nanosToMillis(histogram.getMinValue()),
                samples == 0 ? 0.0
                        : nanosToMillis(histogram.getMaxValue()),
                samples == 0 ? 0.0
                        : nanosToMillis(histogram.getValueAtPercentile(95.0)),
                samples == 0 ? 0.0
                        : nanosToMillis(histogram.getValueAtPercentile(99.9)),
                histogram);

        windowStartedAt = endedAt;
        return snapshot;
    }

    private static double nanosToMillis(double nanos) {
        return nanos / 1_000_000.0;
    }
}
