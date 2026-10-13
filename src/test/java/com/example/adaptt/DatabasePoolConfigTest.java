package com.example.adaptt;

import com.example.adaptt.config.AppConfig;
import com.example.adaptt.config.DatabasePool;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabasePoolConfigTest {
    @Test
    void createsStablePoolConfiguration() {
        HikariConfig hikari = DatabasePool.buildConfig(
                AppConfig.load(), "test-only-password");

        assertEquals(120, hikari.getMaximumPoolSize());
        assertEquals(10, hikari.getMinimumIdle());
        assertEquals(5_000L, hikari.getConnectionTimeout());
        assertEquals("adapt_t_app", hikari.getUsername());
        assertFalse(hikari.isReadOnly());
    }

    @Test
    void rejectsBlankPassword() {
        assertThrows(IllegalArgumentException.class,
                () -> DatabasePool.buildConfig(AppConfig.load(), " "));
    }
}
