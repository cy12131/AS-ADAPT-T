package com.example.adaptt;

import com.example.adaptt.control.AdaptAction;
import com.example.adaptt.control.ControlDecision;
import com.example.adaptt.control.ControllerTelemetry;
import com.example.adaptt.metrics.CsvMetricsReporter;
import com.example.adaptt.metrics.LatencyWindow;
import com.example.adaptt.metrics.LatencyWindowSnapshot;
import com.example.adaptt.metrics.PoolWindowSnapshot;
import com.example.adaptt.server.AdjustableWorkerPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvMetricsReporterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesHeaderAndOneStrictWindow() throws Exception {
        Path output = temporaryDirectory.resolve("windows.csv");
        LatencyWindow latencyWindow = new LatencyWindow(10);
        latencyWindow.recordSuccess(TimeUnit.MILLISECONDS.toNanos(4));
        latencyWindow.recordSuccess(TimeUnit.MILLISECONDS.toNanos(6));
        latencyWindow.recordFailure();

        try (AdjustableWorkerPool pool = new AdjustableWorkerPool(2, 1, 11, 10)) {
            try (CsvMetricsReporter reporter = new CsvMetricsReporter(
                    output, "PRIME", "FIXED")) {
                LatencyWindowSnapshot latency = latencyWindow.snapshotAndReset();
                PoolWindowSnapshot poolSnapshot = new PoolWindowSnapshot(
                        2, 2, 0, 0, 0, 0);
                reporter.report(latency, poolSnapshot, null);
            }
        }

        List<String> lines = Files.readAllLines(output);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("mean_latency_ms"));
        assertTrue(lines.get(0).contains("window_index"));
        assertTrue(lines.get(0).contains("throughput_rps"));
        assertTrue(lines.get(0).contains("control_state"));
        assertTrue(lines.get(0).contains("thread_total_variation"));
        assertTrue(lines.get(0).contains("p95_latency_ms"));
        assertTrue(lines.get(0).contains("p999_latency_ms"));
        assertTrue(lines.get(0).contains("controller_decision_count"));

        String[] header = lines.get(0).split(",", -1);
        String[] values = lines.get(1).split(",", -1);
        assertEquals(53, values.length);
        assertEquals("PRIME", value(header, values, "application"));
        assertEquals("FIXED", value(header, values, "policy"));
        assertEquals("1", value(header, values, "window_index"));
        assertTrue(Double.parseDouble(value(
                header, values, "window_duration_seconds")) > 0.0);
        assertTrue(Double.parseDouble(value(
                header, values, "throughput_rps")) > 0.0);
        assertEquals("2", value(header, values, "configured_pool_size"));
        assertEquals("2", value(header, values, "successful_requests"));
        assertEquals("1", value(header, values, "failed_requests"));
        assertEquals("", value(header, values, "control_state"));
        assertEquals("", value(header, values, "search_window_count"));
        assertEquals("", value(header, values, "controller_decision_count"));
    }

    @Test
    void writesAdaptiveStepControllerTelemetry() throws Exception {
        Path output = temporaryDirectory.resolve("as-adapt.csv");
        Instant now = Instant.now();
        LatencyWindowSnapshot latency = new LatencyWindowSnapshot(
                now.minusSeconds(10), now, 100, 0, 5, 9, 1, 10);
        PoolWindowSnapshot pool = new PoolWindowSnapshot(6, 6, 2, 3, 100, 0);
        ControllerTelemetry telemetry = new ControllerTelemetry(
                "TRIAL",
                1,
                6,
                1,
                5,
                false,
                true,
                0,
                0,
                3,
                1,
                1,
                25,
                0,
                4);
        ControlDecision control = new ControlDecision(
                "TRIAL_APPLIED",
                AdaptAction.INCREASE,
                1,
                6,
                0,
                0,
                5,
                Double.NaN,
                telemetry);

        try (CsvMetricsReporter reporter = new CsvMetricsReporter(
                output, "PRIME", "AS_ADAPT_T")) {
            reporter.report(latency, pool, control);
        }

        String[] header = Files.readAllLines(output).get(0).split(",", -1);
        String[] values = Files.readAllLines(output).get(1).split(",", -1);
        assertEquals(53, header.length);
        assertEquals(53, values.length);
        assertEquals("TRIAL", value(header, values, "control_state"));
        assertEquals("1", value(header, values, "best_pool_size"));
        assertEquals("6", value(header, values, "trial_pool_size"));
        assertEquals("1", value(header, values, "search_direction"));
        assertEquals("5", value(header, values, "search_step_size"));
        assertEquals("false", value(header, values, "increase_failed"));
        assertEquals("true", value(header, values, "decrease_failed"));
        assertEquals("25", value(header, values, "thread_total_variation"));
    }

    private static String value(
            String[] header,
            String[] values,
            String column) {
        int index = IntStream.range(0, header.length)
                .filter(i -> column.equals(header[i]))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing CSV column: " + column));
        return values[index];
    }
}
