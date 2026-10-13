package com.example.adaptt.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/** Resets the dedicated DBWrite table before one automated experiment run. */
public final class DatabaseWriteReset {
    private DatabaseWriteReset() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        try (DatabasePool pool = DatabasePool.from(config);
             Connection connection = pool.connection();
             Statement statement = connection.createStatement()) {
            long rowsBefore = countRows(statement);
            statement.executeUpdate("TRUNCATE TABLE events");
            long rowsAfter = countRows(statement);
            if (rowsAfter != 0L) {
                throw new IllegalStateException(
                        "DBWrite reset left " + rowsAfter + " rows");
            }
            System.out.println("DBWrite reset: rows_before=" + rowsBefore
                    + " rows_after=" + rowsAfter);
        }
    }

    private static long countRows(Statement statement) throws Exception {
        try (ResultSet result = statement.executeQuery(
                "SELECT COUNT(*) FROM events")) {
            if (!result.next()) {
                throw new IllegalStateException("COUNT(*) returned no row");
            }
            return result.getLong(1);
        }
    }
}
