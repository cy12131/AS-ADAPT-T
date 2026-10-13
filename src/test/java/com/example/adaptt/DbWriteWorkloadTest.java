package com.example.adaptt;

import com.example.adaptt.workload.DbWriteWorkload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DbWriteWorkloadTest {
    @Test
    void exposesStableConfiguredPayload() {
        DbWriteWorkload workload = new DbWriteWorkload(
                () -> {
                    throw new AssertionError("Database must not be used by getters");
                },
                "benchmark",
                3_072);

        assertEquals("benchmark", workload.eventType());
        assertEquals(3_072, workload.payloadBytes());
    }

    @Test
    void rejectsBlankEventType() {
        assertThrows(IllegalArgumentException.class,
                () -> new DbWriteWorkload(() -> null, " ", 3_072));
    }

    @Test
    void rejectsInvalidPayloadSizes() {
        assertThrows(IllegalArgumentException.class,
                () -> new DbWriteWorkload(() -> null, "benchmark", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DbWriteWorkload(
                        () -> null, "benchmark", 1_048_577));
    }
}
