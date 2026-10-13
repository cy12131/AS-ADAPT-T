package com.example.adaptt.control;

import com.example.adaptt.config.AppConfig;

import java.util.Objects;

/** Immutable, validated parameters for AS-ADAPT-T. */
public record AsAdaptTConfig(
        int minimumPoolSize,
        int maximumPoolSize,
        int minimumStepSize,
        int maximumStepSize,
        double meanImprovementPercent,
        double maximumThroughputDegradationPercent,
        double maximumP99DegradationPercent,
        int referenceWindows,
        int changeDetectionWindows,
        double meanChangePercent,
        double p99ChangePercent,
        double throughputChangePercent,
        double queueGrowthPercent,
        double queueAssistedMeanChangePercent,
        double queueAssistedThroughputChangePercent,
        boolean stableHoldingEnabled) {

    public AsAdaptTConfig {
        if (minimumPoolSize < 1) {
            throw new IllegalArgumentException("minimumPoolSize must be at least 1");
        }
        if (maximumPoolSize < minimumPoolSize) {
            throw new IllegalArgumentException(
                    "maximumPoolSize must be at least minimumPoolSize");
        }
        if (minimumStepSize < 1) {
            throw new IllegalArgumentException("minimumStepSize must be at least 1");
        }
        if (maximumStepSize < minimumStepSize) {
            throw new IllegalArgumentException(
                    "maximumStepSize must be at least minimumStepSize");
        }
        requirePercent(meanImprovementPercent, "meanImprovementPercent");
        requirePercent(maximumThroughputDegradationPercent,
                "maximumThroughputDegradationPercent");
        requirePercent(maximumP99DegradationPercent,
                "maximumP99DegradationPercent");
        if (referenceWindows < 1) {
            throw new IllegalArgumentException("referenceWindows must be at least 1");
        }
        if (changeDetectionWindows < 1) {
            throw new IllegalArgumentException(
                    "changeDetectionWindows must be at least 1");
        }
        requirePercent(meanChangePercent, "meanChangePercent");
        requirePercent(p99ChangePercent, "p99ChangePercent");
        requirePercent(throughputChangePercent, "throughputChangePercent");
        requirePercent(queueGrowthPercent, "queueGrowthPercent");
        requirePercent(queueAssistedMeanChangePercent,
                "queueAssistedMeanChangePercent");
        requirePercent(queueAssistedThroughputChangePercent,
                "queueAssistedThroughputChangePercent");
    }

    public static AsAdaptTConfig from(AppConfig config) {
        Objects.requireNonNull(config, "config");
        return new AsAdaptTConfig(
                config.getInt("asAdapt.minPoolSize"),
                config.getInt("asAdapt.maxPoolSize"),
                config.getInt("asAdapt.minStepSize"),
                config.getInt("asAdapt.maxStepSize"),
                config.getDouble("asAdapt.meanImprovementPercent"),
                config.getDouble("asAdapt.maxThroughputDegradationPercent"),
                config.getDouble("asAdapt.maxP99DegradationPercent"),
                config.getInt("asAdapt.referenceWindows"),
                config.getInt("asAdapt.changeDetectionWindows"),
                config.getDouble("asAdapt.meanChangePercent"),
                config.getDouble("asAdapt.p99ChangePercent"),
                config.getDouble("asAdapt.throughputChangePercent"),
                config.getDouble("asAdapt.queueGrowthPercent"),
                config.getDouble("asAdapt.queueAssistedMeanChangePercent"),
                config.getDouble("asAdapt.queueAssistedThroughputChangePercent"),
                parseBoolean(config, "asAdapt.stableHoldingEnabled"));
    }

    private static boolean parseBoolean(AppConfig config, String key) {
        String value = config.getString(key);
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(
                key + " must be either true or false");
    }

    private static void requirePercent(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 100.0) {
            throw new IllegalArgumentException(name + " must be between 0 and 100");
        }
    }
}
