package com.example.adaptt.control;

import com.example.adaptt.metrics.LatencyWindowSnapshot;
import com.example.adaptt.metrics.PoolWindowSnapshot;

/** Advances a worker-pool controller using one complete measurement window. */
public interface WindowController {
    ControlDecision onWindow(
            LatencyWindowSnapshot latency,
            PoolWindowSnapshot pool);
}
