package com.example.adaptt;

import com.example.adaptt.control.AdaptAction;
import com.example.adaptt.control.ControlDecision;
import com.example.adaptt.control.QueueHysteresisController;
import com.example.adaptt.metrics.LatencyWindowSnapshot;
import com.example.adaptt.metrics.PoolWindowSnapshot;
import com.example.adaptt.server.AdjustableWorkerPool;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueueHysteresisControllerTest {
    @Test
    void requiresConsecutiveThresholdWindowsAndUsesHysteresis() {
        try (AdjustableWorkerPool pool = new AdjustableWorkerPool(1, 1, 100, 100)) {
            QueueHysteresisController controller = new QueueHysteresisController(
                    pool, 1, 100, 5, 0, 5, 2);

            ControlDecision highPending = controller.onWindow(
                    latency(), poolSnapshot(1, 6));
            assertEquals("QUEUE_HIGH_PENDING", highPending.stage());
            assertEquals(AdaptAction.NONE, highPending.action());
            assertEquals(1, pool.configuredSize());

            ControlDecision increase = controller.onWindow(
                    latency(), poolSnapshot(1, 7));
            assertEquals("QUEUE_INCREASE_APPLIED", increase.stage());
            assertEquals(AdaptAction.INCREASE, increase.action());
            assertEquals(6, pool.configuredSize());

            ControlDecision bandHold = controller.onWindow(
                    latency(), poolSnapshot(6, 3));
            assertEquals("QUEUE_HYSTERESIS_HOLD", bandHold.stage());

            ControlDecision lowPending = controller.onWindow(
                    latency(), poolSnapshot(6, 0));
            assertEquals("QUEUE_LOW_PENDING", lowPending.stage());

            ControlDecision decrease = controller.onWindow(
                    latency(), poolSnapshot(6, 0));
            assertEquals("QUEUE_DECREASE_APPLIED", decrease.stage());
            assertEquals(AdaptAction.DECREASE, decrease.action());
            assertEquals(1, pool.configuredSize());
            assertEquals(2, decrease.telemetry().adjustmentCount());
            assertEquals(10, decrease.telemetry().totalVariation());
        }
    }

    @Test
    void respectsConfiguredUpperAndLowerBounds() {
        try (AdjustableWorkerPool pool = new AdjustableWorkerPool(10, 1, 10, 100)) {
            QueueHysteresisController controller = new QueueHysteresisController(
                    pool, 1, 10, 5, 0, 5, 1);

            ControlDecision upper = controller.onWindow(
                    latency(), poolSnapshot(10, 20));
            assertEquals("QUEUE_HIGH_BOUND_HOLD", upper.stage());
            assertEquals(10, pool.configuredSize());

            controller.onWindow(latency(), poolSnapshot(10, 0));
            assertEquals(5, pool.configuredSize());
            controller.onWindow(latency(), poolSnapshot(5, 0));
            assertEquals(1, pool.configuredSize());
            ControlDecision lower = controller.onWindow(
                    latency(), poolSnapshot(1, 0));
            assertEquals("QUEUE_LOW_BOUND_HOLD", lower.stage());
            assertEquals(1, pool.configuredSize());
        }
    }

    private static LatencyWindowSnapshot latency() {
        Instant end = Instant.now();
        return new LatencyWindowSnapshot(
                end.minusSeconds(10), end, 100, 0, 5, 10, 1, 12);
    }

    private static PoolWindowSnapshot poolSnapshot(
            int configuredSize,
            int queueSize) {
        return new PoolWindowSnapshot(
                configuredSize,
                configuredSize,
                configuredSize,
                queueSize,
                100,
                0);
    }
}
