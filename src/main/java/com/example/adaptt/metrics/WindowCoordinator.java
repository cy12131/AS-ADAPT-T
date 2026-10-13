package com.example.adaptt.metrics;

import com.example.adaptt.config.AppConfig;
import com.example.adaptt.control.AdaptTController;
import com.example.adaptt.control.AsAdaptTController;
import com.example.adaptt.control.ControlDecision;
import com.example.adaptt.control.ControllerMetricsTracker;
import com.example.adaptt.control.QueueHysteresisController;
import com.example.adaptt.control.WindowController;
import com.example.adaptt.server.AdjustableWorkerPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Creates each T-second snapshot once and distributes it to control and CSV. */
public final class WindowCoordinator implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(WindowCoordinator.class);

    private final LatencyWindow latencyWindow;
    private final AdjustableWorkerPool workerPool;
    private final CsvMetricsReporter reporter;
    private final WindowController controller;
    private final ControllerMetricsTracker controllerMetrics;
    private final int windowSeconds;
    private final ScheduledExecutorService scheduler;

    private long previousCompletedTasks;
    private long previousRejectedTasks;
    private MeasurementState measurementState = MeasurementState.READY;
    private int targetWindows;
    private int completedWindows;
    private Instant scheduledStartAt;
    private Instant startedAt;
    private Instant completedAt;
    private ScheduledFuture<?> periodicTask;

    public WindowCoordinator(
            LatencyWindow latencyWindow,
            AdjustableWorkerPool workerPool,
            CsvMetricsReporter reporter,
            WindowController controller,
            int windowSeconds) {
        this.latencyWindow = Objects.requireNonNull(latencyWindow, "latencyWindow");
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.controller = controller;
        this.controllerMetrics = new ControllerMetricsTracker(controller != null);
        if (windowSeconds < 1) {
            throw new IllegalArgumentException("windowSeconds must be at least 1");
        }
        this.windowSeconds = windowSeconds;
        this.previousCompletedTasks = workerPool.completedTaskCount();
        this.previousRejectedTasks = workerPool.rejectedTaskCount();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "window-coordinator");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static WindowCoordinator from(
            AppConfig config,
            LatencyWindow latencyWindow,
            AdjustableWorkerPool workerPool,
            CsvMetricsReporter reporter) {
        String policy = config.getString("experiment.policy")
                .toUpperCase(Locale.ROOT);
        WindowController controller;
        if ("FIXED".equals(policy)) {
            controller = null;
        } else if ("ADAPT_T".equals(policy)) {
            if (workerPool.configuredSize() != 1) {
                throw new IllegalArgumentException(
                        "Algorithm 1 requires worker.initialSize=1 for ADAPT_T");
            }
            controller = AdaptTController.from(config, workerPool);
        } else if ("AS_ADAPT_T".equals(policy)) {
            controller = AsAdaptTController.from(config, workerPool);
        } else if ("QUEUE_HYSTERESIS".equals(policy)) {
            controller = QueueHysteresisController.from(config, workerPool);
        } else {
            throw new IllegalArgumentException(
                    "experiment.policy must be FIXED, ADAPT_T, AS_ADAPT_T, "
                            + "or QUEUE_HYSTERESIS");
        }

        return new WindowCoordinator(
                latencyWindow,
                workerPool,
                reporter,
                controller,
                config.getInt("adapt.windowSeconds"));
    }

    /** Arms exactly one finite measurement after a short load warm-up delay. */
    public synchronized MeasurementStatus arm(int windows, int delaySeconds) {
        if (windows < 1) {
            throw new IllegalArgumentException("windows must be at least 1");
        }
        if (delaySeconds < 0 || delaySeconds > 60) {
            throw new IllegalArgumentException(
                    "delaySeconds must be between 0 and 60");
        }
        if (measurementState != MeasurementState.READY) {
            throw new IllegalStateException(
                    "measurement has already been armed for this server process");
        }

        targetWindows = windows;
        completedWindows = 0;
        scheduledStartAt = Instant.now().plusSeconds(delaySeconds);
        measurementState = MeasurementState.SCHEDULED;
        scheduler.schedule(this::beginSafely, delaySeconds, TimeUnit.SECONDS);
        return status();
    }

    public synchronized MeasurementStatus status() {
        return new MeasurementStatus(
                measurementState,
                targetWindows,
                completedWindows,
                epochMillis(scheduledStartAt),
                epochMillis(startedAt),
                epochMillis(completedAt));
    }

    /** Exposed for deterministic integration tests. */
    public synchronized void tickNow() throws Exception {
        LatencyWindowSnapshot latency = latencyWindow.snapshotAndReset();

        long completed = workerPool.completedTaskCount();
        long rejected = workerPool.rejectedTaskCount();
        PoolWindowSnapshot pool = new PoolWindowSnapshot(
                workerPool.configuredSize(),
                workerPool.liveThreadCount(),
                workerPool.activeCount(),
                workerPool.queueSize(),
                Math.max(0L, completed - previousCompletedTasks),
                Math.max(0L, rejected - previousRejectedTasks));
        previousCompletedTasks = completed;
        previousRejectedTasks = rejected;

        ControlDecision control = null;
        if (controller != null) {
            long decisionStartedNanos = System.nanoTime();
            control = controller.onWindow(latency, pool);
            long decisionElapsedNanos = System.nanoTime() - decisionStartedNanos;
            controllerMetrics.record(control, decisionElapsedNanos);
        }
        reporter.report(latency, pool, control, controllerMetrics.snapshot());
    }

    private synchronized void beginSafely() {
        if (measurementState != MeasurementState.SCHEDULED) {
            return;
        }
        try {
            // Discard health checks and warm-up requests immediately before the
            // first official window begins.
            latencyWindow.snapshotAndReset();
            previousCompletedTasks = workerPool.completedTaskCount();
            previousRejectedTasks = workerPool.rejectedTaskCount();
            startedAt = Instant.now();
            measurementState = MeasurementState.RUNNING;
            periodicTask = scheduler.scheduleAtFixedRate(
                    this::tickScheduledSafely,
                    windowSeconds,
                    windowSeconds,
                    TimeUnit.SECONDS);
            LOGGER.info(
                    "Measurement started targetWindows={} windowSeconds={}",
                    targetWindows,
                    windowSeconds);
        } catch (Exception e) {
            LOGGER.error("Unable to start measurement", e);
        }
    }

    private synchronized void tickScheduledSafely() {
        if (measurementState != MeasurementState.RUNNING) {
            return;
        }
        try {
            tickNow();
            completedWindows++;
            if (completedWindows >= targetWindows) {
                completedAt = Instant.now();
                measurementState = MeasurementState.COMPLETED;
                reporter.complete(controllerMetrics.snapshot());
                if (periodicTask != null) {
                    periodicTask.cancel(false);
                }
                LOGGER.info(
                        "Measurement completed windows={}", completedWindows);
            }
        } catch (Exception e) {
            LOGGER.error("Unable to process measurement window", e);
        }
    }

    private static Long epochMillis(Instant value) {
        return value == null ? null : value.toEpochMilli();
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(Duration.ofSeconds(2).toMillis(),
                    TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            reporter.complete(controllerMetrics.snapshot());
        } catch (Exception e) {
            LOGGER.error("Unable to finalize measurement reports", e);
        }
    }
}
