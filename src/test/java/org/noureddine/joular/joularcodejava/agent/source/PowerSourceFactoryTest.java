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

package org.noureddine.joular.joularcodejava.agent.source;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noureddine.joular.joularcodejava.agent.utils.AgentProperties;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PowerSourceFactory}.
 *
 * <p>The factory reads {@code power-source-type} from {@link AgentProperties}
 * and constructs the corresponding {@link PowerSource} implementation. Each test
 * writes a minimal properties file to a {@code @TempDir} and points the system
 * property {@code joularcodejava.properties} at it so that {@link AgentProperties}
 * picks up the desired configuration.
 *
 * <h2>System-property isolation</h2>
 * <p>The system property is cleared in {@code @AfterEach} to prevent any test
 * from leaking configuration state into subsequent tests, regardless of execution
 * order.
 *
 * <h2>AgentProperties default-fallback caveat</h2>
 * <p>When a property value is empty or whitespace-only, {@link AgentProperties}
 * returns the built-in default rather than an empty string. Tests that exercise
 * error paths therefore use an explicitly invalid (non-empty) value rather than
 * trying to pass an empty one.
 */
class PowerSourceFactoryTest {

    @TempDir
    Path tempDir;

    private Path propsFile;

    @BeforeEach
    void setUp() throws Exception {
        propsFile = tempDir.resolve("joularcodejava.properties");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("joularcodejava.properties");
    }

    /**
     * Writes {@code content} to the temp properties file and sets the system
     * property so that {@link AgentProperties} loads it.
     */
    private AgentProperties propsWithContent(String content) throws Exception {
        Files.writeString(propsFile, content, StandardCharsets.UTF_8);
        System.setProperty("joularcodejava.properties", propsFile.toString());
        return new AgentProperties();
    }

    // -------------------------------------------------------------------------
    // Successful dispatch
    // -------------------------------------------------------------------------

    /**
     * {@code power-source-type=csv} must produce a {@link PowerJoularCsvSource}
     * instance. The CSV path falls back to the default value; no further
     * configuration is needed.
     */
    @Test
    void getPowerSource_csv_returnsCSVSource() throws Exception {
        AgentProperties props = propsWithContent("power-source-type=csv\n");
        PowerSource source = PowerSourceFactory.getPowerSource(props);
        assertNotNull(source);
        assertInstanceOf(PowerJoularCsvSource.class, source);
    }

    /**
     * {@code power-source-type=ringbuffer} must produce a
     * {@link PowerJoularRingBufferSource} instance. The path falls back to the
     * OS-appropriate default; no file needs to exist for construction.
     */
    @Test
    void getPowerSource_ringbuffer_returnsRingBufferSource() throws Exception {
        AgentProperties props = propsWithContent("power-source-type=ringbuffer\n");
        PowerSource source = PowerSourceFactory.getPowerSource(props);
        assertNotNull(source);
        assertInstanceOf(PowerJoularRingBufferSource.class, source);
    }

    /**
     * {@code power-source-type=rapl} must produce a {@link LinuxRaplPowerSource}
     * instance. RAPL availability is checked during {@code initialize()}, so this
     * construction test is platform-independent.
     */
    @Test
    void getPowerSource_rapl_returnsLinuxRaplSource() throws Exception {
        AgentProperties props = propsWithContent("power-source-type=rapl\n");
        PowerSource source = PowerSourceFactory.getPowerSource(props);
        assertNotNull(source);
        assertInstanceOf(LinuxRaplPowerSource.class, source);
    }

    /**
     * The type string is normalised to lowercase before the switch statement,
     * so {@code "CSV"} (all-caps) must produce the same result as {@code "csv"}.
     */
    @Test
    void getPowerSource_csvUppercase_returnsCSVSource() throws Exception {
        AgentProperties props = propsWithContent("power-source-type=CSV\n");
        PowerSource source = PowerSourceFactory.getPowerSource(props);
        assertNotNull(source);
        assertInstanceOf(PowerJoularCsvSource.class, source);
    }

    // -------------------------------------------------------------------------
    // Error paths
    // -------------------------------------------------------------------------

    /**
     * An unrecognised type string must result in {@code null} being returned so
     * the agent can detect the misconfiguration and abort startup cleanly, rather
     * than attempting to use an undefined source.
     */
    @Test
    void getPowerSource_unknownType_returnsNull() throws Exception {
        AgentProperties props = propsWithContent("power-source-type=grpc\n");
        assertNull(PowerSourceFactory.getPowerSource(props));
    }
}
