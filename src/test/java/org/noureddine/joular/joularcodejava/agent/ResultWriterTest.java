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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.noureddine.joular.joularcodejava.agent.ResultWriter.ALL_METHODS_FILE;
import static org.noureddine.joular.joularcodejava.agent.ResultWriter.APP_METHODS_FILE;
import static org.noureddine.joular.joularcodejava.agent.ResultWriter.CSV_HEADER;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ResultWriterTest {

    @TempDir
    Path tempDir;

    private List<String> lines(String file) throws IOException {
        return Files.readAllLines(tempDir.resolve(file), StandardCharsets.UTF_8);
    }

    private void writeAll(Map<String, Double> power, double intervalSeconds) throws IOException {
        try (ResultWriter writer = new ResultWriter(tempDir)) {
            writer.write(power, Map.of(), 1000L, intervalSeconds, 1.0);
        }
    }

    // -------------------------------------------------------------------------
    // Opening
    // -------------------------------------------------------------------------

    @Test
    void open_createsTheDirectoryAndBothFilesWithAHeader() throws IOException {
        Path nested = tempDir.resolve("sub/nested");
        new ResultWriter(nested).close();

        assertEquals(List.of(CSV_HEADER), Files.readAllLines(nested.resolve(ALL_METHODS_FILE)));
        assertEquals(List.of(CSV_HEADER), Files.readAllLines(nested.resolve(APP_METHODS_FILE)));
    }

    /** A regular file where the directory should be fails at startup, rather than dropping every result later. */
    @Test
    void open_pathIsAFile_throws() throws IOException {
        Path blockingFile = tempDir.resolve("blocked");
        Files.writeString(blockingFile, "not a directory");

        assertThrows(IOException.class, () -> new ResultWriter(blockingFile));
    }

    /** A link planted where a results file goes must not make the agent, perhaps running as root, write through it. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void open_symbolicLink_isRefused() throws IOException {
        Path target = tempDir.resolve("target.txt");
        Files.writeString(target, "precious");
        Files.createSymbolicLink(tempDir.resolve(ALL_METHODS_FILE), target);

        assertThrows(IOException.class, () -> new ResultWriter(tempDir));
        assertEquals("precious", Files.readString(target));
    }

    /** Results already there are kept, and the header is not written twice. */
    @Test
    void open_existingResults_appendsWithoutASecondHeader() throws IOException {
        writeAll(Map.of("A.m", 1.0), 1.0);
        writeAll(Map.of("B.m", 2.0), 1.0);

        List<String> lines = lines(ALL_METHODS_FILE);
        assertEquals(3, lines.size());
        assertEquals(1, lines.stream().filter(CSV_HEADER::equals).count());
    }

    // -------------------------------------------------------------------------
    // Rows
    // -------------------------------------------------------------------------

    @Test
    void write_eachFileGetsItsOwnRows() throws IOException {
        try (ResultWriter writer = new ResultWriter(tempDir)) {
            writer.write(Map.of("java.lang.Thread.run;com.app.Main.run", 3.0), Map.of("com.app.Main.run", 3.0),
                    1746000000000L, 1.0, 0.75);
        }

        assertEquals("1746000000000,java.lang.Thread.run;com.app.Main.run,3.000000000,3.000000000,1.000000000,0.7500",
                lines(ALL_METHODS_FILE).get(1));
        assertEquals("1746000000000,com.app.Main.run,3.000000000,3.000000000,1.000000000,0.7500",
                lines(APP_METHODS_FILE).get(1));
    }

    @Test
    void write_energyIsPowerTimesInterval() throws IOException {
        writeAll(Map.of("m.m", 4.0), 2.5);

        String[] columns = lines(ALL_METHODS_FILE).get(1).split(",");
        assertEquals(10.0, Double.parseDouble(columns[3]), 1e-9);
        assertEquals(2.5, Double.parseDouble(columns[4]), 1e-9);
    }

    @Test
    void write_zeroOrNegativePower_rowSkipped() throws IOException {
        Map<String, Double> power = new LinkedHashMap<>();
        power.put("A.zero", 0.0);
        power.put("B.negative", -1.0);
        power.put("C.positive", 3.0);
        writeAll(power, 1.0);

        List<String> lines = lines(ALL_METHODS_FILE);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("C.positive"));
    }

    @Test
    void write_branchWithAComma_isQuoted() throws IOException {
        writeAll(Map.of("foo,bar", 3.0), 1.0);

        assertTrue(lines(ALL_METHODS_FILE).get(1).contains("\"foo,bar\""));
    }

    @Test
    void close_isIdempotent() throws IOException {
        ResultWriter writer = new ResultWriter(tempDir);
        assertDoesNotThrow(() -> {
            writer.close();
            writer.close();
        });
    }

    // -------------------------------------------------------------------------
    // csvEscape
    // -------------------------------------------------------------------------

    @Test
    void csvEscape() {
        assertEquals("org.example.Foo.bar", ResultWriter.csvEscape("org.example.Foo.bar"));
        assertEquals("", ResultWriter.csvEscape(""));
        assertEquals("\"a,b\"", ResultWriter.csvEscape("a,b"));
        assertEquals("\"foo\"\"bar\"", ResultWriter.csvEscape("foo\"bar"));
        assertEquals("\"a\nb\"", ResultWriter.csvEscape("a\nb"));
        assertEquals("\"a\rb\"", ResultWriter.csvEscape("a\rb"));
        assertEquals("\"a,\"\"b\"\"\"", ResultWriter.csvEscape("a,\"b\""));
    }
}
