package com.example.adaptt;

import com.example.adaptt.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppConfigTest {
    @AfterEach
    void clearOverrides() {
        System.clearProperty("server.port");
        System.clearProperty("worker.initialSize");
    }

    @Test
    void loadsBundledConfiguration() {
        AppConfig config = AppConfig.load();

        assertEquals(8080, config.getInt("server.port"));
        assertEquals(10, config.getInt("worker.initialSize"));
        assertEquals("PRIME", config.getString("experiment.application"));
        assertEquals(5, config.getInt("queueBaseline.stepSize"));
        assertEquals(5, config.getInt("queueBaseline.highQueueThreshold"));
        assertEquals(2, config.getInt("queueBaseline.consecutiveWindows"));
    }

    @Test
    void systemPropertyOverridesBundledValue() {
        System.setProperty("server.port", "9090");

        AppConfig config = AppConfig.load();

        assertEquals(9090, config.getInt("server.port"));
    }

    @Test
    void rejectsInitialWorkerCountOutsideBounds() {
        System.setProperty("worker.initialSize", "1000");

        assertThrows(IllegalArgumentException.class, AppConfig::load);
    }
}
