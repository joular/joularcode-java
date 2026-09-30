/*
 * Copyright (c) 2026, Adel Noureddine.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the
 * GNU Lesser General Public License v3.0 (LGPL-3.0-only)
 * which accompanies this distribution, and is available at
 * https://www.gnu.org/licenses/lgpl-3.0.en.html
 *
 * Author : Adel Noureddine
 */

package org.noureddine.joular.joularcodejava.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearSystemProperty() {
        System.clearProperty("joularcodejava.properties");
    }

    private static Config config(String content) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(content));
        return Config.from(properties);
    }

    @Test
    void defaults() throws IOException {
        Config config = config("");

        assertEquals("auto", config.powerSourceType());
        assertNull(config.vmPowerFile());
        assertEquals("powerjoular", config.vmPowerFormat());
        assertEquals(10L, config.sampleRateMs());
        assertEquals(Path.of("joular-code-java-results"), config.resultsDir());
        assertTrue(config.appPrefixes().isEmpty());
    }

    /** A value of blanks only is the same as no value. */
    @Test
    void blankValue_usesTheDefault() throws IOException {
        assertEquals("auto", config("power-source-type=   \n").powerSourceType());
    }

    @Test
    void values_areReadAndTrimmed() throws IOException {
        Config config = config("""
                power-source-type = RingBuffer
                powerjoular-ringbuffer-path = /custom/ring
                vm-power-file = /mnt/host/vm-power.csv
                vm-power-format = Watts
                stack-monitoring-sample-rate = 50
                results-path = out
                """);

        assertEquals("ringbuffer", config.powerSourceType(), "the type is not case sensitive");
        assertEquals(Path.of("/custom/ring"), config.ringBufferPath());
        assertEquals(Path.of("/mnt/host/vm-power.csv"), config.vmPowerFile());
        assertEquals("watts", config.vmPowerFormat());
        assertEquals(50L, config.sampleRateMs());
        assertEquals(Path.of("out"), config.resultsDir());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "fast", "1001", "99999999999999999999"})
    void invalidSampleRate_usesTheDefault(String value) throws IOException {
        assertEquals(Config.DEFAULT_SAMPLE_RATE_MS, config("stack-monitoring-sample-rate=" + value).sampleRateMs());
    }

    @Test
    void prefixes_areSplitTrimmedAndEmptyOnesDropped() throws IOException {
        assertEquals(List.of("com.example", "org.myapp", "io.foo"),
                config("methods-filtering-prefix=com.example, org.myapp ,, io.foo").appPrefixes());
    }

    @Test
    void ringBufferPath_defaultDependsOnTheOs() throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String path = config("").ringBufferPath().toString();

        if (os.contains("win")) {
            assertTrue(path.endsWith("\\powerjoular"), path);
        } else if (os.contains("mac")) {
            assertEquals("/tmp/powerjoular", path);
        } else {
            assertEquals("/dev/shm/powerjoular", path);
        }
    }

    @Test
    void load_readsTheFileNamedBySystemProperty() throws IOException {
        Path file = tempDir.resolve("custom.properties");
        Files.writeString(file, "stack-monitoring-sample-rate=25\n", StandardCharsets.UTF_8);
        System.setProperty("joularcodejava.properties", file.toString());

        assertEquals(25L, Config.load().sampleRateMs());
    }

    @Test
    void load_missingFile_usesDefaults() {
        System.setProperty("joularcodejava.properties", tempDir.resolve("missing.properties").toString());

        assertEquals(Config.DEFAULT_SAMPLE_RATE_MS, Config.load().sampleRateMs());
    }
}
