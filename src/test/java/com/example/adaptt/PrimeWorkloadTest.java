package com.example.adaptt;

import com.example.adaptt.workload.PrimeResult;
import com.example.adaptt.workload.PrimeWorkload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimeWorkloadTest {
    @Test
    void identifiesPrimeAndCompositeNumbers() {
        assertTrue(PrimeWorkload.isPrime(2));
        assertTrue(PrimeWorkload.isPrime(17));
        assertTrue(PrimeWorkload.isPrime(1_000_003));
        assertFalse(PrimeWorkload.isPrime(1));
        assertFalse(PrimeWorkload.isPrime(100));
        assertFalse(PrimeWorkload.isPrime(1_000_000));
    }

    @Test
    void randomInputsStayInsideConfiguredRange() {
        PrimeWorkload workload = new PrimeWorkload(999_990, 1_000_010);

        for (int i = 0; i < 1_000; i++) {
            PrimeResult result = workload.executeRandom();
            assertTrue(result.number() >= workload.lowerBound());
            assertTrue(result.number() <= workload.upperBound());
        }
    }

    @Test
    void rejectsInvalidRanges() {
        assertThrows(IllegalArgumentException.class,
                () -> new PrimeWorkload(10, 9));
        assertThrows(IllegalArgumentException.class,
                () -> new PrimeWorkload(-1, 10));
    }
}
