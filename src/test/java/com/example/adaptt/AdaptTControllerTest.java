package com.example.adaptt;

import com.example.adaptt.control.AdaptAction;
import com.example.adaptt.control.AdaptObjective;
import com.example.adaptt.control.AdaptTController;
import com.example.adaptt.control.ControlDecision;
import com.example.adaptt.metrics.LatencyWindowSnapshot;
import com.example.adaptt.server.AdjustableWorkerPool;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptTControllerTest {
    @Test
    void followsIncreaseCheckWaitDecreaseCheckAndRollbackStages() {
        try (AdjustableWorkerPool pool = new AdjustableWorkerPool(1, 1, 101, 10)) {
            AdaptTController controller = controller(pool, AdaptObjective.MEAN);

            ControlDecision increase = controller.onWindow(window(100.0, 200.0));
            assertEquals("INCREASE_APPLIED", increase.stage());
            assertEquals(11, pool.configuredSize());

            ControlDecision accepted = controller.onWindow(window(90.0, 190.0));
            assertEquals("INCREASE_ACCEPTED", accepted.stage());
            assertEquals(10.0, accepted.improvementPercent(), 0.0001);
            assertEquals(11, pool.configuredSize());
            assertEquals(AdaptAction.DECREASE, controller.nextAction());

            ControlDecision decrease = controller.onWindow(window(90.0, 190.0));
            assertEquals("DECREASE_APPLIED", decrease.stage());
            assertEquals(1, pool.configuredSize());

            ControlDecision rolledBack = controller.onWindow(window(95.0, 195.0));
            assertEquals("DECREASE_ROLLED_BACK", rolledBack.stage());
            assertEquals(11, pool.configuredSize());
            assertEquals(6, controller.decrementLock());
            assertEquals(AdaptAction.INCREASE, controller.nextAction());
        }
    }

    @Test
    void decrementsLocksByTwoAfterMandatoryEndWait() {
        try (AdjustableWorkerPool pool = new AdjustableWorkerPool(1, 1, 101, 10)) {
            AdaptTController controller = controller(pool, AdaptObjective.MEAN);

            controller.onWindow(window(100.0, 100.0)); // increase 1 -> 11
            controller.onWindow(window(100.0, 100.0)); // fail, Linc=6, rollback

            ControlDecision next = controller.onWindow(window(100.0, 100.0));

            assertEquals("DECREASE_BOUND_LOCKED_WAIT", next.stage());
            assertEquals(4, controller.incrementLock());
            assertEquals(6, controller.decrementLock());
        }
    }

    @Test
    void defersEmptyWindowsWithoutAdvancingAlgorithm() {
        try (AdjustableWorkerPool pool = new AdjustableWorkerPool(1, 1, 101, 10)) {
            AdaptTController controller = controller(pool, AdaptObjective.MEAN);

            ControlDecision empty = controller.onWindow(emptyWindow());
            assertEquals("EMPTY_WINDOW_DEFERRED", empty.stage());
            assertEquals(1, pool.configuredSize());
            assertEquals(AdaptAction.INCREASE, controller.nextAction());

            ControlDecision firstRealWindow = controller.onWindow(window(100.0, 200.0));
            assertEquals("INCREASE_APPLIED", firstRealWindow.stage());
            assertEquals(11, pool.configuredSize());
        }
    }

    @Test
    void usesP99WhenConfigured() {
        try (AdjustableWorkerPool pool = new AdjustableWorkerPool(1, 1, 101, 10)) {
            AdaptTController controller = controller(pool, AdaptObjective.P99);

            controller.onWindow(window(100.0, 200.0));
            ControlDecision result = controller.onWindow(window(99.0, 180.0));

            assertEquals("INCREASE_ACCEPTED", result.stage());
            assertEquals(10.0, result.improvementPercent(), 0.0001);
            assertTrue(result.observedMetricMs() == 180.0);
        }
    }

    private static AdaptTController controller(
            AdjustableWorkerPool pool,
            AdaptObjective objective) {
        return new AdaptTController(pool, 10, 5.0, 6, 6, objective);
    }

    private static LatencyWindowSnapshot window(double mean, double p99) {
        Instant now = Instant.now();
        return new LatencyWindowSnapshot(
                now.minusSeconds(10), now, 100, 0, mean, p99, mean, p99);
    }

    private static LatencyWindowSnapshot emptyWindow() {
        Instant now = Instant.now();
        return new LatencyWindowSnapshot(
                now.minusSeconds(10), now, 0, 0, 0.0, 0.0, 0.0, 0.0);
    }
}
