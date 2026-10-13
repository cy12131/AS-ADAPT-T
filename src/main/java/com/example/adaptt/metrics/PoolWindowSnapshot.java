package com.example.adaptt.metrics;

/** Worker-pool state that actually applied during a completed latency window. */
public record PoolWindowSnapshot(
        int configuredSize,
        int liveThreads,
        int activeThreads,
        int queueSize,
        long completedTasksDelta,
        long rejectedTasksDelta) {
}
