package com.example.adaptt;

import com.example.adaptt.metrics.LatencyWindow;
import com.example.adaptt.metrics.LatencyWindowSnapshot;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyWindowTest {
    @Test
    void reportsMeanAndPercentileInMilliseconds() {
        LatencyWindow window = new LatencyWindow(10);
        window.recordSuccess(TimeUnit.MILLISECONDS.toNanos(1));
        window.recordSuccess(TimeUnit.MILLISECONDS.toNanos(2));
        window.recordSuccess(TimeUnit.MILLISECONDS.toNanos(3));
        window.recordFailure();

        LatencyWindowSnapshot snapshot = window.snapshotAndReset();

        assertEquals(3, snapshot.successfulRequests());
        assertEquals(1, snapshot.failedRequests());
        assertEquals(4, snapshot.totalRequests());
        assertEquals(2.0, snapshot.meanLatencyMs(), 0.01);
        assertEquals(3.0, snapshot.p95LatencyMs(), 0.01);
        assertEquals(3.0, snapshot.p99LatencyMs(), 0.01);
        assertEquals(3.0, snapshot.p999LatencyMs(), 0.01);
        assertTrue(snapshot.hasLatencySamples());
        assertTrue(snapshot.hasMergeableHistogram());
        assertEquals(3, snapshot.histogram().getTotalCount());
    }

    @Test
    void snapshotsAreNonOverlapping() {
        LatencyWindow window = new LatencyWindow(10);
        window.recordSuccess(TimeUnit.MILLISECONDS.toNanos(5));
        LatencyWindowSnapshot first = window.snapshotAndReset();

        LatencyWindowSnapshot second = window.snapshotAndReset();

        assertEquals(1, first.successfulRequests());
        assertEquals(0, second.successfulRequests());
        assertEquals(0, second.failedRequests());
        assertFalse(second.hasLatencySamples());
        assertFalse(second.hasMergeableHistogram());
        assertEquals(first.endedAt(), second.startedAt());
    }
}
