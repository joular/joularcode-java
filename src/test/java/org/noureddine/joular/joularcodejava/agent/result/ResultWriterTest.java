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

package org.noureddine.joular.joularcodejava.agent.result;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ResultWriter}.
 *
 * <p>Covers three areas:
 * <ul>
 *   <li>{@code csvEscape} — RFC 4180 escaping of special characters in CSV field values.</li>
 *   <li>{@code verifyWritable} — fail-fast directory creation and write-probe behaviour.</li>
 *   <li>{@code writeRuntimeMethods} — CSV output format, header injection, power filtering,
 *       energy calculation, and idempotent close.</li>
 * </ul>
 *
 * <p>Every test that touches the filesystem uses a JUnit 5 {@code @TempDir} so no
 * cleanup is needed and tests are fully isolated from each other.
 */
class ResultWriterTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // csvEscape
    // -------------------------------------------------------------------------

    /**
     * A {@code null} input must be treated as an empty string rather than
     * propagating a NullPointerException into the CSV output.
     */
    @Test
    void csvEscape_null_returnsEmptyString() {
        assertEquals("", ResultWriter.csvEscape(null));
    }

    /**
     * A value that contains none of the RFC 4180 special characters
     * (comma, double-quote, newline, carriage-return) must be returned
     * unchanged — no surrounding quotes should be added.
     */
    @Test
    void csvEscape_plainText_unchanged() {
        String input = "org.example.Foo.bar";
        assertEquals(input, ResultWriter.csvEscape(input));
    }

    /**
     * An empty string contains no special characters and must be returned as-is.
     */
    @Test
    void csvEscape_empty_returnsEmpty() {
        assertEquals("", ResultWriter.csvEscape(""));
    }

    /**
     * A comma inside a field value is a CSV delimiter and must be neutralised
     * by wrapping the entire value in double-quotes.
     */
    @Test
    void csvEscape_withComma_quotesValue() {
        assertEquals("\"a,b\"", ResultWriter.csvEscape("a,b"));
    }

    /**
     * A double-quote inside a field value must be escaped by doubling it
     * (RFC 4180 §2.7) and the whole value wrapped in double-quotes.
     * Input {@code foo"bar} becomes {@code "foo""bar"}.
     */
    @Test
    void csvEscape_withDoubleQuote_doublesAndQuotes() {
        assertEquals("\"foo\"\"bar\"", ResultWriter.csvEscape("foo\"bar"));
    }

    /**
     * A newline character inside a value causes the value to be wrapped in
     * double-quotes; the newline itself must be preserved verbatim inside the
     * quotes (not escaped or removed).
     */
    @Test
    void csvEscape_withNewline_quotesValue() {
        String result = ResultWriter.csvEscape("a\nb");
        assertTrue(result.startsWith("\"") && result.endsWith("\""), "Should be quoted: " + result);
        assertTrue(result.contains("\n"), "Newline should be preserved inside quotes");
    }

    /**
     * A carriage-return character must trigger quoting just as a newline does.
     */
    @Test
    void csvEscape_withCarriageReturn_quotesValue() {
        String result = ResultWriter.csvEscape("a\rb");
        assertTrue(result.startsWith("\"") && result.endsWith("\""), "Should be quoted: " + result);
    }

    /**
     * When a value contains both a comma and a double-quote, both escaping
     * rules apply simultaneously: the value is wrapped in double-quotes, and
     * each internal double-quote is doubled.
     * Input {@code a,"b"} becomes {@code "a,""b""}.
     */
    @Test
    void csvEscape_withCommaAndQuote_bothRulesApplied() {
        // "a,"b"" → "a,""b"""
        assertEquals("\"a,\"\"b\"\"\"", ResultWriter.csvEscape("a,\"b\""));
    }

    // -------------------------------------------------------------------------
    // verifyWritable
    // -------------------------------------------------------------------------

    /**
     * When the results directory does not yet exist, {@code verifyWritable}
     * must create it (including any missing intermediate directories) and
     * complete without throwing.
     */
    @Test
    void verifyWritable_nonExistentDir_createsDir() throws Exception {
        Path nested = tempDir.resolve("sub/nested");
        ResultWriter writer = new ResultWriter(nested.toString());
        assertDoesNotThrow(writer::verifyWritable);
        assertTrue(Files.isDirectory(nested));
    }

    /**
     * When the results directory already exists and is writable,
     * {@code verifyWritable} must succeed without throwing.
     */
    @Test
    void verifyWritable_existingDir_succeeds() throws Exception {
        ResultWriter writer = new ResultWriter(tempDir.toString());
        assertDoesNotThrow(writer::verifyWritable);
    }

    /**
     * The write-probe file ({@code .joular-write-probe}) is used only to
     * confirm the directory is writable at startup. It must be deleted
     * before {@code verifyWritable} returns so it does not pollute the
     * results directory.
     */
    @Test
    void verifyWritable_probeFileDeletedAfterCheck() throws Exception {
        ResultWriter writer = new ResultWriter(tempDir.toString());
        writer.verifyWritable();
        assertFalse(Files.exists(tempDir.resolve(".joular-write-probe")));
    }

    // -------------------------------------------------------------------------
    // writeRuntimeMethods
    // -------------------------------------------------------------------------

    /**
     * The first write to a new CSV file must produce the standard header line
     * as the very first line of the file, followed by the data row.
     * Expected header: {@code timestamp,branch,power_watts,energy_joules,interval_seconds}.
     */
    @Test
    void writeRuntimeMethods_newFile_writesHeaderThenData() throws Exception {
        ResultWriter writer = new ResultWriter(tempDir.toString());
        writer.verifyWritable();
        writer.writeRuntimeMethods(Map.of("com.A.method", 5.0), 1000L, 1.0, "out.csv");
        writer.close();

        List<String> lines = Files.readAllLines(tempDir.resolve("out.csv"), StandardCharsets.UTF_8);
        assertEquals("timestamp,branch,power_watts,energy_joules,interval_seconds", lines.get(0));
        assertEquals(2, lines.size());
    }

    /**
     * A method entry with exactly zero power must not produce a CSV row.
     * Only entries with strictly positive power are meaningful for energy
     * attribution; writing zeros would inflate the output file without adding
     * information.
     */
    @Test
    void writeRuntimeMethods_zeroPower_rowSkipped() throws Exception {
        ResultWriter writer = new ResultWriter(tempDir.toString());
        writer.verifyWritable();

        // Use LinkedHashMap to guarantee insertion order for the assertion below.
        Map<String, Double> map = new LinkedHashMap<>();
        map.put("A.zero", 0.0);
        map.put("B.positive", 3.0);
        writer.writeRuntimeMethods(map, 1000L, 1.0, "out.csv");
        writer.close();

        List<String> lines = Files.readAllLines(tempDir.resolve("out.csv"), StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), "Expected header + 1 data row");
        assertTrue(lines.get(1).contains("B.positive"));
    }

    /**
     * A method entry with negative power must be suppressed for the same
     * reason as zero power — the power filter is {@code powerWatts > 0}.
     */
    @Test
    void writeRuntimeMethods_negativePower_rowSkipped() throws Exception {
        ResultWriter writer = new ResultWriter(tempDir.toString());
        writer.verifyWritable();

        Map<String, Double> map = new LinkedHashMap<>();
        map.put("A.neg", -1.0);
        map.put("B.pos", 2.0);
        writer.writeRuntimeMethods(map, 1000L, 1.0, "out.csv");
        writer.close();

        List<String> lines = Files.readAllLines(tempDir.resolve("out.csv"), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertFalse(lines.get(1).contains("A.neg"));
    }

    /**
     * The {@code energy_joules} column must equal {@code power_watts × interval_seconds}.
     * This test uses power = 4.0 W and interval = 2.5 s, so the expected energy is 10.0 J.
     * Values are read back from the written file and compared with a tolerance of 1e-9
     * to accommodate any floating-point rounding in the nine-decimal-place format.
     */
    @Test
    void writeRuntimeMethods_energyEqualsPoworTimesInterval() throws Exception {
        ResultWriter writer = new ResultWriter(tempDir.toString());
        writer.verifyWritable();
        writer.writeRuntimeMethods(Map.of("m.method", 4.0), 1000L, 2.5, "out.csv");
        writer.close();

        List<String> lines = Files.readAllLines(tempDir.resolve("out.csv"), StandardCharsets.UTF_8);
        String dataLine = lines.get(1);
        String[] cols = dataLine.split(",");
        // CSV columns: timestamp, branch, power_watts, energy_joules, interval_seconds
        double power = Double.parseDouble(cols[2]);
        double energy = Double.parseDouble(cols[3]);
        double interval = Double.parseDouble(cols[4]);
        assertEquals(power * interval, energy, 1e-9);
        assertEquals(4.0, power, 1e-9);
        assertEquals(10.0, energy, 1e-9);
    }

    /**
     * Power and energy values must be formatted with exactly nine decimal places
     * (format string {@code "%.9f"}) to preserve sub-milliwatt precision for
     * fine-grained energy attribution.
     */
    @Test
    void writeRuntimeMethods_usesNineDecimalPrecision() throws Exception {
        ResultWriter writer = new ResultWriter(tempDir.toString());
        writer.verifyWritable();
        // 1/3 is an irrational decimal — ensures the formatter actually rounds at 9 places.
        writer.writeRuntimeMethods(Map.of("m.m", 1.0 / 3.0), 1000L, 1.0, "out.csv");
        writer.close();

        List<String> lines = Files.readAllLines(tempDir.resolve("out.csv"), StandardCharsets.UTF_8);
        String dataLine = lines.get(1);
        String[] cols = dataLine.split(",");
        // power_watts is column index 2
        assertTrue(cols[2].matches("-?\\d+\\.\\d{9}"), "Expected 9 decimal places, got: " + cols[2]);
    }

    /**
     * When {@code writeRuntimeMethods} is called multiple times on the same
     * file name, subsequent calls must append rows without writing the header
     * a second time. The header must appear exactly once at the top of the file.
     */
    @Test
    void writeRuntimeMethods_appendsWithoutDuplicateHeader() throws Exception {
        ResultWriter writer = new ResultWriter(tempDir.toString());
        writer.verifyWritable();
        writer.writeRuntimeMethods(Map.of("A.m", 1.0), 1000L, 1.0, "out.csv");
        writer.writeRuntimeMethods(Map.of("B.m", 2.0), 2000L, 1.0, "out.csv");
        writer.close();

        List<String> lines = Files.readAllLines(tempDir.resolve("out.csv"), StandardCharsets.UTF_8);
        long headerCount = lines.stream()
                .filter(l -> l.startsWith("timestamp,branch"))
                .count();
        assertEquals(1, headerCount, "Header should appear exactly once");
        assertEquals(3, lines.size(), "Expected header + 2 data rows");
    }

    /**
     * If the output file already exists but is empty (zero bytes), a fresh
     * header must be prepended before the first data row. This covers the
     * edge case where the file was created externally or left empty by a
     * previous failed run.
     */
    @Test
    void writeRuntimeMethods_addsHeaderToEmptyPreexistingFile() throws Exception {
        // Pre-create an empty file to exercise the "size == 0" branch in the writer.
        Path outFile = tempDir.resolve("out.csv");
        Files.write(outFile, new byte[0]);

        ResultWriter writer = new ResultWriter(tempDir.toString());
        writer.verifyWritable();
        writer.writeRuntimeMethods(Map.of("X.m", 5.0), 1000L, 1.0, "out.csv");
        writer.close();

        List<String> lines = Files.readAllLines(outFile, StandardCharsets.UTF_8);
        assertEquals("timestamp,branch,power_watts,energy_joules,interval_seconds", lines.get(0));
    }

    /**
     * Method (branch) names used as CSV field values are passed through
     * {@code csvEscape}. A branch name that contains a comma must be wrapped
     * in double-quotes so it reads back as a single field, not two separate
     * columns.
     */
    @Test
    void writeRuntimeMethods_csvEscapeAppliedToKey() throws Exception {
        ResultWriter writer = new ResultWriter(tempDir.toString());
        writer.verifyWritable();
        writer.writeRuntimeMethods(Map.of("foo,bar", 3.0), 1000L, 1.0, "out.csv");
        writer.close();

        String content = Files.readString(tempDir.resolve("out.csv"), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"foo,bar\""), "Comma in key should be CSV-escaped");
    }

    /**
     * Calling {@code close()} more than once must not throw. The internal
     * writer map is cleared on the first call, so the second call must
     * silently do nothing.
     */
    @Test
    void close_isIdempotent() throws Exception {
        ResultWriter writer = new ResultWriter(tempDir.toString());
        writer.verifyWritable();
        writer.writeRuntimeMethods(Map.of("A.m", 1.0), 1000L, 1.0, "out.csv");
        assertDoesNotThrow(() -> {
            writer.close();
            writer.close();
        });
    }
}
