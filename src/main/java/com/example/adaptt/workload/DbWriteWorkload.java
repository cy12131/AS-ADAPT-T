package com.example.adaptt.workload;

import com.example.adaptt.config.AppConfig;
import com.example.adaptt.config.DatabasePool;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/** One blocking, auto-commit MySQL insert per request. */
public final class DbWriteWorkload {
    private static final String INSERT_EVENT =
            "INSERT INTO events(event_type, payload, created_at) "
                    + "VALUES (?, ?, CURRENT_TIMESTAMP(6))";

    private final SqlConnectionProvider connections;
    private final String eventType;
    private final String payload;
    private final int payloadBytes;

    public DbWriteWorkload(
            SqlConnectionProvider connections,
            String eventType,
            int payloadBytes) {
        this.connections = Objects.requireNonNull(connections, "connections");
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (payloadBytes < 1 || payloadBytes > 1_048_576) {
            throw new IllegalArgumentException(
                    "payloadBytes must be between 1 and 1048576");
        }
        this.eventType = eventType;
        this.payloadBytes = payloadBytes;
        this.payload = "W".repeat(payloadBytes);
    }

    public static DbWriteWorkload from(
            AppConfig config,
            DatabasePool databasePool) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(databasePool, "databasePool");
        return new DbWriteWorkload(
                databasePool::connection,
                config.getString("dbwrite.eventType"),
                config.getInt("dbwrite.payloadBytes"));
    }

    public DbWriteResult execute() throws SQLException {
        try (Connection connection = connections.connection();
             PreparedStatement statement = connection.prepareStatement(
                     INSERT_EVENT,
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, eventType);
            statement.setString(2, payload);
            int updatedRows = statement.executeUpdate();
            if (updatedRows != 1) {
                throw new SQLException(
                        "DBWrite expected one inserted row but got " + updatedRows);
            }

            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                if (!generatedKeys.next()) {
                    throw new SQLException("DBWrite returned no generated event ID");
                }
                return new DbWriteResult(
                        generatedKeys.getLong(1), payloadBytes);
            }
        }
    }

    public String eventType() {
        return eventType;
    }

    public int payloadBytes() {
        return payloadBytes;
    }
}
