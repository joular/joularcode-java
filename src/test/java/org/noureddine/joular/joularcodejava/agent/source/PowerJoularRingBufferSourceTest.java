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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.noureddine.joular.joularcodejava.agent.source.PowerJoularRingBufferSource.ENTRY_SIZE;
import static org.noureddine.joular.joularcodejava.agent.source.PowerJoularRingBufferSource.FILE_SIZE;
import static org.noureddine.joular.joularcodejava.agent.source.PowerSource.MAX_STALE_CYCLES;
import static org.noureddine.joular.joularcodejava.agent.source.TestLogging.quietly;

/**
 * Unit tests for {@link PowerJoularRingBufferSource}.
 *
 * <p>Fixtures reproduce the area PowerJoular writes with {@code -r}, in the byte order of the
 * machine:
 *
 * <pre>
 *   Offset 0      : u64  head
 *   Offset 8      : entry[0]  (48 bytes)
 *   Offset 56     : entry[1]
 *   ...
 *   Each entry:
 *     +0  u64  timestamp     (Unix time in seconds)
 *     +8  f64  cpu_power     (watts)
 *     +16 f64  gpu_power
 *     +24 f64  total_power
 *     +32 f64  cpu_usage
 *     +40 f64  pid_app_power
 *
 *   Total size: 8 + 5 * 48 = 248 bytes.
 * </pre>
 *
 * <p>The sizes come from the class under test rather than from copies, so that a change to the layout
 * cannot leave the fixtures quietly asserting against the old one.
 *
 * <p>Entries carry the current time: the source ignores entries older than ten seconds, so a fixed
 * timestamp would make every fixture look like an area left behind by a PowerJoular that stopped.
 */
class PowerJoularRingBufferSourceTest {

    @TempDir
    Path tempDir;

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    /** An area holding one entry with the current time. */
    private Path writeTempRingBuffer(long head, int entryIdx, double cpuPower) throws Exception {
        return writeTempRingBuffer(head, entryIdx, cpuPower, now());
    }

    private Path writeTempRingBuffer(long head, int entryIdx, double cpuPower, long timestamp)
            throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(FILE_SIZE).order(ByteOrder.nativeOrder());
        buf.putLong(0, head);
        int entryOffset = 8 + entryIdx * ENTRY_SIZE;
        buf.putLong(entryOffset, timestamp);
        buf.putDouble(entryOffset + 8, cpuPower);
        Path file = tempDir.resolve("ring.bin");
        Files.write(file, buf.array());
        return file;
    }

    private PowerJoularRingBufferSource sourceFor(Path file) {
        PowerJoularRingBufferSource source = new PowerJoularRingBufferSource(file.toString());
        source.initialize();
        return source;
    }

    // -------------------------------------------------------------------------
    // Reading an entry
    // -------------------------------------------------------------------------

    /**
     * The counter says how many cycles have been written, so the newest entry is {@code (head-1) mod 5}
     * and its cpu_power sits 8 bytes into it. A counter of zero or below means PowerJoular has not
     * written a cycle yet, and -1 is what it writes for a value it could not read at all.
     */
    @ParameterizedTest(name = "head={0} entry={1} cpu_power={2} -> {3} W")
    @CsvSource({
            // head, entryIdx, cpuPower, expected
            "  1, 0,  25.0, 25.0",   // first cycle
            "  3, 2,  18.0, 18.0",   // (3-1) % 5 = 2
            "  7, 1,  11.5, 11.5",   // the counter wraps round the ring
            "  0, 0,  99.0,  0.0",   // nothing written yet
            " -1, 0,  10.0,  0.0",   // a counter outside its range
            "  1, 0,  -1.0,  0.0",   // the unreadable sentinel
            "  1, 0,   NaN,  0.0",   // not a finite power
    })
    void getCurrentPower_readsNewestEntry(long head, int entryIdx, double cpuPower, double expected)
            throws Exception {
        PowerJoularRingBufferSource source = sourceFor(writeTempRingBuffer(head, entryIdx, cpuPower));
        assertEquals(expected, source.getCurrentPower(), 1e-9);
    }

    /** Reading the same unchanged area twice gives the same value. */
    @Test
    void getCurrentPower_calledTwice_returnsSameValue() throws Exception {
        PowerJoularRingBufferSource source = sourceFor(writeTempRingBuffer(1L, 0, 30.0));
        assertEquals(30.0, source.getCurrentPower(), 1e-9);
        assertEquals(30.0, source.getCurrentPower(), 1e-9);
    }

    // -------------------------------------------------------------------------
    // Freshness
    // -------------------------------------------------------------------------

    /**
     * The area outlives PowerJoular on purpose, so an old one still holds a plausible counter and a
     * plausible power. It is the timestamp that has to rule it out. A timestamp ahead of us means the
     * clock stepped, which is not staleness.
     */
    @ParameterizedTest(name = "entry {0}s old -> {1} W")
    @CsvSource({
            "    2, 16.0",   // just read
            "  -60, 16.0",   // the clock stepped backwards
            " 3600,  0.0",   // an hour old: PowerJoular has stopped
    })
    void getCurrentPower_judgesFreshnessOnTheTimestamp(long ageSeconds, double expected)
            throws Exception {
        Path file = writeTempRingBuffer(1L, 0, 16.0, now() - ageSeconds);
        PowerJoularRingBufferSource source = sourceFor(file);
        quietly(PowerJoularRingBufferSource.class,
                () -> assertEquals(expected, source.getCurrentPower(), 1e-9));
    }

    // -------------------------------------------------------------------------
    // Attaching
    // -------------------------------------------------------------------------

    /** Reading before initialize must not throw. */
    @Test
    void getCurrentPower_neverInitialized_returnsZero() throws Exception {
        PowerJoularRingBufferSource source = new PowerJoularRingBufferSource(
                tempDir.resolve("nonexistent").toString());
        quietly(PowerJoularRingBufferSource.class,
                () -> assertEquals(0.0, source.getCurrentPower(), 1e-9));
    }

    /**
     * A missing area is not fatal: PowerJoular may not have started yet, so the agent must come up
     * rather than abort, and must pick the area up once it appears.
     */
    @Test
    void getCurrentPower_fileAppearsLater_attaches() throws Exception {
        Path file = tempDir.resolve("ring.bin");
        PowerJoularRingBufferSource source = new PowerJoularRingBufferSource(file.toString());
        quietly(PowerJoularRingBufferSource.class, () -> {
            assertDoesNotThrow(source::initialize);
            assertEquals(0.0, source.getCurrentPower(), 1e-9);
        });

        writeTempRingBuffer(1L, 0, 21.0);
        assertEquals(21.0, source.getCurrentPower(), 1e-9);
    }

    /**
     * A file shorter than the area is one PowerJoular has created but not yet sized. Mapping past its
     * end would fault on access rather than fail at the mapping, so the length is checked first, and
     * the area is read once it grows.
     */
    @Test
    void getCurrentPower_shortFileThenSized_attaches() throws Exception {
        Path file = tempDir.resolve("ring.bin");
        Files.write(file, new byte[64]);

        PowerJoularRingBufferSource source = new PowerJoularRingBufferSource(file.toString());
        quietly(PowerJoularRingBufferSource.class, () -> {
            source.initialize();
            assertEquals(0.0, source.getCurrentPower(), 1e-9);
        });

        writeTempRingBuffer(1L, 0, 27.5);
        assertEquals(27.5, source.getCurrentPower(), 1e-9);
    }

    // -------------------------------------------------------------------------
    // The grace window
    // -------------------------------------------------------------------------

    /**
     * The cover the last known value gives is bounded: once PowerJoular has been quiet for long
     * enough, the source reports zero rather than going on replaying a value that is no longer true.
     */
    @Test
    void getCurrentPower_afterGraceWindow_returnsZero() throws Exception {
        long now = now();
        PowerJoularRingBufferSource source = sourceFor(writeTempRingBuffer(1L, 0, 20.0, now));
        assertEquals(20.0, source.getCurrentPower(), 1e-9);

        // The same area, with its entry aged past the limit: PowerJoular has stopped writing.
        writeTempRingBuffer(1L, 0, 20.0, now - 3600);

        quietly(PowerJoularRingBufferSource.class, () -> {
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
        long now = now();
        PowerJoularRingBufferSource source = sourceFor(writeTempRingBuffer(1L, 0, 20.0, now));
        assertEquals(20.0, source.getCurrentPower(), 1e-9);

        writeTempRingBuffer(1L, 0, 20.0, now - 3600);
        quietly(PowerJoularRingBufferSource.class, () -> {
            for (int cycle = 0; cycle <= MAX_STALE_CYCLES; cycle++) {
                source.getCurrentPower();
            }
        });

        writeTempRingBuffer(2L, 1, 35.0);
        assertEquals(35.0, source.getCurrentPower(), 1e-9);
    }

    // -------------------------------------------------------------------------
    // Closing
    // -------------------------------------------------------------------------

    /** Reading after close must not throw. */
    @Test
    void close_thenGetCurrentPower_returnsZero() throws Exception {
        PowerJoularRingBufferSource source = sourceFor(writeTempRingBuffer(1L, 0, 20.0));
        assertEquals(20.0, source.getCurrentPower(), 1e-9);

        source.close();
        // Closing drops the mapping; the next read re-attaches, which is harmless.
        quietly(PowerJoularRingBufferSource.class, () -> assertDoesNotThrow(source::getCurrentPower));
    }

    /** The contract on {@link PowerSource} says close must be safe even if initialize never ran. */
    @Test
    void close_withoutInitialize_isSafe() {
        PowerJoularRingBufferSource source = new PowerJoularRingBufferSource("/nonexistent");
        assertDoesNotThrow(source::close);
    }

    // -------------------------------------------------------------------------
    // cycleCounter: the edge the agent closes its attribution window on
    // -------------------------------------------------------------------------

    /** Unattached, the source has no cycle to offer and the agent falls back to its own timer. */
    @Test
    void cycleCounter_notAttached_returnsMinusOne() throws Exception {
        PowerJoularRingBufferSource source = new PowerJoularRingBufferSource(
                tempDir.resolve("nonexistent").toString());
        quietly(PowerJoularRingBufferSource.class, () -> {
            source.initialize();
            assertEquals(-1, source.cycleCounter());
        });
    }

    /** Attached, it reports PowerJoular's counter, and follows it as cycles are written. */
    @Test
    void cycleCounter_attached_followsTheProducer() throws Exception {
        PowerJoularRingBufferSource source = sourceFor(writeTempRingBuffer(4L, 3, 12.0));
        assertEquals(4L, source.cycleCounter());

        writeTempRingBuffer(5L, 4, 13.0);
        assertEquals(5L, source.cycleCounter(), "a new cycle must be visible to the agent");
    }
}
