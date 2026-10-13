package com.example.adaptt.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/** HikariCP connection pool shared by DBRead and DBWrite workloads. */
public final class DatabasePool implements AutoCloseable {
    private final HikariDataSource dataSource;

    private DatabasePool(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public static DatabasePool from(AppConfig config) {
        Objects.requireNonNull(config, "config");
        HikariConfig hikariConfig = buildConfig(
                config, config.databasePassword());
        return new DatabasePool(new HikariDataSource(hikariConfig));
    }

    /** Public for deterministic configuration tests; does not open connections. */
    public static HikariConfig buildConfig(AppConfig config, String password) {
        Objects.requireNonNull(config, "config");
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Database password must not be blank");
        }

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("adapt-t-database");
        hikari.setJdbcUrl(config.getString("database.jdbcUrl"));
        hikari.setUsername(config.getString("database.username"));
        hikari.setPassword(password);
        hikari.setMaximumPoolSize(config.getInt("database.maximumPoolSize"));
        hikari.setMinimumIdle(config.getInt("database.minimumIdle"));
        hikari.setConnectionTimeout(config.getLong("database.connectionTimeoutMs"));
        hikari.setValidationTimeout(config.getLong("database.validationTimeoutMs"));
        hikari.setIdleTimeout(config.getLong("database.idleTimeoutMs"));
        hikari.setMaxLifetime(config.getLong("database.maxLifetimeMs"));
        hikari.setAutoCommit(true);
        hikari.setReadOnly(false);
        hikari.setInitializationFailTimeout(
                config.getLong("database.connectionTimeoutMs"));

        // Stable prepared-statement behavior for all experiment runs.
        hikari.addDataSourceProperty("cachePrepStmts", "true");
        hikari.addDataSourceProperty("prepStmtCacheSize", "250");
        hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikari.addDataSourceProperty("useServerPrepStmts", "true");
        return hikari;
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    /** Fails startup before a benchmark begins if credentials or schema are invalid. */
    public void verify() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            if (!result.next() || result.getInt(1) != 1) {
                throw new SQLException("Unexpected database verification result");
            }
        }
    }

    public int activeConnections() {
        return poolBean().getActiveConnections();
    }

    public int idleConnections() {
        return poolBean().getIdleConnections();
    }

    public int totalConnections() {
        return poolBean().getTotalConnections();
    }

    public int threadsAwaitingConnection() {
        return poolBean().getThreadsAwaitingConnection();
    }

    private HikariPoolMXBean poolBean() {
        return dataSource.getHikariPoolMXBean();
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
