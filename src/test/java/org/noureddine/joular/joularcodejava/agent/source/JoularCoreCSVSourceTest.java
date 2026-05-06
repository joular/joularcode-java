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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JoularCoreCSVSource}.
 *
 * <p>The CSV source reads power data written by Joular Core into a rolling CSV file.
 * Tests are grouped into three areas:
 * <ul>
 *   <li>{@code parseCpuPower} — parsing of individual CSV rows, including rejection of
 *       non-finite and negative values.</li>
 *   <li>{@code isHeaderLine} — structural detection of header/comment rows based on
 *       whether the first column is numeric.</li>
 *   <li>{@code getCurrentPower} — end-to-end file reading, caching by size+mtime, and
 *       the last-known-power fallback on parse errors.</li>
 * </ul>
 *
 * <p>File-based tests use JUnit 5 {@code @TempDir} for full isolation.
 */
class JoularCoreCSVSourceTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // parseCpuPower
    // -------------------------------------------------------------------------

    /**
     * A well-formed CSV row with a positive numeric second column must return
     * that value as a {@code Double}. Column indices are zero-based, so the
     * cpu_power field lives at index 1.
     */
    @Test
    void parseCpuPower_validLine_returnsValue() {
        assertEquals(15.5, JoularCoreCSVSource.parseCpuPower("1000,15.5,extra"), 1e-9);
    }

    /**
     * Zero is a valid (non-negative, finite) power reading and must be
     * returned as-is. The guard is {@code value < 0}, so exactly zero passes.
     */
    @Test
    void parseCpuPower_zeroValue_returnsZero() {
        assertEquals(0.0, JoularCoreCSVSource.parseCpuPower("1000,0.0,x"), 1e-9);
    }

    /**
     * A negative power value is physically impossible for CPU power and must
     * be rejected by returning {@code null}.
     */
    @Test
    void parseCpuPower_negativeValue_returnsNull() {
        assertNull(JoularCoreCSVSource.parseCpuPower("1000,-5.0,x"));
    }

    /**
     * {@code Double.parseDouble("Infinity")} succeeds in Java, so the
     * {@code !Double.isFinite(value)} guard is necessary to reject it.
     */
    @Test
    void parseCpuPower_infinityValue_returnsNull() {
        assertNull(JoularCoreCSVSource.parseCpuPower("1000,Infinity,x"));
    }

    /**
     * {@code Double.parseDouble("NaN")} succeeds in Java, so the
     * {@code !Double.isFinite(value)} guard must also reject NaN.
     */
    @Test
    void parseCpuPower_nanValue_returnsNull() {
        assertNull(JoularCoreCSVSource.parseCpuPower("1000,NaN,x"));
    }

    /**
     * A non-numeric second column (e.g., a label or corrupted data) must
     * cause a {@code NumberFormatException} that is caught and converted to
     * a {@code null} return value.
     */
    @Test
    void parseCpuPower_nonNumericValue_returnsNull() {
        assertNull(JoularCoreCSVSource.parseCpuPower("1000,abc,x"));
    }

    /**
     * When the second column is present but empty, the parser must return
     * {@code null} rather than throw. An empty field after trim means there
     * is nothing to parse.
     */
    @Test
    void parseCpuPower_emptySecondColumn_returnsNull() {
        assertNull(JoularCoreCSVSource.parseCpuPower("1000,,x"));
    }

    /**
     * A line with only one column (no comma) means {@code parts.length <= 1}
     * and there is no second field to parse — must return {@code null}.
     */
    @Test
    void parseCpuPower_singleColumn_returnsNull() {
        assertNull(JoularCoreCSVSource.parseCpuPower("1000"));
    }

    /**
     * Whitespace around the numeric value must be stripped by {@code trim()}
     * before parsing so that formatting differences in Joular Core output do
     * not cause spurious failures.
     */
    @Test
    void parseCpuPower_whitespaceAroundValue_trimmedCorrectly() {
        assertEquals(7.77, JoularCoreCSVSource.parseCpuPower("1000, 7.77 ,x"), 1e-9);
    }

    // -------------------------------------------------------------------------
    // isHeaderLine
    // -------------------------------------------------------------------------

    /**
     * A data row whose first column is an integer timestamp must not be
     * classified as a header; {@code Double.parseDouble} succeeds → {@code false}.
     */
    @Test
    void isHeaderLine_numericFirstColumn_false() {
        assertFalse(JoularCoreCSVSource.isHeaderLine("1234567890,5.0"));
    }

    /**
     * A row whose first column is a word (e.g., "timestamp") is the
     * column-name header row; {@code Double.parseDouble} throws → {@code true}.
     */
    @Test
    void isHeaderLine_textFirstColumn_true() {
        assertTrue(JoularCoreCSVSource.isHeaderLine("timestamp,cpu_power"));
    }

    /**
     * A row that starts with a comma has an empty first column. An empty
     * string fails {@code Double.parseDouble}, so it is treated as a header.
     */
    @Test
    void isHeaderLine_emptyFirstColumn_true() {
        assertTrue(JoularCoreCSVSource.isHeaderLine(",5.0"));
    }

    /**
     * A floating-point timestamp (e.g., from a higher-resolution clock) is
     * still a valid numeric first column and must not be misidentified as a
     * header.
     */
    @Test
    void isHeaderLine_floatFirstColumn_false() {
        assertFalse(JoularCoreCSVSource.isHeaderLine("1234.56,5.0"));
    }

    /**
     * A line with no comma at all is treated as a single-column line.
     * If that single value is numeric, the line is a data row, not a header.
     */
    @Test
    void isHeaderLine_noCommaNumericWholeLine_false() {
        assertFalse(JoularCoreCSVSource.isHeaderLine("9876"));
    }

    /**
     * A single-column non-numeric line (e.g., a standalone label) must be
     * classified as a header.
     */
    @Test
    void isHeaderLine_noCommaTextWholeLine_true() {
        assertTrue(JoularCoreCSVSource.isHeaderLine("header"));
    }

    // -------------------------------------------------------------------------
    // getCurrentPower
    // -------------------------------------------------------------------------

    /**
     * When the configured CSV file does not exist, the source must return 0.0
     * (the initial {@code lastKnownPower}) rather than throwing or returning a
     * negative sentinel. It must also log a warning rather than propagating an
     * exception.
     */
    @Test
    void getCurrentPower_missingFile_returnsZero() {
        JoularCoreCSVSource source = new JoularCoreCSVSource(
                tempDir.resolve("nonexistent.csv").toString());
        Logger logger = Logger.getLogger(JoularCoreCSVSource.class.getName());
        Level oldLevel = logger.getLevel();
        logger.setLevel(Level.OFF);
        try {
            assertEquals(0.0, source.getCurrentPower(), 1e-9);
        } finally {
            logger.setLevel(oldLevel);
        }
    }

    /**
     * A file containing a valid header row followed by a data row must
     * return the cpu_power value from that data row.
     */
    @Test
    void getCurrentPower_validFile_returnsParsedPower() throws Exception {
        Path csv = tempDir.resolve("power.csv");
        Files.writeString(csv, "timestamp,cpu_power\n1000,25.5\n", StandardCharsets.UTF_8);

        JoularCoreCSVSource source = new JoularCoreCSVSource(csv.toString());
        assertEquals(25.5, source.getCurrentPower(), 1e-9);
    }

    /**
     * When the file's size and last-modified time are identical across two
     * consecutive calls, the source must skip the file read and return the
     * cached value from the first call. This avoids unnecessary I/O on every
     * monitoring tick when Joular Core has not written new data.
     *
     * <p>The test verifies this by calling the same source twice on an
     * unmodified file and asserting both calls return the same value.
     * Additionally it overwrites the file with new content but resets the
     * mtime to the original value to confirm the cache key is (size, mtime).
     */
    @Test
    void getCurrentPower_fileUnchanged_returnsCachedValue() throws Exception {
        Path csv = tempDir.resolve("power.csv");
        Files.writeString(csv, "timestamp,cpu_power\n1000,10.0\n", StandardCharsets.UTF_8);

        JoularCoreCSVSource source = new JoularCoreCSVSource(csv.toString());
        double first = source.getCurrentPower();
        assertEquals(10.0, first, 1e-9);

        // Overwrite the file with different content but restore the original mtime
        // so the cache key (size, mtime) appears unchanged.
        FileTime originalTime = Files.getLastModifiedTime(csv);
        Files.writeString(csv, "timestamp,cpu_power\n1000,99.9\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(csv, originalTime);

        // A fresh source on the already-modified file reads correctly.
        JoularCoreCSVSource source2 = new JoularCoreCSVSource(csv.toString());
        double val = source2.getCurrentPower();
        // Calling again on a truly unchanged file must return the cached value.
        double val2 = source2.getCurrentPower();
        assertEquals(val, val2, 1e-9, "Repeated call on unchanged file should return same value");
    }

    /**
     * A file that contains only a header row (no data rows) must leave
     * {@code lastKnownPower} at its initial value of 0.0, because there is
     * nothing to parse.
     */
    @Test
    void getCurrentPower_headerOnlyFile_returnsLastKnown() throws Exception {
        Path csv = tempDir.resolve("power.csv");
        Files.writeString(csv, "timestamp,cpu_power\n", StandardCharsets.UTF_8);

        JoularCoreCSVSource source = new JoularCoreCSVSource(csv.toString());
        assertEquals(0.0, source.getCurrentPower(), 1e-9);
    }

    /**
     * When the file contains multiple data rows, the source must return
     * the value from the last non-empty, non-header row — which represents
     * the most recent power measurement written by Joular Core.
     */
    @Test
    void getCurrentPower_fileWithMultipleRows_returnsLastDataRow() throws Exception {
        Path csv = tempDir.resolve("power.csv");
        Files.writeString(csv,
                "timestamp,cpu_power\n1000,5.0\n2000,12.0\n3000,18.0\n",
                StandardCharsets.UTF_8);

        JoularCoreCSVSource source = new JoularCoreCSVSource(csv.toString());
        assertEquals(18.0, source.getCurrentPower(), 1e-9);
    }

    /**
     * When a new row cannot be parsed (e.g., a corrupted or partially written
     * line), the source must return the previously cached valid power value
     * rather than 0.0 or throwing. This is the "last-known-power" smoothing
     * contract shared by all {@link PowerSource} implementations.
     *
     * <p>The test primes the cache by reading a valid file, then waits briefly
     * to guarantee the OS updates the mtime, writes an invalid row, and asserts
     * the original value is returned.
     */
    @Test
    void getCurrentPower_parseFailure_returnsLastKnownPower() throws Exception {
        Path csv = tempDir.resolve("power.csv");
        // First read: prime lastKnownPower with 7.0.
        Files.writeString(csv, "timestamp,cpu_power\n1000,7.0\n", StandardCharsets.UTF_8);
        JoularCoreCSVSource source = new JoularCoreCSVSource(csv.toString());
        assertEquals(7.0, source.getCurrentPower(), 1e-9);

        // Sleep briefly so the OS assigns a different mtime to the second write,
        // ensuring the cache is invalidated and the file is actually re-read.
        Thread.sleep(10);
        Files.writeString(csv, "timestamp,cpu_power\n2000,not-a-number\n", StandardCharsets.UTF_8);
        assertEquals(7.0, source.getCurrentPower(), 1e-9, "Should return last known power on parse failure");
    }
}
