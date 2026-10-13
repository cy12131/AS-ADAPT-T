package com.example.adaptt.control;

/** States of the adaptive-step ADAPT-T controller. */
public enum AsAdaptTState {
    INITIAL_BASELINE,
    SEARCH,
    TRIAL,
    REFERENCE_COLLECTION,
    RECOVERY_BASELINE_COLLECTION,
    STABLE
}
