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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.noureddine.joular.joularcodejava.agent.VmPowerFile.MAX_REUSES;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The file a host writes the power of a virtual machine to, as the guest reads it. */
class VmPowerFileTest {

    @TempDir
    Path tempDir;

    private Path file() {
        return tempDir.resolve("vm-power.csv");
    }

    private void write(String content) throws IOException {
        Files.writeString(file(), content);
    }

    /** The row PowerJoular rewrites every second with -o for the process a virtual machine runs as. */
    @Nested
    class PowerJoularFormat {

        private VmPowerFile open() throws IOException {
            return VmPowerFile.open(file(), "powerjoular");
        }

        @Test
        void readsThePowerColumn() throws IOException {
            write("1790000000,0.2500,12.5000\n");
            assertEquals(12.5, open().watts(), 1e-9);
        }

        /** The host may run Windows, and only the first line is the latest row. */
        @Test
        void windowsLineEndingsAndFurtherLines_areFine() throws IOException {
            write("1790000000,0.25,12.5\r\nsomething else\r\n");
            assertEquals(12.5, open().watts(), 1e-9);
        }

        /** A row caught while the host writes it may be cut inside the number, which would still parse. */
        @Test
        void rowWithoutItsNewline_isNotRead() throws IOException {
            write("1790000000,0.25,12.5");
            assertThrows(IOException.class, open()::watts);
        }

        @Test
        void wholeHostFile_isRefused() throws IOException {
            write("1790000000,0.25,40.0,35.0,5.0\n");
            IOException e = assertThrows(IOException.class, open()::watts);
            assertTrue(e.getMessage().contains("whole host"), e.getMessage());
        }

        /** Written with -f instead of -o, the file starts with a header. */
        @ParameterizedTest
        @ValueSource(strings = {"Timestamp,CPU Usage,CPU Power\n", "1790000000,0.25,-1.0\n", "1790000000,0.25\n", "\n"})
        void unusableRow_throws(String content) throws IOException {
            write(content);
            assertThrows(IOException.class, open()::watts);
        }

        @Test
        void missingFile_throws() {
            IOException e = assertThrows(IOException.class, () -> open().watts());
            assertTrue(e.getMessage().contains("There is no"), e.getMessage());
        }

        @Test
        void notARegularFile_isRefused() throws IOException {
            Files.createDirectory(file());
            assertThrows(IOException.class, open()::watts);
        }

        /**
         * The host and the windows run on separate one-second cycles, so a window may see the row the previous one
         * saw, or catch the file empty while it is rewritten. The last row covers that, for a little while.
         */
        @Test
        void noNewRow_lastOneCoversAFewWindowsThenTheHostIsTakenToHaveStopped() throws IOException {
            VmPowerFile source = open();
            write("1790000000,0.25,12.5\n");
            assertEquals(12.5, source.watts(), 1e-9);

            write("");
            assertEquals(12.5, source.watts(), 1e-9, "caught while the host rewrites it");
            write("1790000000,0.25,12.5\n");
            for (int reuse = 2; reuse <= MAX_REUSES; reuse++) {
                assertEquals(12.5, source.watts(), 1e-9, "the same row again");
            }

            IOException e = assertThrows(IOException.class, source::watts);
            assertTrue(e.getMessage().contains("has not updated"), e.getMessage());

            write("1790000004,0.30,15.0\n");
            assertEquals(15.0, source.watts(), 1e-9, "the host writes again");
        }
    }

    /** A power alone, written by anything. */
    @Nested
    class WattsFormat {

        private VmPowerFile open() throws IOException {
            return VmPowerFile.open(file(), "watts");
        }

        @ParameterizedTest
        @ValueSource(strings = {"42.5\n", "42.5", " 42.5 \r\n"})
        void readsTheValue(String content) throws IOException {
            write(content);
            assertEquals(42.5, open().watts(), 1e-9);
        }

        /** There is no timestamp to tell a stopped host from a steady one, so the value is used as long as it is there. */
        @Test
        void unchangedValue_isUsedEveryTime() throws IOException {
            write("42.5\n");
            VmPowerFile source = open();
            for (int window = 0; window <= MAX_REUSES + 2; window++) {
                assertEquals(42.5, source.watts(), 1e-9);
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"-1\n", "lots\n", "NaN\n", ""})
        void unusableValue_throws(String content) throws IOException {
            write(content);
            assertThrows(IOException.class, open()::watts);
        }

        @Test
        void lineTooLong_throws() throws IOException {
            write("4".repeat(300));
            assertThrows(IOException.class, open()::watts);
        }
    }
}
