package com.example.adaptt.metrics;

import com.example.adaptt.config.AppConfig;
import com.example.adaptt.control.ControlDecision;
import com.example.adaptt.control.ControllerMetricsSnapshot;
import com.example.adaptt.control.ControllerTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Writes one CSV row for each strict latency measurement window. */
public final class CsvMetricsReporter implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(CsvMetricsReporter.class);
    private static final String HEADER = String.join(",",
            "window_start",
            "window_end",
            "application",
            "policy",
            "window_index",
            "window_duration_seconds",
            "throughput_rps",
            "configured_pool_size",
            "live_threads",
            "active_threads",
            "queue_size",
            "successful_requests",
            "failed_requests",
            "completed_tasks_delta",
            "rejected_tasks_delta",
            "mean_latency_ms",
            "p95_latency_ms",
            "p99_latency_ms",
            "p999_latency_ms",
            "min_latency_ms",
            "max_latency_ms") + System.lineSeparator();
    private static final String CONTROL_HEADER = String.join(",",
            "control_stage",
            "control_action",
            "pool_size_after_control",
            "improvement_percent",
            "increment_lock",
            "decrement_lock") + System.lineSeparator();
    private static final String TELEMETRY_HEADER = String.join(",",
            "control_state",
            "best_pool_size",
            "trial_pool_size",
            "search_direction",
            "search_step_size",
            "increase_failed",
            "decrease_failed",
            "reference_window_count",
            "change_detection_count",
            "adjustment_count",
            "rollback_count",
            "direction_reversal_count",
            "thread_total_variation",
            "stable_window_count",
            "search_window_count") + System.lineSeparator();
    private static final String CONTROLLER_METRICS_HEADER = String.join(",",
            "controller_decision_count",
            "candidate_count",
            "accepted_count",
            "rejected_count",
            "controller_rollback_count",
            "stable_enter_count",
            "recovery_count",
            "controller_decision_total_ns",
            "controller_decision_mean_ns",
            "controller_decision_p95_ns",
            "controller_decision_max_ns") + System.lineSeparator();

    private final Path outputFile;
    private final String application;
    private final String policy;
    private final RequestLatencyHistogramReporter requestLatencyReporter;
    private final Path controllerSummaryFile;
    private long windowIndex;
    private boolean completed;

    public CsvMetricsReporter(
            Path outputFile,
            String application,
            String policy) throws IOException {
        this.outputFile = Objects.requireNonNull(outputFile, "outputFile");
        this.application = csvSafe(application);
        this.policy = csvSafe(policy);
        this.windowIndex = 0L;
        this.completed = false;
        this.requestLatencyReporter = new RequestLatencyHistogramReporter(outputFile);
        this.controllerSummaryFile = siblingWithSuffix(
                outputFile,
                "-controller-summary.csv");
        initializeOutputFile();
    }

    public static CsvMetricsReporter from(AppConfig config) throws IOException {
        Objects.requireNonNull(config, "config");
        return new CsvMetricsReporter(
                Path.of(config.getString("metrics.outputFile")),
                config.getString("experiment.application"),
                config.getString("experiment.policy"));
    }

    public synchronized void report(
            LatencyWindowSnapshot snapshot,
            PoolWindowSnapshot pool,
            ControlDecision control) throws IOException {
        report(
                snapshot,
                pool,
                control,
                ControllerMetricsSnapshot.unavailable());
    }

    public synchronized void report(
            LatencyWindowSnapshot snapshot,
            PoolWindowSnapshot pool,
            ControlDecision control,
            ControllerMetricsSnapshot controllerMetrics) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(controllerMetrics, "controllerMetrics");
        if (completed) {
            throw new IllegalStateException("metrics reporting is completed");
        }

        String controlStage = control == null ? "FIXED" : control.stage();
        String controlAction = control == null ? "NONE" : control.action().name();
        int poolAfterControl = control == null
                ? pool.configuredSize()
                : control.poolAfter();
        double improvement = control == null
                ? ControlDecision.NOT_AVAILABLE
                : control.improvementPercent();
        int incrementLock = control == null ? 0 : control.incrementLock();
        int decrementLock = control == null ? 0 : control.decrementLock();
        ControllerTelemetry telemetry = control == null
                ? ControllerTelemetry.unavailable()
                : control.telemetry();
        boolean telemetryAvailable = !telemetry.state().isBlank();
        windowIndex++;
        double durationSeconds = Duration.between(
                snapshot.startedAt(), snapshot.endedAt()).toNanos()
                / 1_000_000_000.0;
        double throughputRps = durationSeconds <= 0.0
                ? 0.0
                : snapshot.successfulRequests() / durationSeconds;

        String baseRow = String.format(Locale.ROOT,
                "%s,%s,%s,%s,%d,%.6f,%.6f,%d,%d,%d,%d,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%s,%s,%d,%s,%d,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s",
                snapshot.startedAt(),
                snapshot.endedAt(),
                application,
                policy,
                windowIndex,
                durationSeconds,
                throughputRps,
                pool.configuredSize(),
                pool.liveThreads(),
                pool.activeThreads(),
                pool.queueSize(),
                snapshot.successfulRequests(),
                snapshot.failedRequests(),
                pool.completedTasksDelta(),
                pool.rejectedTasksDelta(),
                snapshot.meanLatencyMs(),
                snapshot.p95LatencyMs(),
                snapshot.p99LatencyMs(),
                snapshot.p999LatencyMs(),
                snapshot.minLatencyMs(),
                snapshot.maxLatencyMs(),
                controlStage,
                controlAction,
                poolAfterControl,
                csvNumber(improvement),
                incrementLock,
                decrementLock,
                telemetryAvailable ? telemetry.state() : "",
                telemetryInteger(telemetry.bestPoolSize(), telemetryAvailable),
                telemetryInteger(telemetry.trialPoolSize(), telemetryAvailable),
                telemetryInteger(telemetry.direction(), telemetryAvailable),
                telemetryInteger(telemetry.stepSize(), telemetryAvailable),
                telemetryBoolean(telemetry.increaseFailed(), telemetryAvailable),
                telemetryBoolean(telemetry.decreaseFailed(), telemetryAvailable),
                telemetryInteger(telemetry.referenceWindowCount(), telemetryAvailable),
                telemetryInteger(telemetry.changeDetectionCount(), telemetryAvailable),
                telemetryLong(telemetry.adjustmentCount(), telemetryAvailable),
                telemetryLong(telemetry.rollbackCount(), telemetryAvailable),
                telemetryLong(telemetry.directionReversalCount(), telemetryAvailable),
                telemetryLong(telemetry.totalVariation(), telemetryAvailable),
                telemetryLong(telemetry.stableWindowCount(), telemetryAvailable),
                telemetryLong(telemetry.searchWindowCount(), telemetryAvailable));
        String row = baseRow + "," + controllerMetricsRow(controllerMetrics)
                + System.lineSeparator();

        Files.writeString(
                outputFile,
                row,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        requestLatencyReporter.recordWindow(snapshot);

        LOGGER.info(
                "window requests={} failures={} meanMs={} p99Ms={} pool={} queue={}",
                snapshot.successfulRequests(),
                snapshot.failedRequests(),
                String.format(Locale.ROOT, "%.3f", snapshot.meanLatencyMs()),
                String.format(Locale.ROOT, "%.3f", snapshot.p99LatencyMs()),
                pool.configuredSize(),
                pool.queueSize());
    }

    public synchronized void complete(
            ControllerMetricsSnapshot controllerMetrics) throws IOException {
        Objects.requireNonNull(controllerMetrics, "controllerMetrics");
        if (completed) {
            return;
        }
        completed = true;
        requestLatencyReporter.complete();
        writeControllerSummary(controllerMetrics);
    }

    private void initializeOutputFile() throws IOException {
        Path parent = outputFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.notExists(outputFile) || Files.size(outputFile) == 0L) {
            Files.writeString(
                    outputFile,
                    HEADER.stripTrailing()
                            + "," + CONTROL_HEADER.stripTrailing()
                            + "," + TELEMETRY_HEADER.stripTrailing()
                            + "," + CONTROLLER_METRICS_HEADER,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    private static String csvSafe(String value) {
        Objects.requireNonNull(value, "value");
        if (value.contains(",") || value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException(
                    "CSV label values must not contain commas or newlines");
        }
        return value;
    }

    private static String csvNumber(double value) {
        return Double.isNaN(value)
                ? ""
                : String.format(Locale.ROOT, "%.6f", value);
    }

    private static String telemetryInteger(int value, boolean available) {
        return !available || value == ControllerTelemetry.NOT_AVAILABLE
                ? ""
                : Integer.toString(value);
    }

    private static String telemetryLong(long value, boolean available) {
        return available ? Long.toString(value) : "";
    }

    private static String telemetryBoolean(boolean value, boolean available) {
        return available ? Boolean.toString(value) : "";
    }

    private static String controllerMetricsRow(
            ControllerMetricsSnapshot metrics) {
        if (!metrics.available()) {
            return ",,,,,,,,,,";
        }
        return String.format(Locale.ROOT,
                "%d,%d,%d,%d,%d,%d,%d,%d,%.3f,%d,%d",
                metrics.decisionCount(),
                metrics.candidateCount(),
                metrics.acceptedCount(),
                metrics.rejectedCount(),
                metrics.rollbackCount(),
                metrics.stableEnterCount(),
                metrics.recoveryCount(),
                metrics.decisionTotalNanos(),
                metrics.decisionMeanNanos(),
                metrics.decisionP95Nanos(),
                metrics.decisionMaxNanos());
    }

    private void writeControllerSummary(
            ControllerMetricsSnapshot metrics) throws IOException {
        String header = "available," + CONTROLLER_METRICS_HEADER;
        String row = Boolean.toString(metrics.available()) + ","
                + controllerMetricsRow(metrics) + System.lineSeparator();
        Files.writeString(
                controllerSummaryFile,
                header + row,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private static Path siblingWithSuffix(Path path, String suffix) {
        String fileName = path.getFileName().toString();
        String stem = fileName.toLowerCase(Locale.ROOT).endsWith(".csv")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
        Path parent = path.toAbsolutePath().getParent();
        return (parent == null ? Path.of(".").toAbsolutePath() : parent)
                .resolve(stem + suffix);
    }

    public Path requestLatencyHistogramFile() {
        return requestLatencyReporter.histogramLogFile();
    }

    public Path requestLatencySummaryFile() {
        return requestLatencyReporter.summaryFile();
    }

    public Path controllerSummaryFile() {
        return controllerSummaryFile;
    }

    @Override
    public synchronized void close() throws IOException {
        complete(ControllerMetricsSnapshot.unavailable());
    }
}
