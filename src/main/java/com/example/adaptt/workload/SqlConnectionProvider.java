package com.example.adaptt.workload;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface SqlConnectionProvider {
    Connection connection() throws SQLException;
}
