package com.example.adaptt.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/** Read-only command-line diagnostic for the experiment database connection. */
public final class DatabaseConnectionCheck {
    private DatabaseConnectionCheck() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        try (DatabasePool pool = DatabasePool.from(config)) {
            pool.verify();

            try (Connection connection = pool.connection();
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT DATABASE(), CURRENT_USER(), VERSION()")) {
                if (!result.next()) {
                    throw new IllegalStateException(
                            "Database identity query returned no rows");
                }
                System.out.println("Database verification: OK");
                System.out.println("Database: " + result.getString(1));
                System.out.println("Current user: " + result.getString(2));
                System.out.println("MySQL version: " + result.getString(3));
            }

            System.out.println("Pool total connections: " + pool.totalConnections());
            System.out.println("Pool active connections: " + pool.activeConnections());
            System.out.println("Pool idle connections: " + pool.idleConnections());
            System.out.println("Threads awaiting connection: "
                    + pool.threadsAwaitingConnection());
        }
    }
}
