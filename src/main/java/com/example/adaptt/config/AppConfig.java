package com.example.adaptt.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

/** Loads the experiment configuration bundled with the application. */
public final class AppConfig {
    private static final String RESOURCE_NAME = "application.properties";

    private final Properties properties;

    private AppConfig(Properties properties) {
        this.properties = properties;
        validate();
    }

    public static AppConfig load() {
        Properties properties = new Properties();
        try (InputStream input = AppConfig.class.getClassLoader()
                .getResourceAsStream(RESOURCE_NAME)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing classpath resource: " + RESOURCE_NAME);
            }
            properties.load(input);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to load " + RESOURCE_NAME, e);
        }
        return new AppConfig(properties);
    }

    /**
     * JVM system properties override values from application.properties. This
     * permits experiment changes such as -Dexperiment.policy=ADAPT_T without
     * editing the reproducible base configuration.
     */
    public String getString(String key) {
        Objects.requireNonNull(key, "key");
        String systemOverride = System.getProperty(key);
        String value = systemOverride != null
                ? systemOverride
                : properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required configuration property: " + key);
        }
        return value.trim();
    }

    public int getInt(String key) {
        String value = getString(key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw invalidNumber(key, value, "integer", e);
        }
    }

    public long getLong(String key) {
        String value = getString(key);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw invalidNumber(key, value, "long", e);
        }
    }

    public double getDouble(String key) {
        String value = getString(key);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw invalidNumber(key, value, "double", e);
        }
    }

    public String databasePassword() {
        String password = System.getenv("ADAPT_T_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "ADAPT_T_DB_PASSWORD is required for database workloads");
        }
        return password;
    }

    private void validate() {
        requireRange("server.port", getInt("server.port"), 1, 65_535);
        requireRange("netty.bossThreads", getInt("netty.bossThreads"), 1, 16);
        requireRange("netty.ioThreads", getInt("netty.ioThreads"), 1, 256);

        int minimum = getInt("worker.minSize");
        int initial = getInt("worker.initialSize");
        int maximum = getInt("worker.maxSize");
        requireRange("worker.minSize", minimum, 1, Integer.MAX_VALUE);
        if (initial < minimum || initial > maximum) {
            throw new IllegalArgumentException(
                    "worker.initialSize must be between worker.minSize and worker.maxSize");
        }
        if (maximum < minimum) {
            throw new IllegalArgumentException(
                    "worker.maxSize must be greater than or equal to worker.minSize");
        }

        requireRange("worker.queueCapacity", getInt("worker.queueCapacity"),
                1, Integer.MAX_VALUE);
        requireRange("adapt.windowSeconds", getInt("adapt.windowSeconds"),
                1, Integer.MAX_VALUE);
        requireRange("adapt.stepSize", getInt("adapt.stepSize"),
                1, Integer.MAX_VALUE);
        requireRange("metrics.highestTrackableLatencySeconds",
                getInt("metrics.highestTrackableLatencySeconds"),
                1, 3_600);

        double threshold = getDouble("adapt.latencyThresholdPercent");
        if (!Double.isFinite(threshold) || threshold < 0.0 || threshold > 100.0) {
            throw new IllegalArgumentException(
                    "adapt.latencyThresholdPercent must be between 0 and 100");
        }

        int queueMinimum = getInt("queueBaseline.minPoolSize");
        int queueMaximum = getInt("queueBaseline.maxPoolSize");
        int queueStep = getInt("queueBaseline.stepSize");
        int queueLow = getInt("queueBaseline.lowQueueThreshold");
        int queueHigh = getInt("queueBaseline.highQueueThreshold");
        int queueWindows = getInt("queueBaseline.consecutiveWindows");
        requireRange("queueBaseline.minPoolSize", queueMinimum, 1, maximum);
        requireRange("queueBaseline.maxPoolSize", queueMaximum,
                queueMinimum, maximum);
        requireRange("queueBaseline.stepSize", queueStep, 1, maximum);
        requireRange("queueBaseline.lowQueueThreshold", queueLow,
                0, Integer.MAX_VALUE);
        if (queueHigh <= queueLow) {
            throw new IllegalArgumentException(
                    "queueBaseline.highQueueThreshold must exceed "
                            + "queueBaseline.lowQueueThreshold");
        }
        requireRange("queueBaseline.consecutiveWindows", queueWindows,
                1, Integer.MAX_VALUE);

        long lower = getLong("prime.lowerBound");
        long upper = getLong("prime.upperBound");
        if (lower > upper) {
            throw new IllegalArgumentException(
                    "prime.lowerBound must not exceed prime.upperBound");
        }

        long minimumNewsId = getLong("news.minId");
        long maximumNewsId = getLong("news.maxId");
        if (minimumNewsId < 1 || minimumNewsId > maximumNewsId) {
            throw new IllegalArgumentException("Invalid news ID range");
        }
        requireRange("dbwrite.payloadBytes", getInt("dbwrite.payloadBytes"),
                1, 1_048_576);

        int minimumIdle = getInt("database.minimumIdle");
        int maximumPoolSize = getInt("database.maximumPoolSize");
        requireRange("database.minimumIdle", minimumIdle, 0, 512);
        requireRange("database.maximumPoolSize", maximumPoolSize, 1, 512);
        if (minimumIdle > maximumPoolSize) {
            throw new IllegalArgumentException(
                    "database.minimumIdle must not exceed database.maximumPoolSize");
        }
        requireRange("database.connectionTimeoutMs",
                getInt("database.connectionTimeoutMs"), 250, 300_000);
        requireRange("database.validationTimeoutMs",
                getInt("database.validationTimeoutMs"), 250, 300_000);
        requireRange("database.idleTimeoutMs",
                getInt("database.idleTimeoutMs"), 10_000, Integer.MAX_VALUE);
        requireRange("database.maxLifetimeMs",
                getInt("database.maxLifetimeMs"), 30_000, Integer.MAX_VALUE);
    }

    private static IllegalArgumentException invalidNumber(
            String key,
            String value,
            String type,
            NumberFormatException cause) {
        return new IllegalArgumentException(
                "Configuration property " + key + " must be a valid "
                        + type + ", but was: " + value,
                cause);
    }

    private static void requireRange(String key, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    key + " must be between " + min + " and " + max);
        }
    }
}
