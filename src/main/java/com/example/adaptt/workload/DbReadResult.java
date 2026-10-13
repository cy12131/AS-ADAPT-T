package com.example.adaptt.workload;

/** One news article returned by the DBRead workload. */
public record DbReadResult(long id, String title, String body) {
}
