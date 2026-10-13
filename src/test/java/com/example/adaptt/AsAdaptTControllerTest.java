package com.example.adaptt;

import com.example.adaptt.control.AsAdaptTConfig;
import com.example.adaptt.control.AsAdaptTController;
import com.example.adaptt.control.AsAdaptTState;
import com.example.adaptt.control.ControlDecision;
import com.example.adaptt.metrics.LatencyWindowSnapshot;
import com.example.adaptt.metrics.PoolWindowSnapshot;
import com.example.adaptt.server.AdjustableWorkerPool;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsAdaptTControllerTest {
    @Test
    void acceptsOnlyWhenMeanImprovesAndThroughputAndP99AreNonInferior() {
        try (AdjustableWorkerPool pool = pool(100)) {
            AsAdaptTController controller = controller(pool, 10, 100);

            controller.onWindow(window(100, 200, 100), pool(pool, 0));
            ControlDecision applied = controller.onWindow(
                    window(100, 200, 100), pool(pool, 0));
            assertEquals("TRIAL_APPLIED", applied.stage());
            assertEquals(11, pool.configuredSize());

            ControlDecision accepted = controller.onWindow(
                    window(95, 220, 95), pool(pool, 0));

            assertEquals("TRIAL_ACCEPTED", accepted.stage());
            assertEquals(11, controller.bestPoolSize());
            assertEquals(10, controller.stepSize());
            assertEquals(1, controller.direction());
            assertEquals(AsAdaptTState.SEARCH, controller.state());
            assertEquals(5.0, accepted.improvementPercent(), 0.0001);
        }
    }

    @Test
    void rejectsWhenP99OrThroughputCrossesItsGuard() {
        try (AdjustableWorkerPool p99Pool = pool(100)) {
            AsAdaptTController controller = controller(p99Pool, 10, 100);
            controller.onWindow(window(100, 200, 100), pool(p99Pool, 0));
            controller.onWindow(window(100, 200, 100), pool(p99Pool, 0));

            ControlDecision rejected = controller.onWindow(
                    window(94, 221, 100), pool(p99Pool, 0));

            assertEquals("TRIAL_REJECTED_STEP_REDUCED", rejected.stage());
            assertEquals(1, p99Pool.configuredSize());
            assertEquals(5, controller.stepSize());
            assertEquals(1, controller.direction(),
                    "lower bound keeps the feasible increase direction");
        }

        try (AdjustableWorkerPool throughputPool = pool(100)) {
            AsAdaptTController controller = controller(throughputPool, 10, 100);
            controller.onWindow(window(100, 200, 100), pool(throughputPool, 0));
            controller.onWindow(window(100, 200, 100), pool(throughputPool, 0));

            ControlDecision rejected = controller.onWindow(
                    window(94, 200, 94), pool(throughputPool, 0));

            assertEquals("TRIAL_REJECTED_STEP_REDUCED", rejected.stage());
            assertEquals(1, throughputPool.configuredSize());
            assertEquals(20L, rejected.telemetry().totalVariation());
            assertEquals(2L, rejected.telemetry().adjustmentCount());
            assertEquals(1L, rejected.telemetry().rollbackCount());
        }
    }

    @Test
    void halvesTheStepAndContinuesInTheOnlyFeasibleBoundaryDirection() {
        try (AdjustableWorkerPool pool = pool(100)) {
            AsAdaptTController controller = controller(pool, 10, 100);
            controller.onWindow(window(100, 200, 100), pool(pool, 0));
            controller.onWindow(window(100, 200, 100), pool(pool, 0));
            controller.onWindow(window(100, 200, 100), pool(pool, 0));

            ControlDecision nextTrial = controller.onWindow(
                    window(100, 200, 100), pool(pool, 0));

            assertEquals("TRIAL_APPLIED", nextTrial.stage());
            assertEquals(6, pool.configuredSize());
            assertEquals(5, controller.stepSize());
            assertEquals(1, controller.direction());
        }
    }

    @Test
    void convergesAfterBothMinimumStepDirectionsFailAndBuildsReference() {
        try (AdjustableWorkerPool pool = pool(10)) {
            AsAdaptTController controller = controller(pool, 2, 10);

            controller.onWindow(window(100, 200, 100), pool(pool, 0)); // baseline
            controller.onWindow(window(100, 200, 100), pool(pool, 0)); // 1 -> 3
            controller.onWindow(window(90, 190, 100), pool(pool, 0));  // accept 3
            controller.onWindow(window(90, 190, 100), pool(pool, 0));  // 3 -> 5
            controller.onWindow(window(95, 200, 100), pool(pool, 0));  // reject, S=1, D=-
            controller.onWindow(window(90, 190, 100), pool(pool, 0));  // 3 -> 2
            controller.onWindow(window(95, 200, 100), pool(pool, 0));  // reject decrease
            controller.onWindow(window(90, 190, 100), pool(pool, 0));  // 3 -> 4
            ControlDecision converged = controller.onWindow(
                    window(95, 200, 100), pool(pool, 0));               // reject increase

            assertEquals("TRIAL_REJECTED_CONVERGED", converged.stage());
            assertEquals(AsAdaptTState.REFERENCE_COLLECTION, controller.state());
            assertEquals(3, pool.configuredSize());
            assertTrue(controller.increaseFailed());
            assertTrue(controller.decreaseFailed());

            controller.onWindow(window(90, 190, 100), pool(pool, 0));
            controller.onWindow(window(90, 190, 100), pool(pool, 0));
            ControlDecision stable = controller.onWindow(
                    window(90, 190, 100), pool(pool, 0));

            assertEquals("STABLE_REFERENCE_ESTABLISHED", stable.stage());
            assertEquals(AsAdaptTState.STABLE, controller.state());
            assertEquals(3, stable.telemetry().referenceWindowCount());
        }
    }

    @Test
    void detectsThreeDegradedWindowsAndRestartsFromStablePoolSize() {
        try (AdjustableWorkerPool pool = pool(10)) {
            AsAdaptTController controller = stableAtThree(pool);

            ControlDecision pendingOne = controller.onWindow(
                    window(130, 250, 80), pool(pool, 1));
            ControlDecision pendingTwo = controller.onWindow(
                    window(130, 250, 80), pool(pool, 2));
            ControlDecision detected = controller.onWindow(
                    window(130, 250, 80), pool(pool, 3));

            assertEquals("LOAD_CHANGE_PENDING", pendingOne.stage());
            assertEquals("LOAD_CHANGE_PENDING", pendingTwo.stage());
            assertEquals("LOAD_CHANGE_DETECTED", detected.stage());
            assertEquals(AsAdaptTState.RECOVERY_BASELINE_COLLECTION,
                    controller.state());
            assertEquals(3, pool.configuredSize());
            assertEquals(3, controller.bestPoolSize());
            assertEquals(2, controller.stepSize(),
                    "restart restores the configured maximum step");
            assertEquals(1, controller.direction(),
                    "higher latency, P99, or queue pressure searches upward");
            assertEquals(0, detected.telemetry().changeDetectionCount());

            ControlDecision baselineOne = controller.onWindow(
                    window(100, 200, 100), pool(pool, 3));
            ControlDecision baselineTwo = controller.onWindow(
                    window(100, 200, 100), pool(pool, 3));
            ControlDecision baselineEstablished = controller.onWindow(
                    window(100, 200, 100), pool(pool, 3));

            assertEquals("LOAD_CHANGE_BASELINE_COLLECTED", baselineOne.stage());
            assertEquals("LOAD_CHANGE_BASELINE_COLLECTED", baselineTwo.stage());
            assertEquals("LOAD_CHANGE_BASELINE_ESTABLISHED",
                    baselineEstablished.stage());
            assertEquals(AsAdaptTState.SEARCH, controller.state());
            assertEquals(3, pool.configuredSize(),
                    "fresh baseline collection must not resize the pool");

            ControlDecision trial = controller.onWindow(
                    window(100, 200, 100), pool(pool, 3));
            assertEquals("TRIAL_APPLIED", trial.stage());
            assertEquals(5, pool.configuredSize());

            ControlDecision rejected = controller.onWindow(
                    window(96, 200, 100), pool(pool, 3));
            assertEquals("TRIAL_REJECTED_RECOVERY_STEP_REDUCED",
                    rejected.stage(),
                    "trial must be compared with the three fresh baseline windows");
            assertEquals(1, controller.direction(),
                    "recovery search keeps its inferred direction while reducing the step");
        }
    }

    @Test
    void lowerDemandCharacteristicsChooseDecreaseAsRecoveryDirection() {
        try (AdjustableWorkerPool pool = pool(10)) {
            AsAdaptTController controller = stableAtThree(pool, 5);

            controller.onWindow(window(80, 170, 80), pool(pool, 2));
            controller.onWindow(window(80, 170, 80), pool(pool, 2));
            ControlDecision detected = controller.onWindow(
                    window(80, 170, 80), pool(pool, 2));

            assertEquals("LOAD_CHANGE_DETECTED", detected.stage());
            assertEquals(-1, controller.direction());
            assertEquals(AsAdaptTState.RECOVERY_BASELINE_COLLECTION,
                    controller.state());

            controller.onWindow(window(80, 170, 80), pool(pool, 2));
            controller.onWindow(window(80, 170, 80), pool(pool, 2));
            controller.onWindow(window(80, 170, 80), pool(pool, 2));
            ControlDecision trial = controller.onWindow(
                    window(80, 170, 80), pool(pool, 2));

            assertEquals("TRIAL_APPLIED", trial.stage());
            assertEquals(1, pool.configuredSize());

            ControlDecision coarseFailure = controller.onWindow(
                    window(100, 200, 60), pool(pool, 0));
            assertEquals("TRIAL_REJECTED_RECOVERY_STEP_REDUCED",
                    coarseFailure.stage());
            assertEquals(3, pool.configuredSize());
            assertEquals(1, controller.stepSize());
            assertEquals(-1, controller.direction(),
                    "a coarse decrease failure must retry a smaller decrease");

            ControlDecision smallerDecrease = controller.onWindow(
                    window(80, 170, 80), pool(pool, 2));
            assertEquals("TRIAL_APPLIED", smallerDecrease.stage());
            assertEquals(2, pool.configuredSize());

            ControlDecision minimumStepFailure = controller.onWindow(
                    window(100, 200, 60), pool(pool, 0));
            assertEquals("TRIAL_REJECTED_DIRECTION_REVERSED",
                    minimumStepFailure.stage());
            assertEquals(3, pool.configuredSize());
            assertEquals(1, controller.direction(),
                    "recovery direction reverses only after its minimum-step trial fails");
        }
    }

    @Test
    void emptyStableWindowClearsPendingLoadChangeDetection() {
        try (AdjustableWorkerPool pool = pool(10)) {
            AsAdaptTController controller = stableAtThree(pool);

            ControlDecision pending = controller.onWindow(
                    window(101, 212, 89), pool(pool, 2));
            assertEquals(1, pending.telemetry().changeDetectionCount());

            ControlDecision empty = controller.onWindow(
                    emptyWindow(), pool(pool, 0));
            assertEquals("EMPTY_WINDOW_DEFERRED", empty.stage());
            assertEquals(AsAdaptTState.STABLE, controller.state());
            assertEquals(0, empty.telemetry().changeDetectionCount());

            ControlDecision pendingAgain = controller.onWindow(
                    window(101, 212, 89), pool(pool, 2));
            assertEquals("LOAD_CHANGE_PENDING", pendingAgain.stage());
            assertEquals(1,
                    pendingAgain.telemetry().changeDetectionCount(),
                    "anomaly count must not cross an empty window");
        }
    }

    @Test
    void emptyWindowDoesNotAdvanceTheStateMachine() {
        try (AdjustableWorkerPool pool = pool(10)) {
            AsAdaptTController controller = controller(pool, 2, 10);

            ControlDecision empty = controller.onWindow(emptyWindow(), pool(pool, 0));

            assertEquals("EMPTY_WINDOW_DEFERRED", empty.stage());
            assertEquals(AsAdaptTState.INITIAL_BASELINE, controller.state());
            assertEquals(1, pool.configuredSize());
        }
    }

    @Test
    void validatesControllerBoundsAgainstWorkerPool() {
        try (AdjustableWorkerPool pool = pool(10)) {
            AsAdaptTConfig invalid = config(2, 20);
            assertThrows(IllegalArgumentException.class,
                    () -> new AsAdaptTController(pool, invalid));
        }
    }

    @Test
    void noStableAblationRestartsSearchInsteadOfHolding() {
        try (AdjustableWorkerPool pool = new AdjustableWorkerPool(1, 1, 1, 10)) {
            AsAdaptTController controller = new AsAdaptTController(
                    pool, config(1, 1, false));

            controller.onWindow(window(100, 200, 100), pool(pool, 0));
            ControlDecision restarted = controller.onWindow(
                    window(100, 200, 100), pool(pool, 0));

            assertEquals("ABLATION_SEARCH_RESTARTED_AT_BOUND",
                    restarted.stage());
            assertEquals(AsAdaptTState.SEARCH, controller.state());
            assertEquals(0, restarted.telemetry().referenceWindowCount());
            assertEquals(0, restarted.telemetry().stableWindowCount());
            assertEquals(1, restarted.telemetry().stepSize());
        }
    }

    private static AsAdaptTController stableAtThree(AdjustableWorkerPool pool) {
        return stableAtThree(pool, 0);
    }

    private static AsAdaptTController stableAtThree(
            AdjustableWorkerPool pool,
            int referenceQueueSize) {
        AsAdaptTController controller = controller(pool, 2, 10);
        controller.onWindow(window(100, 200, 100), pool(pool, 0));
        controller.onWindow(window(100, 200, 100), pool(pool, 0));
        controller.onWindow(window(90, 190, 100), pool(pool, 0));
        controller.onWindow(window(90, 190, 100), pool(pool, 0));
        controller.onWindow(window(95, 200, 100), pool(pool, 0));
        controller.onWindow(window(90, 190, 100), pool(pool, 0));
        controller.onWindow(window(95, 200, 100), pool(pool, 0));
        controller.onWindow(window(90, 190, 100), pool(pool, 0));
        controller.onWindow(window(95, 200, 100), pool(pool, 0));
        controller.onWindow(window(90, 190, 100),
                pool(pool, referenceQueueSize));
        controller.onWindow(window(90, 190, 100),
                pool(pool, referenceQueueSize));
        controller.onWindow(window(90, 190, 100),
                pool(pool, referenceQueueSize));
        assertEquals(AsAdaptTState.STABLE, controller.state());
        return controller;
    }

    private static AsAdaptTController controller(
            AdjustableWorkerPool pool,
            int maximumStep,
            int maximumPool) {
        return new AsAdaptTController(pool, config(maximumStep, maximumPool));
    }

    private static AsAdaptTConfig config(int maximumStep, int maximumPool) {
        return config(maximumStep, maximumPool, true);
    }

    private static AsAdaptTConfig config(
            int maximumStep,
            int maximumPool,
            boolean stableHoldingEnabled) {
        return new AsAdaptTConfig(
                1,
                maximumPool,
                1,
                maximumStep,
                5.0,
                5.0,
                10.0,
                3,
                3,
                10.0,
                10.0,
                10.0,
                10.0,
                5.0,
                5.0,
                stableHoldingEnabled);
    }

    private static AdjustableWorkerPool pool(int maximum) {
        return new AdjustableWorkerPool(1, 1, maximum, 10);
    }

    private static PoolWindowSnapshot pool(
            AdjustableWorkerPool pool,
            int queueSize) {
        return new PoolWindowSnapshot(
                pool.configuredSize(),
                pool.liveThreadCount(),
                0,
                queueSize,
                0,
                0);
    }

    private static LatencyWindowSnapshot window(
            double mean,
            double p99,
            long successfulRequests) {
        Instant now = Instant.now();
        return new LatencyWindowSnapshot(
                now.minusSeconds(10),
                now,
                successfulRequests,
                0,
                mean,
                p99,
                mean,
                p99);
    }

    private static LatencyWindowSnapshot emptyWindow() {
        Instant now = Instant.now();
        return new LatencyWindowSnapshot(
                now.minusSeconds(10), now, 0, 0, 0, 0, 0, 0);
    }
}
