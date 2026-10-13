package com.example.adaptt;

import com.example.adaptt.control.AdaptObjective;
import com.example.adaptt.control.AdaptTController;
import com.example.adaptt.config.AppConfig;
import com.example.adaptt.metrics.CsvMetricsReporter;
import com.example.adaptt.metrics.LatencyWindow;
import com.example.adaptt.metrics.MeasurementState;
import com.example.adaptt.metrics.WindowCoordinator;
import com.example.adaptt.server.AdjustableWorkerPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowCoordinatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void recordsWindowPoolBeforeApplyingControlDecision() throws Exception {
        Path output = temporaryDirectory.resolve("adapt.csv");
        LatencyWindow latency = new LatencyWindow(10);
        latency.recordSuccess(TimeUnit.MILLISECONDS.toNanos(100));

        try (AdjustableWorkerPool pool = new AdjustableWorkerPool(1, 1, 101, 10)) {
            AdaptTController controller = new AdaptTController(
                    pool, 10, 5.0, 6, 6, AdaptObjective.MEAN);
            CsvMetricsReporter reporter = new CsvMetricsReporter(
                    output, "PRIME", "ADAPT_T");

            try (WindowCoordinator coordinator = new WindowCoordinator(
                    latency, pool, reporter, controller, 10)) {
                coordinator.tickNow();
            }

            assertEquals(11, pool.configuredSize());
        }

        List<String> lines = Files.readAllLines(output);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("PRIME,ADAPT_T,1"));
        assertTrue(lines.get(1).contains("INCREASE_APPLIED,INCREASE,11"));
    }

    @Test
    void recordsExactlyTheArmedNumberOfWindows() throws Exception {
        Path output = temporaryDirectory.resolve("finite.csv");
        LatencyWindow latency = new LatencyWindow(10);

        try (AdjustableWorkerPool pool = new AdjustableWorkerPool(2, 1, 11, 10);
             WindowCoordinator coordinator = new WindowCoordinator(
                     latency,
                     pool,
                     new CsvMetricsReporter(output, "PRIME", "FIXED"),
                     null,
                     1)) {
            coordinator.arm(1, 0);
            awaitState(coordinator, MeasurementState.RUNNING, 1_000L);
            latency.recordSuccess(TimeUnit.MILLISECONDS.toNanos(5));
            awaitState(coordinator, MeasurementState.COMPLETED, 2_000L);

            assertEquals(1, coordinator.status().completedWindows());
        }

        List<String> lines = Files.readAllLines(output);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("PRIME,FIXED,1,"));
    }

    @Test
    void selectsAdaptiveStepControllerWithoutChangingOriginalPolicy() throws Exception {
        Path output = temporaryDirectory.resolve("as-adapt.csv");
        System.setProperty("experiment.policy", "AS_ADAPT_T");
        System.setProperty("worker.initialSize", "1");
        try {
            AppConfig config = AppConfig.load();
            LatencyWindow latency = new LatencyWindow(10);
            try (AdjustableWorkerPool pool = AdjustableWorkerPool.from(config);
                 WindowCoordinator coordinator = WindowCoordinator.from(
                         config,
                         latency,
                         pool,
                         new CsvMetricsReporter(output, "PRIME", "AS_ADAPT_T"))) {
                latency.recordSuccess(TimeUnit.MILLISECONDS.toNanos(100));
                coordinator.tickNow();
                assertEquals(1, pool.configuredSize());

                latency.recordSuccess(TimeUnit.MILLISECONDS.toNanos(100));
                coordinator.tickNow();
                assertEquals(11, pool.configuredSize());
            }
        } finally {
            System.clearProperty("experiment.policy");
            System.clearProperty("worker.initialSize");
        }

        List<String> lines = Files.readAllLines(output);
        assertEquals(3, lines.size());
        assertTrue(lines.get(1).contains("INITIAL_BASELINE_ESTABLISHED"));
        assertTrue(lines.get(2).contains("TRIAL_APPLIED"));
    }

    private static void awaitState(
            WindowCoordinator coordinator,
            MeasurementState expected,
            long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (coordinator.status().state() != expected
                && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertEquals(expected, coordinator.status().state());
    }
}
