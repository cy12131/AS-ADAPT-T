package com.example.adaptt.workload;

/** Result of one durable event insert in the DBWrite workload. */
public record DbWriteResult(long id, int payloadBytes) {
}
