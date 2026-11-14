package com.smartwater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {

    @TempDir
    Path dir;

    @Test
    void sharedConstantsMatchTheContract() {
        assertEquals("tcp://localhost:1883", Config.BROKER_URL);
        assertEquals(1000, Config.RESERVOIR_INITIAL);
        assertEquals(200, Config.QUOTA_INITIAL);
        assertEquals("keys", Config.KEYS_DIR);
        assertEquals(Map.of("A", 200, "B", 200, "C", 200), Config.initialQuotas());
    }

    @Test
    void passwordComesFromTheEnvironmentFirst() throws IOException {
        Path file = dir.resolve("pw.properties");
        Files.writeString(file, "A=depuis-fichier\n");
        String pw = Config.resolvePassword("A",
                name -> name.equals("SMARTWATER_MQTT_PASSWORD_A") ? "depuis-env" : null, file);
        assertEquals("depuis-env", pw);
    }

    @Test
    void passwordFallsBackToTheLocalFile() throws IOException {
        Path file = dir.resolve("pw.properties");
        Files.writeString(file, "B=secret-b\nDASHBOARD=secret-d\n");
        assertEquals("secret-b", Config.resolvePassword("B", name -> null, file));
        assertEquals("secret-d", Config.resolvePassword("DASHBOARD", name -> null, file));
    }

    @Test
    void missingPasswordGivesAClearErrorWithoutLeakingAnything() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Config.resolvePassword("C", name -> null, dir.resolve("absent.properties")));
        assertTrue(e.getMessage().contains("SMARTWATER_MQTT_PASSWORD_C"), e.getMessage());
    }

    @Test
    void unknownMqttClientIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Config.resolvePassword("D", name -> "x", dir.resolve("pw.properties")));
    }
}
