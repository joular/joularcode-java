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
     * {@code power-source-type=csv} must produce a {@link JoularCoreCSVSource}
     * instance. The CSV path falls back to the default value; no further
     * configuration is needed.
     */
    @Test
    void getPowerSource_csv_returnsCSVSource() throws Exception {
        AgentProperties props = propsWithContent("power-source-type=csv\n");
        PowerSource source = PowerSourceFactory.getPowerSource(props);
        assertNotNull(source);
        assertInstanceOf(JoularCoreCSVSource.class, source);
    }

    /**
     * {@code power-source-type=http} with a valid HTTP URL must produce a
     * {@link JoularCoreHttpSource} instance. The source is closed after the
     * assertion to release the internal executor thread.
     */
    @Test
    void getPowerSource_http_returnsHttpSource() throws Exception {
        AgentProperties props = propsWithContent(
                "power-source-type=http\njoular-core-http-url=http://localhost:9999/data\n");
        PowerSource source = PowerSourceFactory.getPowerSource(props);
        assertNotNull(source);
        assertInstanceOf(JoularCoreHttpSource.class, source);
        source.close();
    }

    /**
     * {@code power-source-type=ringbuffer} must produce a
     * {@link JoularCoreRingBufferSource} instance. The path falls back to the
     * OS-appropriate default; no file needs to exist for construction.
     */
    @Test
    void getPowerSource_ringbuffer_returnsRingBufferSource() throws Exception {
        AgentProperties props = propsWithContent("power-source-type=ringbuffer\n");
        PowerSource source = PowerSourceFactory.getPowerSource(props);
        assertNotNull(source);
        assertInstanceOf(JoularCoreRingBufferSource.class, source);
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
        assertInstanceOf(JoularCoreCSVSource.class, source);
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

    /**
     * When the HTTP URL has an invalid scheme (e.g., {@code ftp://}), the
     * {@link JoularCoreHttpSource} constructor throws an
     * {@link IllegalArgumentException}. The factory must catch it and return
     * {@code null} so that the agent can fail fast with a clear log message.
     */
    @Test
    void getPowerSource_invalidHttpUrl_returnsNull() throws Exception {
        AgentProperties props = propsWithContent(
                "power-source-type=http\njoular-core-http-url=ftp://bad-scheme\n");
        assertNull(PowerSourceFactory.getPowerSource(props));
    }

    /**
     * A URL without a scheme (e.g., {@code "localhost:8080"}) is parsed by
     * {@code java.net.URI} as having scheme {@code "localhost"}, which is not
     * {@code http} or {@code https}. The constructor throws
     * {@link IllegalArgumentException} and the factory must return {@code null}.
     *
     * <p>Note: {@link AgentProperties} replaces a genuinely empty property value
     * with its built-in default, so an empty {@code joular-core-http-url} would
     * not reach this path. This test uses a non-empty but scheme-less value
     * instead.
     */
    @Test
    void getPowerSource_noSchemeUrl_returnsNull() throws Exception {
        // "localhost:8080" parses as URI with scheme="localhost" (not http/https).
        AgentProperties props = propsWithContent(
                "power-source-type=http\njoular-core-http-url=localhost:8080\n");
        assertNull(PowerSourceFactory.getPowerSource(props));
    }
}
