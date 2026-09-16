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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.noureddine.joular.joularcodejava.agent.source.PowerJoularCsvSource.SYSTEM_CPU_POWER_INDEX;
import static org.noureddine.joular.joularcodejava.agent.source.PowerSource.MAX_STALE_CYCLES;
import static org.noureddine.joular.joularcodejava.agent.source.TestLogging.quietly;

/**
 * Unit tests for {@link PowerJoularCsvSource}.
 *
 * <p>Fixtures are real PowerJoular rows. The system file it writes with {@code -f} or {@code -o} is
 * {@code Timestamp,CPU Usage,Total Power,CPU Power,GPU Power}, so CPU Power sits at index 3 and not
 * at index 1, which is CPU Usage.
 *
 * <p>Timestamps are written as the current time: the source ignores rows older than ten seconds, so
 * a fixed timestamp would make every file look like one left behind by a PowerJoular that stopped.
 */
class PowerJoularCsvSourceTest {

    private static final String SYSTEM_HEADER = "Timestamp,CPU Usage,Total Power,CPU Power,GPU Power";
    private static final String TARGET_HEADER = "Timestamp,CPU Usage,CPU Power";

    @TempDir
    Path tempDir;

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    /** One system row with the given CPU Power and a current timestamp. */
    private static String systemRow(double cpuPower) {
        return now() + ",0.2460,18.4500," + cpuPower + ",3.2500";
    }

    /** One row of the file PowerJoular writes for a monitored process. */
    private static String targetRow(double cpuPower) {
        return now() + ",0.0310," + cpuPower;
    }

    private Path writeCsv(String content) throws Exception {
        Path csv = tempDir.resolve("powerjoular.csv");
        Files.writeString(csv, content, StandardCharsets.UTF_8);
        return csv;
    }

    private static Double parseSystemRow(String row) {
        return PowerJoularCsvSource.parseCpuPower(row.split(",", -1), SYSTEM_CPU_POWER_INDEX);
    }

    // -------------------------------------------------------------------------
    // parseCpuPower
    // -------------------------------------------------------------------------

    /**
     * A well formed system row must give back the value in the CPU Power column, not another one.
     * Whitespace around the value is stripped, and zero is a real reading: a CPU that was read and
     * drew no measurable power.
     */
    @ParameterizedTest(name = "{0} -> {1} W")
    @CsvSource(delimiter = '|', value = {
            "1756681930,0.2460,18.4500,15.2000,3.2500 | 15.2",
            "1756681930,0.0,0.0,0.0,0.0               | 0.0",
            "1756681930,0.2,18.4, 7.77 ,3.2           | 7.77",
    })
    void parseCpuPower_validRow_returnsCpuPower(String row, double expected) {
        assertEquals(expected, parseSystemRow(row), 1e-9);
    }

    /**
     * Values that are not a power must come back as null rather than throw. PowerJoular writes -1 for
     * a value it could not read at all, which is not the same as zero; Infinity and NaN both parse
     * successfully in Java, so they need rejecting explicitly; and a row can be corrupted or half
     * written.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "1756681930,0.2460,18.4500,-1.0000,3.2500", // unreadable sentinel
            "1756681930,0.2,18.4,Infinity,3.2",
            "1756681930,0.2,18.4,NaN,3.2",
            "1756681930,0.2,18.4,abc,3.2",
            "1756681930,0.2,18.4,,3.2",                 // empty column
            "1756681930,0.2",                           // row shorter than the index
    })
    void parseCpuPower_unusableRow_returnsNull(String row) {
        assertNull(parseSystemRow(row));
    }

    /** A column index of -1 stands for a column that could not be worked out. */
    @Test
    void parseCpuPower_unresolvedColumn_returnsNull() {
        assertNull(PowerJoularCsvSource.parseCpuPower("1756681930,0.2,18.4,15.2,3.2".split(",", -1), -1));
    }

    // -------------------------------------------------------------------------
    // parseTimestamp
    // -------------------------------------------------------------------------

    /** A data row opens with a time, whole or fractional. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1756681930    | 1756681930",
            "1756681930.5  | 1756681930.5",
            "  1756681930  | 1756681930",
    })
    void parseTimestamp_numericFirstColumn_returnsTime(String firstColumn, double expected) {
        assertEquals(expected, PowerJoularCsvSource.parseTimestamp(firstColumn), 1e-9);
    }

    /** Anything that is not a time marks the header row, a comment, or an empty column. */
    @ParameterizedTest
    @ValueSource(strings = {"Timestamp", "header", "", "   "})
    void parseTimestamp_nonNumericFirstColumn_returnsNull(String firstColumn) {
        assertNull(PowerJoularCsvSource.parseTimestamp(firstColumn));
    }

    // -------------------------------------------------------------------------
    // getCurrentPower
    // -------------------------------------------------------------------------

    /** A file that is not there yet must give zero and a warning, not an exception. */
    @Test
    void getCurrentPower_missingFile_returnsZero() throws Exception {
        PowerJoularCsvSource source = new PowerJoularCsvSource(
                tempDir.resolve("nonexistent.csv").toString());
        quietly(PowerJoularCsvSource.class, () -> assertEquals(0.0, source.getCurrentPower(), 1e-9));
    }

    /** The file PowerJoular writes with -f: a header, then rows. */
    @Test
    void getCurrentPower_systemFileWithHeader_returnsCpuPower() throws Exception {
        Path csv = writeCsv(SYSTEM_HEADER + "\n" + systemRow(15.2) + "\n");

        PowerJoularCsvSource source = new PowerJoularCsvSource(csv.toString());
        assertEquals(15.2, source.getCurrentPower(), 1e-9);
    }

    /**
     * The file PowerJoular writes with -o: the latest row only, and no header. The column is worked
     * out from the number of columns, which is what makes both forms readable.
     */
    @Test
    void getCurrentPower_headerlessSystemFile_returnsCpuPower() throws Exception {
        Path csv = writeCsv(systemRow(12.5) + "\n");

        PowerJoularCsvSource source = new PowerJoularCsvSource(csv.toString());
        assertEquals(12.5, source.getCurrentPower(), 1e-9);
    }

    /**
     * The three column file holds the power of one monitored process, which the agent would scale by
     * the share of the machine the JVM is using a second time. It must be refused, not read.
     */
    @Test
    void getCurrentPower_targetFile_isRejected() throws Exception {
        Path csv = writeCsv(targetRow(1.84) + "\n");

        PowerJoularCsvSource source = new PowerJoularCsvSource(csv.toString());
        quietly(PowerJoularCsvSource.class, () -> assertEquals(0.0, source.getCurrentPower(), 1e-9,
                "The per-process file must be refused rather than scaled twice"));
    }

    /**
     * The same refusal when the source sees the header before any row has been written, which is what
     * {@code powerjoular -f} does: it creates the file with its header and adds the first row a second
     * later. Reading the header first must not teach the source a column and so talk it past the
     * refusal on the following cycle.
     */
    @Test
    void getCurrentPower_targetFileHeaderSeenFirst_isStillRejected() throws Exception {
        Path csv = writeCsv(TARGET_HEADER + "\n");

        PowerJoularCsvSource source = new PowerJoularCsvSource(csv.toString());
        quietly(PowerJoularCsvSource.class, () -> {
            assertEquals(0.0, source.getCurrentPower(), 1e-9, "A header on its own carries no power");

            Files.writeString(csv, TARGET_HEADER + "\n" + targetRow(1.84) + "\n", StandardCharsets.UTF_8);
            assertEquals(0.0, source.getCurrentPower(), 1e-9,
                    "The row must still be refused once it arrives");
        });
    }

    /** A file holding only its header has nothing to read yet. */
    @Test
    void getCurrentPower_headerOnlyFile_returnsZero() throws Exception {
        Path csv = writeCsv(SYSTEM_HEADER + "\n");

        PowerJoularCsvSource source = new PowerJoularCsvSource(csv.toString());
        assertEquals(0.0, source.getCurrentPower(), 1e-9);
    }

    /** With several rows, the last one is the newest measurement. */
    @Test
    void getCurrentPower_multipleRows_returnsLastRow() throws Exception {
        Path csv = writeCsv(SYSTEM_HEADER + "\n"
                + systemRow(5.0) + "\n"
                + systemRow(12.0) + "\n"
                + systemRow(18.0) + "\n");

        PowerJoularCsvSource source = new PowerJoularCsvSource(csv.toString());
        assertEquals(18.0, source.getCurrentPower(), 1e-9);
    }

    /**
     * A file left behind by a PowerJoular that has stopped still parses, so it is the timestamp that
     * has to rule it out.
     */
    @Test
    void getCurrentPower_staleRow_returnsZero() throws Exception {
        Path csv = writeCsv(SYSTEM_HEADER + "\n" + (now() - 3600) + ",0.2460,18.4500,15.2000,3.2500\n");

        PowerJoularCsvSource source = new PowerJoularCsvSource(csv.toString());
        quietly(PowerJoularCsvSource.class, () -> assertEquals(0.0, source.getCurrentPower(), 1e-9,
                "A row an hour old must not be taken for a current measurement"));
    }

    /** A row that cannot be parsed is covered by the last value we trust. */
    @Test
    void getCurrentPower_parseFailure_returnsLastKnownPower() throws Exception {
        Path csv = writeCsv(SYSTEM_HEADER + "\n" + systemRow(7.0) + "\n");
        PowerJoularCsvSource source = new PowerJoularCsvSource(csv.toString());
        assertEquals(7.0, source.getCurrentPower(), 1e-9);

        Files.writeString(csv, SYSTEM_HEADER + "\n" + now() + ",0.2460,18.4500,not-a-number,3.2500\n",
                StandardCharsets.UTF_8);
        assertEquals(7.0, source.getCurrentPower(), 1e-9,
                "A single unreadable cycle must be covered by the last known power");
    }

    /** In overwrite mode PowerJoular empties the file before writing, so a read can land on nothing. */
    @Test
    void getCurrentPower_emptyFile_returnsLastKnownPower() throws Exception {
        Path csv = writeCsv(systemRow(9.5) + "\n");
        PowerJoularCsvSource source = new PowerJoularCsvSource(csv.toString());
        assertEquals(9.5, source.getCurrentPower(), 1e-9);

        Files.writeString(csv, "", StandardCharsets.UTF_8);
        assertEquals(9.5, source.getCurrentPower(), 1e-9);
    }

    /**
     * The cover the last known value gives is bounded: once PowerJoular has been quiet for long
     * enough, the source reports zero rather than going on replaying a value that is no longer true.
     */
    @Test
    void getCurrentPower_afterGraceWindow_returnsZero() throws Exception {
        Path csv = writeCsv(SYSTEM_HEADER + "\n" + systemRow(20.0) + "\n");
        PowerJoularCsvSource source = new PowerJoularCsvSource(csv.toString());
        assertEquals(20.0, source.getCurrentPower(), 1e-9);

        Files.delete(csv);

        quietly(PowerJoularCsvSource.class, () -> {
            for (int cycle = 1; cycle <= MAX_STALE_CYCLES; cycle++) {
                assertEquals(20.0, source.getCurrentPower(), 1e-9,
                        "Cycle " + cycle + " is still within the grace window");
            }
            assertEquals(0.0, source.getCurrentPower(), 1e-9,
                    "Past the grace window the source must stop attributing energy");
        });
    }

    /** A producer that comes back must be picked up again, with the stale state cleared. */
    @Test
    void getCurrentPower_producerReturns_recovers() throws Exception {
        Path csv = writeCsv(SYSTEM_HEADER + "\n" + systemRow(20.0) + "\n");
        PowerJoularCsvSource source = new PowerJoularCsvSource(csv.toString());
        assertEquals(20.0, source.getCurrentPower(), 1e-9);

        Files.delete(csv);
        quietly(PowerJoularCsvSource.class, () -> {
            for (int cycle = 0; cycle <= MAX_STALE_CYCLES; cycle++) {
                source.getCurrentPower();
            }
        });

        Files.writeString(csv, SYSTEM_HEADER + "\n" + systemRow(33.0) + "\n", StandardCharsets.UTF_8);
        assertEquals(33.0, source.getCurrentPower(), 1e-9);
    }
}
