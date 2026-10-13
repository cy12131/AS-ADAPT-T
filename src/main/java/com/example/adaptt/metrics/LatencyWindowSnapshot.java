package com.example.adaptt.metrics;

import org.HdrHistogram.Histogram;

import java.time.Instant;

/** Immutable statistics for one non-overlapping measurement interval. */
public record LatencyWindowSnapshot(
        Instant startedAt,
        Instant endedAt,
        long successfulRequests,
        long failedRequests,
        double meanLatencyMs,
        double p99LatencyMs,
        double minLatencyMs,
        double maxLatencyMs,
        double p95LatencyMs,
        double p999LatencyMs,
        Histogram histogram) {

    /** Backward-compatible constructor for controller-focused unit tests. */
    public LatencyWindowSnapshot(
            Instant startedAt,
            Instant endedAt,
            long successfulRequests,
            long failedRequests,
            double meanLatencyMs,
            double p99LatencyMs,
            double minLatencyMs,
            double maxLatencyMs) {
        this(
                startedAt,
                endedAt,
                successfulRequests,
                failedRequests,
                meanLatencyMs,
                p99LatencyMs,
                minLatencyMs,
                maxLatencyMs,
                p99LatencyMs,
                p99LatencyMs,
                null);
    }

    public long totalRequests() {
        return successfulRequests + failedRequests;
    }

    public boolean hasLatencySamples() {
        return successfulRequests > 0;
    }

    public boolean hasMergeableHistogram() {
        return histogram != null && histogram.getTotalCount() > 0;
    }
}
