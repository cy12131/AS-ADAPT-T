package com.example.adaptt.metrics;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Persists mergeable per-window request-latency histograms and an exact
 * run-level summary. Histogram values are recorded in nanoseconds; the log's
 * display ratio converts them to milliseconds without changing the encoded
 * values.
 */
public final class RequestLatencyHistogramReporter implements AutoCloseable {
    private static final double NANOS_PER_MILLISECOND = 1_000_000.0;

    private final Path histogramLogFile;
    private final Path summaryFile;
    private final OutputStream outputStream;
    private final HistogramLogWriter logWriter;

    private Histogram aggregate;
    private Instant firstWindowStart;
    private Instant lastWindowEnd;
    private long windowCount;
    private boolean headerWritten;
    private boolean completed;

    public RequestLatencyHistogramReporter(Path metricsCsv) throws IOException {
        Objects.requireNonNull(metricsCsv, "metricsCsv");
        String baseName = stripCsvSuffix(metricsCsv.getFileName().toString());
        Path parent = metricsCsv.toAbsolutePath().getParent();
        if (parent == null) {
            parent = Path.of(".").toAbsolutePath();
        }
        Files.createDirectories(parent);
        this.histogramLogFile = parent.resolve(baseName + "-request-latency.hlog");
        this.summaryFile = parent.resolve(baseName + "-request-latency-summary.csv");
        this.outputStream = Files.newOutputStream(
                histogramLogFile,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        this.logWriter = new HistogramLogWriter(outputStream);
    }

    public synchronized void recordWindow(LatencyWindowSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ensureOpen();
        windowCount++;
        if (firstWindowStart == null) {
            firstWindowStart = snapshot.startedAt();
        }
        lastWindowEnd = snapshot.endedAt();

        if (!snapshot.hasMergeableHistogram()) {
            return;
        }

        Histogram interval = snapshot.histogram();
        interval.setStartTimeStamp(snapshot.startedAt().toEpochMilli());
        interval.setEndTimeStamp(snapshot.endedAt().toEpochMilli());
        interval.setTag("window-" + windowCount);

        if (!headerWritten) {
            logWriter.outputLogFormatVersion();
            logWriter.outputStartTime(snapshot.startedAt().toEpochMilli());
            logWriter.setBaseTime(snapshot.startedAt().toEpochMilli());
            logWriter.outputBaseTime(snapshot.startedAt().toEpochMilli());
            logWriter.outputComment(
                    "Request latency recorded in nanoseconds; output ratio is milliseconds");
            logWriter.outputLegend();
            headerWritten = true;
        }
        logWriter.outputIntervalHistogram(interval);

        if (aggregate == null) {
            aggregate = interval.copy();
            aggregate.setTag("run-total");
        } else {
            aggregate.add(interval);
        }
    }

    public synchronized void complete() throws IOException {
        if (completed) {
            return;
        }
        completed = true;
        writeSummary();
        logWriter.close();
    }

    public Path histogramLogFile() {
        return histogramLogFile;
    }

    public Path summaryFile() {
        return summaryFile;
    }

    private void writeSummary() throws IOException {
        long samples = aggregate == null ? 0L : aggregate.getTotalCount();
        String header = String.join(",",
                "measurement_start",
                "measurement_end",
                "window_count",
                "successful_requests",
                "mean_latency_ms",
                "p50_latency_ms",
                "p95_latency_ms",
                "p99_latency_ms",
                "p999_latency_ms",
                "min_latency_ms",
                "max_latency_ms") + System.lineSeparator();
        String row = String.format(Locale.ROOT,
                "%s,%s,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                firstWindowStart == null ? "" : firstWindowStart,
                lastWindowEnd == null ? "" : lastWindowEnd,
                windowCount,
                samples,
                meanMillis(samples),
                percentileMillis(samples, 50.0),
                percentileMillis(samples, 95.0),
                percentileMillis(samples, 99.0),
                percentileMillis(samples, 99.9),
                minimumMillis(samples),
                maximumMillis(samples));
        Files.writeString(
                summaryFile,
                header + row,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private double meanMillis(long samples) {
        return samples == 0L ? 0.0 : aggregate.getMean() / NANOS_PER_MILLISECOND;
    }

    private double percentileMillis(long samples, double percentile) {
        return samples == 0L
                ? 0.0
                : aggregate.getValueAtPercentile(percentile)
                        / NANOS_PER_MILLISECOND;
    }

    private double minimumMillis(long samples) {
        return samples == 0L
                ? 0.0
                : aggregate.getMinValue() / NANOS_PER_MILLISECOND;
    }

    private double maximumMillis(long samples) {
        return samples == 0L
                ? 0.0
                : aggregate.getMaxValue() / NANOS_PER_MILLISECOND;
    }

    private void ensureOpen() {
        if (completed) {
            throw new IllegalStateException("request latency reporting is completed");
        }
    }

    private static String stripCsvSuffix(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".csv")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
    }

    @Override
    public synchronized void close() throws IOException {
        complete();
    }
}
