package com.example.adaptt;

import com.example.adaptt.server.AdjustableWorkerPool;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdjustableWorkerPoolTest {
    @Test
    void resizesWithinConfiguredBounds() {
        try (AdjustableWorkerPool pool = new AdjustableWorkerPool(2, 1, 11, 10)) {
            assertEquals(2, pool.configuredSize());

            pool.resize(11);
            assertEquals(11, pool.configuredSize());

            pool.resize(1);
            assertEquals(1, pool.configuredSize());
        }
    }

    @Test
    void rejectsSizesOutsideBounds() {
        try (AdjustableWorkerPool pool = new AdjustableWorkerPool(2, 1, 11, 10)) {
            assertThrows(IllegalArgumentException.class, () -> pool.resize(0));
            assertThrows(IllegalArgumentException.class, () -> pool.resize(12));
        }
    }

    @Test
    void executesSubmittedTasks() throws Exception {
        CountDownLatch completed = new CountDownLatch(4);

        try (AdjustableWorkerPool pool = new AdjustableWorkerPool(2, 1, 11, 10)) {
            for (int i = 0; i < 4; i++) {
                pool.execute(completed::countDown);
            }

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            pool.shutdown();
            assertTrue(pool.awaitTermination(Duration.ofSeconds(2)));
            assertEquals(4, pool.completedTaskCount());
        }
    }
}
