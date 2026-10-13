package com.example.adaptt.metrics;

/** JSON-safe status returned to the load generator. */
public record MeasurementStatus(
        MeasurementState state,
        int targetWindows,
        int completedWindows,
        Long scheduledStartEpochMs,
        Long startedEpochMs,
        Long completedEpochMs) {
}
