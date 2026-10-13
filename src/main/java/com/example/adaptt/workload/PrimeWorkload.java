package com.example.adaptt.workload;

import com.example.adaptt.config.AppConfig;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/** CPU-bound primality workload based on the Prime1m case in the paper. */
public final class PrimeWorkload {
    private final long lowerBound;
    private final long upperBound;

    public PrimeWorkload(long lowerBound, long upperBound) {
        if (lowerBound < 0) {
            throw new IllegalArgumentException("lowerBound must not be negative");
        }
        if (lowerBound > upperBound) {
            throw new IllegalArgumentException(
                    "lowerBound must not exceed upperBound");
        }
        if (upperBound == Long.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "upperBound must be smaller than Long.MAX_VALUE");
        }
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
    }

    public static PrimeWorkload from(AppConfig config) {
        Objects.requireNonNull(config, "config");
        return new PrimeWorkload(
                config.getLong("prime.lowerBound"),
                config.getLong("prime.upperBound"));
    }

    public PrimeResult executeRandom() {
        long number = ThreadLocalRandom.current()
                .nextLong(lowerBound, upperBound + 1);
        return execute(number);
    }

    public PrimeResult execute(long number) {
        return new PrimeResult(number, isPrime(number));
    }

    /**
     * Deliberately straightforward trial division. The purpose is to provide a
     * visible CPU-bound request, not to benchmark an optimized prime library.
     */
    public static boolean isPrime(long number) {
        if (number < 2) {
            return false;
        }
        for (long divisor = 2; divisor < number; divisor++) {
            if (number % divisor == 0) {
                return false;
            }
        }
        return true;
    }

    public long lowerBound() {
        return lowerBound;
    }

    public long upperBound() {
        return upperBound;
    }
}
