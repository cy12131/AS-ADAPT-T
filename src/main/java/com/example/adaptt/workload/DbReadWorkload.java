package com.example.adaptt.workload;

import com.example.adaptt.config.AppConfig;
import com.example.adaptt.config.DatabasePool;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/** Blocking primary-key lookup workload over the immutable news dataset. */
public final class DbReadWorkload {
    private static final String SELECT_NEWS =
            "SELECT title, body FROM news WHERE id = ?";

    private final SqlConnectionProvider connections;
    private final long minimumId;
    private final long maximumId;

    public DbReadWorkload(
            SqlConnectionProvider connections,
            long minimumId,
            long maximumId) {
        this.connections = Objects.requireNonNull(connections, "connections");
        if (minimumId < 1 || minimumId > maximumId) {
            throw new IllegalArgumentException("Invalid DBRead ID range");
        }
        if (maximumId == Long.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "maximumId must be smaller than Long.MAX_VALUE");
        }
        this.minimumId = minimumId;
        this.maximumId = maximumId;
    }

    public static DbReadWorkload from(AppConfig config, DatabasePool databasePool) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(databasePool, "databasePool");
        return new DbReadWorkload(
                databasePool::connection,
                config.getLong("news.minId"),
                config.getLong("news.maxId"));
    }

    public DbReadResult executeRandom() throws SQLException {
        return execute(nextId());
    }

    public DbReadResult execute(long id) throws SQLException {
        if (id < minimumId || id > maximumId) {
            throw new IllegalArgumentException(
                    "News ID is outside the configured DBRead range");
        }

        try (Connection connection = connections.connection();
             PreparedStatement statement = connection.prepareStatement(SELECT_NEWS)) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new NoSuchElementException("News article not found: " + id);
                }
                return new DbReadResult(
                        id,
                        result.getString("title"),
                        result.getString("body"));
            }
        }
    }

    public long nextId() {
        return ThreadLocalRandom.current().nextLong(minimumId, maximumId + 1);
    }

    public long minimumId() {
        return minimumId;
    }

    public long maximumId() {
        return maximumId;
    }
}
