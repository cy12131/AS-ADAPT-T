package com.example.adaptt.control;

/** Observable result of advancing Algorithm 1 by one T-second stage. */
public record ControlDecision(
        String stage,
        AdaptAction action,
        int poolBefore,
        int poolAfter,
        int incrementLock,
        int decrementLock,
        double observedMetricMs,
        double improvementPercent,
        ControllerTelemetry telemetry) {

    public static final double NOT_AVAILABLE = Double.NaN;

    public ControlDecision(
            String stage,
            AdaptAction action,
            int poolBefore,
            int poolAfter,
            int incrementLock,
            int decrementLock,
            double observedMetricMs,
            double improvementPercent) {
        this(
                stage,
                action,
                poolBefore,
                poolAfter,
                incrementLock,
                decrementLock,
                observedMetricMs,
                improvementPercent,
                ControllerTelemetry.unavailable());
    }
}
