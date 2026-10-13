package com.example.adaptt.server;

import com.example.adaptt.config.AppConfig;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Request-processing pool whose fixed size can be changed safely at runtime. */
public final class AdjustableWorkerPool implements AutoCloseable {
    private final int minimumSize;
    private final int maximumSize;
    private final LongAdder rejectedTasks = new LongAdder();
    private final ThreadPoolExecutor executor;

    public AdjustableWorkerPool(
            int initialSize,
            int minimumSize,
            int maximumSize,
            int queueCapacity) {
        if (minimumSize < 1) {
            throw new IllegalArgumentException("minimumSize must be at least 1");
        }
        if (maximumSize < minimumSize) {
            throw new IllegalArgumentException(
                    "maximumSize must not be smaller than minimumSize");
        }
        if (initialSize < minimumSize || initialSize > maximumSize) {
            throw new IllegalArgumentException(
                    "initialSize must be between minimumSize and maximumSize");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be at least 1");
        }

        this.minimumSize = minimumSize;
        this.maximumSize = maximumSize;
        this.executor = new ThreadPoolExecutor(
                initialSize,
                initialSize,
                1L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new NamedThreadFactory("request-worker-"),
                (task, pool) -> {
                    rejectedTasks.increment();
                    throw new RejectedExecutionException(
                            "Request worker pool and queue are saturated");
                });
        this.executor.prestartAllCoreThreads();
    }

    public static AdjustableWorkerPool from(AppConfig config) {
        Objects.requireNonNull(config, "config");
        return new AdjustableWorkerPool(
                config.getInt("worker.initialSize"),
                config.getInt("worker.minSize"),
                config.getInt("worker.maxSize"),
                config.getInt("worker.queueCapacity"));
    }

    public void execute(Runnable task) {
        executor.execute(Objects.requireNonNull(task, "task"));
    }

    /**
     * Changes both core and maximum sizes so the executor always behaves as a
     * fixed-size pool. Ordering avoids transient core/max invariant failures.
     */
    public synchronized void resize(int newSize) {
        if (newSize < minimumSize || newSize > maximumSize) {
            throw new IllegalArgumentException(
                    "Worker size " + newSize + " is outside ["
                            + minimumSize + ", " + maximumSize + "]");
        }

        int currentSize = configuredSize();
        if (newSize == currentSize) {
            return;
        }

        if (newSize > currentSize) {
            executor.setMaximumPoolSize(newSize);
            executor.setCorePoolSize(newSize);
            executor.prestartAllCoreThreads();
        } else {
            executor.setCorePoolSize(newSize);
            executor.setMaximumPoolSize(newSize);
        }
    }

    public int configuredSize() {
        return executor.getCorePoolSize();
    }

    public int minimumSize() {
        return minimumSize;
    }

    public int maximumSize() {
        return maximumSize;
    }

    public int activeCount() {
        return executor.getActiveCount();
    }

    public int liveThreadCount() {
        return executor.getPoolSize();
    }

    public int queueSize() {
        return executor.getQueue().size();
    }

    public long completedTaskCount() {
        return executor.getCompletedTaskCount();
    }

    public long rejectedTaskCount() {
        return rejectedTasks.sum();
    }

    public void shutdown() {
        executor.shutdown();
    }

    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        return executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        shutdown();
        try {
            if (!awaitTermination(Duration.ofSeconds(5))) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger();

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task,
                    prefix + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        }
    }
}
