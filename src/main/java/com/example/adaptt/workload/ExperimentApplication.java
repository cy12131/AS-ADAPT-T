package com.example.adaptt.workload;

import java.util.Locale;

public enum ExperimentApplication {
    PRIME("/prime"),
    DBREAD("/dbread"),
    DBWRITE("/dbwrite");

    private final String endpoint;

    ExperimentApplication(String endpoint) {
        this.endpoint = endpoint;
    }

    public String endpoint() {
        return endpoint;
    }

    public static ExperimentApplication parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
