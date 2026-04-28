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

package org.noureddine.joular.joularcodejava.agent.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgentProperties}.
 *
 * <p>{@code AgentProperties} loads configuration in priority order:
 * <ol>
 *   <li>The classpath resource {@code joularcodejava.properties} (all values
 *       intentionally empty so the defaults are defined in code, not in the file).</li>
 *   <li>The file path pointed to by the system property
 *       {@code joularcodejava.properties} (if set).</li>
 *   <li>A {@code joularcodejava.properties} file in the working directory
 *       (only consulted if the system property is absent).</li>
 * </ol>
 *
 * <h2>Test isolation</h2>
 * <p>Every test sets the {@code joularcodejava.properties} system property before
 * constructing {@link AgentProperties} and clears it in {@code @AfterEach} so that
 * no test leaks configuration to its successors regardless of execution order.
 *
 * <p>Tests that want the built-in defaults point the system property at a path that
 * does not exist on disk; {@code AgentProperties} then falls back to the classpath
 * defaults without reading any external file.
 */
class AgentPropertiesTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        System.clearProperty("joularcodejava.properties");
    }

    /**
     * Constructs an {@link AgentProperties} backed only by the classpath defaults.
     * The system property is set to a non-existent path so neither the
     * system-property path nor the working-directory fallback can accidentally
     * load a local file during development.
     */
    private AgentProperties defaults() {
        System.setProperty("joularcodejava.properties",
                tempDir.resolve("nonexistent.properties").toString());
        return new AgentProperties();
    }

    /**
     * Writes {@code content} to a temp properties file, sets the system property
     * to point at it, and returns a freshly constructed {@link AgentProperties}.
     */
    private AgentProperties withContent(String content) throws Exception {
        Path f = tempDir.resolve("joularcodejava.properties");
        Files.writeString(f, content, StandardCharsets.UTF_8);
        System.setProperty("joularcodejava.properties", f.toString());
        return new AgentProperties();
    }

    // -------------------------------------------------------------------------
    // Default values (no external file)
    // -------------------------------------------------------------------------

    /**
     * When no external file is loaded, the power source type must default to
     * {@code "ringbuffer"} — the lowest-latency source, preferred over CSV or HTTP.
     */
    @Test
    void defaults_powerSourceType_ringbuffer() {
        assertEquals("ringbuffer", defaults().getPowerSourceType());
    }

    /**
     * The default CSV file path is {@code "joularcore-data.csv"}, resolved
     * relative to the agent's working directory.
     */
    @Test
    void defaults_csvPath_default() {
        assertEquals("joularcore-data.csv", defaults().getJoularCoreCsvPath());
    }

    /**
     * The default HTTP endpoint assumes a locally running Joular Core instance
     * on the standard port.
     */
    @Test
    void defaults_httpUrl_default() {
        assertEquals("http://localhost:8080/data", defaults().getJoularCoreHttpUrl());
    }

    /**
     * The default stack-sampling interval is 10 ms, a balance between
     * attribution accuracy and monitoring overhead.
     */
    @Test
    void defaults_sampleRate_ten() {
        assertEquals(10L, defaults().getSampleRateMs());
    }

    /**
     * The default results directory is {@code "joular-code-java-results"},
     * resolved relative to the agent's working directory.
     */
    @Test
    void defaults_resultsPath_default() {
        assertEquals("joular-code-java-results", defaults().getResultsPath());
    }

    /**
     * When no filtering prefix is configured, the returned list must be empty,
     * which means all observed branches appear in both output files.
     */
    @Test
    void defaults_filteringPrefixes_empty() {
        assertTrue(defaults().getMethodsFilteringPrefixes().isEmpty());
    }

    // -------------------------------------------------------------------------
    // Override from external file
    // -------------------------------------------------------------------------

    /**
     * A value supplied in the external file overrides the classpath default.
     * Setting {@code power-source-type=csv} must be reflected by the getter.
     */
    @Test
    void fromFile_powerSourceType_csv() throws Exception {
        assertEquals("csv", withContent("power-source-type=csv\n").getPowerSourceType());
    }

    /**
     * A property set to whitespace only is treated the same as absent: after
     * trimming, the value is empty, so the code falls back to the default.
     */
    @Test
    void fromFile_emptyValue_usesDefault() throws Exception {
        // Three spaces → trim → "" → use default "ringbuffer"
        assertEquals("ringbuffer", withContent("power-source-type=   \n").getPowerSourceType());
    }

    /**
     * A valid positive integer for {@code stack-monitoring-sample-rate} must
     * be returned as a {@code long} without modification.
     */
    @Test
    void fromFile_sampleRate_50() throws Exception {
        assertEquals(50L, withContent("stack-monitoring-sample-rate=50\n").getSampleRateMs());
    }

    /**
     * Zero is not a valid sample rate (the constraint is {@code > 0}), so
     * the property must be rejected and the default 10 ms returned.
     */
    @Test
    void fromFile_zeroSampleRate_usesDefault() throws Exception {
        assertEquals(10L, withContent("stack-monitoring-sample-rate=0\n").getSampleRateMs());
    }

    /**
     * A negative value is also not a valid sample rate; the default must
     * be returned and a warning logged.
     */
    @Test
    void fromFile_negativeSampleRate_usesDefault() throws Exception {
        assertEquals(10L, withContent("stack-monitoring-sample-rate=-5\n").getSampleRateMs());
    }

    /**
     * A non-numeric value causes a {@code NumberFormatException} inside
     * {@code getPositiveLongProperty}, which logs a warning and returns the
     * default rather than propagating the exception.
     */
    @Test
    void fromFile_nonNumericSampleRate_usesDefault() throws Exception {
        assertEquals(10L, withContent("stack-monitoring-sample-rate=fast\n").getSampleRateMs());
    }

    /**
     * The CSV file path accepts any string value without validation, because
     * the file is opened lazily during the monitoring loop rather than at
     * configuration time.
     */
    @Test
    void fromFile_csvPath_customValue() throws Exception {
        assertEquals("/data/power.csv",
                withContent("joular-core-csv-path=/data/power.csv\n").getJoularCoreCsvPath());
    }

    /**
     * A fully qualified HTTP URL — including scheme, host, port, and path —
     * must be returned verbatim; no parsing or normalisation is applied
     * at the properties level.
     */
    @Test
    void fromFile_httpUrl_customValue() throws Exception {
        assertEquals("http://192.168.1.1:9090/power",
                withContent("joular-core-http-url=http://192.168.1.1:9090/power\n")
                        .getJoularCoreHttpUrl());
    }

    /**
     * The results directory path accepts any string value and is returned
     * without modification.
     */
    @Test
    void fromFile_resultsPath_customValue() throws Exception {
        assertEquals("/tmp/results",
                withContent("results-path=/tmp/results\n").getResultsPath());
    }

    /**
     * A single prefix must be parsed into a one-element list with the value
     * trimmed.
     */
    @Test
    void fromFile_filteringPrefixes_single() throws Exception {
        List<String> prefixes = withContent("methods-filtering-prefix=com.example\n")
                .getMethodsFilteringPrefixes();
        assertEquals(List.of("com.example"), prefixes);
    }

    /**
     * Multiple comma-separated prefixes must each be individually trimmed of
     * surrounding whitespace and returned as separate list entries.
     */
    @Test
    void fromFile_filteringPrefixes_multiple_trimmed() throws Exception {
        List<String> prefixes = withContent(
                "methods-filtering-prefix=com.example, org.myapp , io.foo\n")
                .getMethodsFilteringPrefixes();
        assertEquals(List.of("com.example", "org.myapp", "io.foo"), prefixes);
    }

    /**
     * Empty tokens produced by consecutive commas (e.g., {@code "com.a,,com.b"})
     * must be silently discarded; only non-blank entries appear in the result.
     */
    @Test
    void fromFile_filteringPrefixes_emptyTokensSkipped() throws Exception {
        List<String> prefixes = withContent(
                "methods-filtering-prefix=com.a,,com.b\n")
                .getMethodsFilteringPrefixes();
        assertEquals(List.of("com.a", "com.b"), prefixes);
    }

    // -------------------------------------------------------------------------
    // OS-dependent ring-buffer path
    // -------------------------------------------------------------------------

    /**
     * The default ring buffer path is determined at runtime by inspecting
     * {@code os.name}:
     * <ul>
     *   <li>Windows → {@code "Local\JoularCoreRing"} (named file mapping)</li>
     *   <li>macOS   → {@code "/tmp/joularcorering"}</li>
     *   <li>Linux   → {@code "/dev/shm/joularcorering"} (shared-memory filesystem)</li>
     * </ul>
     * The test branches on the current platform so it remains correct in all
     * three CI environments.
     */
    @Test
    void getRingBufferPath_default_isOsDependent() {
        String os = System.getProperty("os.name").toLowerCase();
        String path = defaults().getRingBufferPath();
        assertNotNull(path);
        assertFalse(path.isEmpty());
        if (os.contains("win")) {
            assertTrue(path.contains("JoularCoreRing"), "Windows path: " + path);
        } else if (os.contains("mac")) {
            assertEquals("/tmp/joularcorering", path);
        } else {
            assertEquals("/dev/shm/joularcorering", path);
        }
    }

    /**
     * An explicitly configured ring buffer path must override the OS-dependent
     * default, allowing the agent to point at a non-standard location.
     */
    @Test
    void getRingBufferPath_customValue_returnsCustom() throws Exception {
        assertEquals("/custom/ring",
                withContent("joular-core-ringbuffer-path=/custom/ring\n").getRingBufferPath());
    }
}
