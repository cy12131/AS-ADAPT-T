package com.example.adaptt;

import com.example.adaptt.control.ControllerMetricsSnapshot;
import com.example.adaptt.metrics.CsvMetricsReporter;
import com.example.adaptt.metrics.LatencyWindow;
import com.example.adaptt.metrics.PoolWindowSnapshot;
import org.HdrHistogram.EncodableHistogram;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestLatencyHistogramReporterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesMergeableIntervalsAndExactRunSummary() throws Exception {
        Path metrics = temporaryDirectory.resolve("run-server.csv");
        LatencyWindow window = new LatencyWindow(10);
        PoolWindowSnapshot pool = new PoolWindowSnapshot(2, 2, 0, 0, 0, 0);

        try (CsvMetricsReporter reporter = new CsvMetricsReporter(
                metrics, "PRIME", "AS_ADAPT_T")) {
            recordMillis(window, 1, 2, 3);
            reporter.report(
                    window.snapshotAndReset(),
                    pool,
                    null,
                    ControllerMetricsSnapshot.unavailable());

            recordMillis(window, 10, 20);
            reporter.report(
                    window.snapshotAndReset(),
                    pool,
                    null,
                    ControllerMetricsSnapshot.unavailable());
        }

        Path histogramLog = temporaryDirectory.resolve(
                "run-server-request-latency.hlog");
        Path summary = temporaryDirectory.resolve(
                "run-server-request-latency-summary.csv");
        assertTrue(Files.size(histogramLog) > 0L);

        Histogram merged = null;
        int intervals = 0;
        try (HistogramLogReader reader = new HistogramLogReader(
                histogramLog.toFile())) {
            EncodableHistogram encoded;
            while ((encoded = reader.nextIntervalHistogram()) != null) {
                Histogram interval = (Histogram) encoded;
                if (merged == null) {
                    merged = interval.copy();
                } else {
                    merged.add(interval);
                }
                intervals++;
            }
        }
        assertEquals(2, intervals);
        assertEquals(5, merged.getTotalCount());
        assertEquals(
                TimeUnit.MILLISECONDS.toNanos(20),
                merged.getValueAtPercentile(99.9),
                TimeUnit.MICROSECONDS.toNanos(50));

        List<String> summaryLines = Files.readAllLines(summary);
        assertEquals(2, summaryLines.size());
        String[] header = summaryLines.get(0).split(",", -1);
        String[] values = summaryLines.get(1).split(",", -1);
        assertEquals("2", value(header, values, "window_count"));
        assertEquals("5", value(header, values, "successful_requests"));
        assertEquals(7.2, Double.parseDouble(value(
                header, values, "mean_latency_ms")), 0.02);
        assertEquals(20.0, Double.parseDouble(value(
                header, values, "p999_latency_ms")), 0.05);
    }

    private static void recordMillis(LatencyWindow window, long... values) {
        for (long value : values) {
            window.recordSuccess(TimeUnit.MILLISECONDS.toNanos(value));
        }
    }

    private static String value(
            String[] header,
            String[] values,
            String column) {
        for (int i = 0; i < header.length; i++) {
            if (column.equals(header[i])) {
                return values[i];
            }
        }
        throw new AssertionError("Missing CSV column: " + column);
    }
}
