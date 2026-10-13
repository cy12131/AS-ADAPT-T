package com.example.adaptt.control;

/** Optional controller state written alongside every measurement window. */
public record ControllerTelemetry(
        String state,
        int bestPoolSize,
        int trialPoolSize,
        int direction,
        int stepSize,
        boolean increaseFailed,
        boolean decreaseFailed,
        int referenceWindowCount,
        int changeDetectionCount,
        long adjustmentCount,
        long rollbackCount,
        long directionReversalCount,
        long totalVariation,
        long stableWindowCount,
        long searchWindowCount) {

    public static final int NOT_AVAILABLE = -1;

    public static ControllerTelemetry unavailable() {
        return new ControllerTelemetry(
                "",
                NOT_AVAILABLE,
                NOT_AVAILABLE,
                0,
                0,
                false,
                false,
                0,
                0,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L);
    }
}
