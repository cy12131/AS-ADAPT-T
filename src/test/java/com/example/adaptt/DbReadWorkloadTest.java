package com.example.adaptt;

import com.example.adaptt.workload.DbReadWorkload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbReadWorkloadTest {
    @Test
    void generatedIdsStayWithinConfiguredRange() {
        DbReadWorkload workload = new DbReadWorkload(
                () -> {
                    throw new AssertionError("Database must not be used by nextId");
                },
                1,
                1_000_000);

        for (int i = 0; i < 10_000; i++) {
            long id = workload.nextId();
            assertTrue(id >= 1 && id <= 1_000_000);
        }
    }

    @Test
    void rejectsIdsOutsideConfiguredRangeBeforeConnecting() {
        DbReadWorkload workload = new DbReadWorkload(
                () -> {
                    throw new AssertionError("Database connection should not be requested");
                },
                1,
                1_000_000);

        assertThrows(IllegalArgumentException.class, () -> workload.execute(0));
        assertThrows(IllegalArgumentException.class,
                () -> workload.execute(1_000_001));
    }

    @Test
    void rejectsInvalidConfiguredRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new DbReadWorkload(() -> null, 10, 9));
    }
}
