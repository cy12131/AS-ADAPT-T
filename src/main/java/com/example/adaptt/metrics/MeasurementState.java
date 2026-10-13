package com.example.adaptt.metrics;

/** Lifecycle of one process-scoped experiment measurement. */
public enum MeasurementState {
    READY,
    SCHEDULED,
    RUNNING,
    COMPLETED
}
