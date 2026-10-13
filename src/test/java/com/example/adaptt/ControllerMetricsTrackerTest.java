package com.example.adaptt;

import com.example.adaptt.control.AdaptAction;
import com.example.adaptt.control.ControlDecision;
import com.example.adaptt.control.ControllerMetricsSnapshot;
import com.example.adaptt.control.ControllerMetricsTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerMetricsTrackerTest {
    @Test
    void classifiesSearchEventsAndDecisionCostWithoutChangingControl() {
        ControllerMetricsTracker tracker = new ControllerMetricsTracker(true);
        tracker.record(decision("TRIAL_APPLIED", 1, 11), 100);
        tracker.record(decision("TRIAL_ACCEPTED", 11, 11), 200);
        tracker.record(decision("TRIAL_APPLIED", 11, 6), 300);
        tracker.record(decision("TRIAL_REJECTED_GUARD", 6, 11), 400);
        tracker.record(decision("STABLE_REFERENCE_ESTABLISHED", 11, 11), 500);
        tracker.record(decision("LOAD_CHANGE_DETECTED", 11, 11), 600);

        ControllerMetricsSnapshot metrics = tracker.snapshot();
        assertTrue(metrics.available());
        assertEquals(6, metrics.decisionCount());
        assertEquals(2, metrics.candidateCount());
        assertEquals(1, metrics.acceptedCount());
        assertEquals(1, metrics.rejectedCount());
        assertEquals(1, metrics.rollbackCount());
        assertEquals(1, metrics.stableEnterCount());
        assertEquals(1, metrics.recoveryCount());
        assertEquals(2_100, metrics.decisionTotalNanos());
        assertEquals(350.0, metrics.decisionMeanNanos(), 1.0);
        assertTrue(metrics.decisionP95Nanos() >= 500);
        assertTrue(metrics.decisionMaxNanos() >= 600);
    }

    @Test
    void treatsQueueBaselineResizeAsAcceptedCandidate() {
        ControllerMetricsTracker tracker = new ControllerMetricsTracker(true);
        tracker.record(decision("QUEUE_INCREASE_APPLIED", 1, 6), 100);
        tracker.record(decision("QUEUE_HYSTERESIS_HOLD", 6, 6), 100);
        tracker.record(decision("QUEUE_DECREASE_APPLIED", 6, 1), 100);

        ControllerMetricsSnapshot metrics = tracker.snapshot();
        assertEquals(3, metrics.decisionCount());
        assertEquals(2, metrics.candidateCount());
        assertEquals(2, metrics.acceptedCount());
        assertEquals(0, metrics.rejectedCount());
        assertEquals(0, metrics.rollbackCount());
    }

    private static ControlDecision decision(
            String stage,
            int poolBefore,
            int poolAfter) {
        return new ControlDecision(
                stage,
                AdaptAction.INCREASE,
                poolBefore,
                poolAfter,
                0,
                0,
                1.0,
                Double.NaN);
    }
}
