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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.noureddine.joular.joularcodejava.agent.PowerJoularRingBuffer.ENTRY_SIZE;
import static org.noureddine.joular.joularcodejava.agent.PowerJoularRingBuffer.FILE_SIZE;
import static org.noureddine.joular.joularcodejava.agent.PowerJoularRingBuffer.MAX_WINDOW_NS;
import static org.noureddine.joular.joularcodejava.agent.PowerSource.ONE_SECOND_NS;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The ring buffer read from files laid out the way PowerJoular writes them with {@code -r}. Entries carry the
 * current time unless a test says otherwise, since old entries mean PowerJoular has stopped.
 */
class PowerJoularRingBufferTest {

    // A mapping is only released by the garbage collector, and Windows cannot delete a file while it is mapped
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    private Path ring() {
        return tempDir.resolve("powerjoular");
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    /** Writes an area whose newest entry, {@code (head - 1) mod 5}, holds the given power. */
    private Path write(long head, double cpuPower, long timestamp) throws IOException {
        ByteBuffer area = ByteBuffer.allocate(FILE_SIZE).order(ByteOrder.nativeOrder());
        area.putLong(0, head);
        int entry = 8 + (int) Math.floorMod(head - 1, 5L) * ENTRY_SIZE;
        area.putLong(entry, timestamp);
        area.putDouble(entry + 8, cpuPower);
        // Written in place, the way PowerJoular updates it: Windows refuses to truncate a mapped file
        return Files.write(ring(), area.array(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    private Path write(long head, double cpuPower) throws IOException {
        return write(head, cpuPower, now());
    }

    /** Opens the source on an area at cycle {@code head - 1}, so that writing cycle {@code head} ends the window. */
    private PowerJoularRingBuffer openBefore(long head) throws IOException {
        write(head - 1, 1.0);
        return PowerJoularRingBuffer.open(ring());
    }

    // -------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "head={0} -> {1} W")
    @CsvSource({"1, 25.0", "3, 18.0", "7, 11.5"})
    void watts_readsTheNewestEntry(long head, double power) throws IOException {
        try (PowerJoularRingBuffer ring = openBefore(head)) {
            write(head, power);
            assertEquals(power, ring.watts(), 1e-9);
        }
    }

    /** No cycle written yet, the -1 PowerJoular writes for what it could not measure, and a value that is no power. */
    @ParameterizedTest(name = "head={0} power={1}")
    @CsvSource({"0, 10.0", "-1, 10.0", "1, -1.0", "1, NaN"})
    void watts_nothingUsable_throws(long head, double power) throws IOException {
        try (PowerJoularRingBuffer ring = openBefore(head)) {
            write(head, power);
            assertThrows(IOException.class, ring::watts);
        }
    }

    /** The area outlives PowerJoular, so it is the timestamp that tells a stopped one. A step back in time is not age. */
    @ParameterizedTest(name = "entry {0} s old -> read: {1}")
    @CsvSource({"2, true", "-60, true", "3600, false"})
    void watts_judgesFreshnessOnTheTimestamp(long ageSeconds, boolean fresh) throws IOException {
        try (PowerJoularRingBuffer ring = openBefore(2)) {
            write(2, 16.0, now() - ageSeconds);
            if (fresh) {
                assertEquals(16.0, ring.watts(), 1e-9);
            } else {
                assertThrows(IOException.class, ring::watts);
            }
        }
    }

    /** Each window is charged the cycle published during it, never the previous window's again. */
    @Test
    void watts_noNewCycleSinceTheLastReading_throwsUntilTheNextOne() throws IOException {
        try (PowerJoularRingBuffer ring = openBefore(5)) {
            write(5, 13.0);
            assertEquals(13.0, ring.watts(), 1e-9);

            assertThrows(IOException.class, ring::watts, "PowerJoular published nothing since");
            assertThrows(IOException.class, ring::watts, "mapped again, the same entry is not charged again");

            write(6, 14.0);
            assertEquals(14.0, ring.watts(), 1e-9);
        }
    }

    // -------------------------------------------------------------------------
    // Attaching
    // -------------------------------------------------------------------------

    /** PowerJoular may start after the application: the source opens anyway and picks the area up once it is there. */
    @Test
    void watts_areaAppearsLater_isPickedUp() throws IOException {
        try (PowerJoularRingBuffer ring = PowerJoularRingBuffer.open(ring())) {
            assertThrows(IOException.class, ring::watts);

            write(1, 21.0);
            assertEquals(21.0, ring.watts(), 1e-9);
        }
    }

    /** A file not yet sized to the area is not mapped: reading past its end would fault instead of failing. */
    @Test
    void watts_areaTooShort_isNotMapped() throws IOException {
        Files.write(ring(), new byte[64]);
        try (PowerJoularRingBuffer ring = PowerJoularRingBuffer.open(ring())) {
            assertThrows(IOException.class, ring::watts);

            write(1, 27.5);
            assertEquals(27.5, ring.watts(), 1e-9);
        }
    }

    /**
     * PowerJoular deletes the area and creates a new one when it restarts, which leaves the old one mapped here, no
     * longer moving. The window without a cycle must lead to the new area, not to the old one's last value again.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows cannot delete a mapped file")
    void watts_powerJoularRestarted_followsTheNewArea() throws IOException {
        try (PowerJoularRingBuffer ring = openBefore(9)) {
            Files.delete(ring());
            write(1, 35.0);

            assertThrows(IOException.class, ring::watts, "the old area published nothing");
            assertEquals(35.0, ring.watts(), 1e-9, "the new area is read");
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void watts_symbolicLink_isRefused() throws IOException {
        Path elsewhere = tempDir.resolve("elsewhere");
        write(1, 20.0);
        Files.move(ring(), elsewhere);
        Files.createSymbolicLink(ring(), elsewhere);

        try (PowerJoularRingBuffer ring = PowerJoularRingBuffer.open(ring())) {
            assertThrows(IOException.class, ring::watts);
        }
    }

    /** A pipe planted at the path would block the open, so only a plain file is mapped. */
    @Test
    void watts_notARegularFile_isRefused() throws IOException {
        Files.createDirectory(ring());

        try (PowerJoularRingBuffer ring = PowerJoularRingBuffer.open(ring())) {
            assertThrows(IOException.class, ring::watts);
        }
    }

    // -------------------------------------------------------------------------
    // Monitoring windows: ended by PowerJoular's cycles
    // -------------------------------------------------------------------------

    /** Without an area there is no cadence to follow, so windows last a second. */
    @Test
    void windowOver_notAttached_afterOneSecond() {
        try (PowerJoularRingBuffer ring = PowerJoularRingBuffer.open(ring())) {
            assertFalse(ring.windowOver(ONE_SECOND_NS - 1));
            assertTrue(ring.windowOver(ONE_SECOND_NS));
        }
    }

    /** The window waits for PowerJoular's next cycle, past a second if need be, and ends as soon as it is published. */
    @Test
    void windowOver_endsWhenPowerJoularPublishes() throws IOException {
        write(4, 12.0);
        try (PowerJoularRingBuffer ring = PowerJoularRingBuffer.open(ring())) {
            assertFalse(ring.windowOver(ONE_SECOND_NS), "no new cycle yet");

            write(5, 13.0);
            assertTrue(ring.windowOver(1), "a new cycle ends the window at once");

            assertEquals(13.0, ring.watts(), 1e-9);
            assertFalse(ring.windowOver(1), "the next window waits for the cycle after");
        }
    }

    /** A PowerJoular that stopped publishing does not hold a window open forever. */
    @Test
    void windowOver_noNewCycle_endsAfterTwoSeconds() throws IOException {
        write(4, 12.0);
        try (PowerJoularRingBuffer ring = PowerJoularRingBuffer.open(ring())) {
            assertFalse(ring.windowOver(MAX_WINDOW_NS - 1));
            assertTrue(ring.windowOver(MAX_WINDOW_NS));
        }
    }
}
