package com.example.adaptt.control;

import com.example.adaptt.metrics.LatencyWindowSnapshot;

public enum AdaptObjective {
    MEAN {
        @Override
        public double valueFrom(LatencyWindowSnapshot snapshot) {
            return snapshot.meanLatencyMs();
        }
    },
    P99 {
        @Override
        public double valueFrom(LatencyWindowSnapshot snapshot) {
            return snapshot.p99LatencyMs();
        }
    };

    public abstract double valueFrom(LatencyWindowSnapshot snapshot);
}
