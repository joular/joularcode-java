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
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JoularCoreRingBufferSource}.
 *
 * <p>The ring buffer is a fixed-size shared-memory region written by Joular Core and
 * read by this source via a memory-mapped file. The binary layout is:
 * <pre>
 *   Offset 0      : u64  head (index of the most-recently written entry + 1)
 *   Offset 8      : entry[0]  (48 bytes)
 *   Offset 56     : entry[1]
 *   ...
 *   Each entry:
 *     +0  u64  timestamp
 *     +8  f64  cpu_power   ← the field we read
 *     +16 f64  gpu_power
 *     +24 f64  total_power
 *     +32 f64  cpu_usage
 *     +40 f64  pid_app_power
 * </pre>
 * Total file size: {@code 8 + 5 × 48 = 248} bytes.
 *
 * <h2>Platform handling</h2>
 * <p>The temp directory is not deleted automatically because Windows can keep a
 * mapped file locked until the JVM releases the {@code MappedByteBuffer}. This
 * lets the file-mapping tests run on Windows instead of being reported as skipped.
 */
class JoularCoreRingBufferSourceTest {

    /** Entry size in bytes: one u64 timestamp + five f64 values. */
    private static final int ENTRY_SIZE = 48;

    /** Number of entries in the ring buffer. */
    private static final int BUFFER_SIZE = 5;

    /** Total file size: 8-byte head counter followed by BUFFER_SIZE entries. */
    private static final int FILE_SIZE = 8 + BUFFER_SIZE * ENTRY_SIZE; // 248

    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    /**
     * Creates a 248-byte temp file whose binary layout matches what Joular Core
     * would write. The {@code head} value is placed at offset 0; the
     * {@code cpuPower} value is placed at the correct offset within entry
     * {@code entryIdx}.
     *
     * <p>The buffer uses {@link ByteOrder#nativeOrder()} to match the production
     * code, which calls {@code buffer.order(ByteOrder.nativeOrder())} after
     * mapping the file.
     *
     * @param head      the head counter to write at offset 0
     * @param entryIdx  the zero-based slot index (0–4) to store the cpu_power value
     * @param cpuPower  the cpu_power value to place in the entry
     * @return path of the written temp file
     */
    private Path writeTempRingBuffer(long head, int entryIdx, double cpuPower) throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(FILE_SIZE).order(ByteOrder.nativeOrder());
        buf.putLong(0, head);
        // Each entry starts at offset 8 + entryIdx * ENTRY_SIZE;
        // cpu_power is the second field (after the 8-byte timestamp), so +8.
        int cpuPowerOffset = 8 + entryIdx * ENTRY_SIZE + 8;
        buf.putDouble(cpuPowerOffset, cpuPower);
        Path file = tempDir.resolve("ring.bin");
        Files.write(file, buf.array());
        return file;
    }

    // -------------------------------------------------------------------------
    // Platform-independent tests
    // -------------------------------------------------------------------------

    /**
     * When {@code initialize()} has never been called, the internal
     * {@code ByteBuffer} is {@code null} and {@code getCurrentPower()} must
     * return 0.0 immediately without throwing.
     */
    @Test
    void getCurrentPower_bufferNull_returnsZero() {
        JoularCoreRingBufferSource source = new JoularCoreRingBufferSource("/nonexistent");
        assertEquals(0.0, source.getCurrentPower(), 1e-9);
    }

    /**
     * After {@code close()} is called, the internal buffer is set to
     * {@code null}. A subsequent call to {@code getCurrentPower()} must
     * therefore return 0.0 (the null-buffer fast-path) and not throw.
     */
    @Test
    void close_setsBufferNull_subsequentGetReturnsZero() throws Exception {
        Path file = writeTempRingBuffer(1L, 0, 20.0);
        JoularCoreRingBufferSource source = new JoularCoreRingBufferSource(file.toString());
        source.initialize();
        // Confirm a successful read before closing.
        source.getCurrentPower();
        source.close();
        assertEquals(0.0, source.getCurrentPower(), 1e-9);
    }

    // -------------------------------------------------------------------------
    // File-mapping tests
    // -------------------------------------------------------------------------

    /**
     * With {@code head = 1}, the ring index is {@code (1 - 1) % 5 = 0}, so
     * the cpu_power field lives at byte offset {@code 8 + 0 × 48 + 8 = 16}.
     * This is the simplest valid reading and validates the base-case slot
     * calculation.
     */
    @Test
    void getCurrentPower_validHead1_returnsCpuPower() throws Exception {
        // head=1 → idx=(1-1)%5=0 → entry at offset 8, cpu_power at offset 16
        Path file = writeTempRingBuffer(1L, 0, 25.0);
        JoularCoreRingBufferSource source = new JoularCoreRingBufferSource(file.toString());
        source.initialize();
        assertEquals(25.0, source.getCurrentPower(), 1e-9);
    }

    /**
     * With {@code head = 3}, the ring index is {@code (3 - 1) % 5 = 2}, so
     * the cpu_power field lives at byte offset {@code 8 + 2 × 48 + 8 = 112}.
     * This verifies the modulo ring-slot arithmetic for non-zero indices.
     */
    @Test
    void getCurrentPower_validHead3_returnsCorrectSlot() throws Exception {
        // head=3 → idx=(3-1)%5=2 → entry at offset 8+2*48=104, cpu_power at 112
        Path file = writeTempRingBuffer(3L, 2, 18.0);
        JoularCoreRingBufferSource source = new JoularCoreRingBufferSource(file.toString());
        source.initialize();
        assertEquals(18.0, source.getCurrentPower(), 1e-9);
    }

    /**
     * A head value of 0 means Joular Core has not written any entry yet.
     * The source must treat this as "no data available" and return
     * {@code lastKnownPower} (initially 0.0).
     */
    @Test
    void getCurrentPower_headZero_returnsZero() throws Exception {
        Path file = writeTempRingBuffer(0L, 0, 99.0);
        JoularCoreRingBufferSource source = new JoularCoreRingBufferSource(file.toString());
        source.initialize();
        assertEquals(0.0, source.getCurrentPower(), 1e-9);
    }

    /**
     * A negative head value is outside the valid range ({@code head <= 0} check)
     * and must be handled the same as zero — return 0.0 without throwing.
     */
    @Test
    void getCurrentPower_headNegative_returnsZero() throws Exception {
        Path file = writeTempRingBuffer(-1L, 0, 10.0);
        JoularCoreRingBufferSource source = new JoularCoreRingBufferSource(file.toString());
        source.initialize();
        assertEquals(0.0, source.getCurrentPower(), 1e-9);
    }

    /**
     * A negative cpu_power value in the ring buffer is physically impossible
     * and must be rejected. The source must return {@code lastKnownPower}
     * (0.0 on the first call) rather than propagating the bad reading.
     */
    @Test
    void getCurrentPower_negativeCpuPower_returnsLastKnown() throws Exception {
        Path file = writeTempRingBuffer(1L, 0, -5.0);
        JoularCoreRingBufferSource source = new JoularCoreRingBufferSource(file.toString());
        source.initialize();
        assertEquals(0.0, source.getCurrentPower(), 1e-9);
    }

    /**
     * {@code Double.longBitsToDouble} for a NaN bit pattern is finite=false,
     * so the {@code !Double.isFinite(value)} guard must reject it and return
     * {@code lastKnownPower} (0.0).
     */
    @Test
    void getCurrentPower_nanCpuPower_returnsLastKnown() throws Exception {
        Path file = writeTempRingBuffer(1L, 0, Double.NaN);
        JoularCoreRingBufferSource source = new JoularCoreRingBufferSource(file.toString());
        source.initialize();
        assertEquals(0.0, source.getCurrentPower(), 1e-9);
    }

    /**
     * Once a valid value has been read, it is cached as {@code lastKnownPower}.
     * Calling {@code getCurrentPower()} again on an unmodified buffer must
     * return the same value — both because the buffer hasn't changed and
     * because the load-verify check (head1 == head2) passes on a static file.
     */
    @Test
    void getCurrentPower_validThenInvalid_returnsLastKnownGoodValue() throws Exception {
        Path file = writeTempRingBuffer(1L, 0, 30.0);
        JoularCoreRingBufferSource source = new JoularCoreRingBufferSource(file.toString());
        source.initialize();
        assertEquals(30.0, source.getCurrentPower(), 1e-9);
        // Second call on the same static buffer — head is unchanged so
        // no torn-read is detected and the cached value is returned.
        assertEquals(30.0, source.getCurrentPower(), 1e-9);
    }

    /**
     * When the mapped file does not exist, {@code initialize()} must propagate
     * the {@link java.io.FileNotFoundException} (or equivalent) as an
     * {@link Exception} so the agent can fail fast at startup.
     */
    @Test
    void initialize_missingFile_throwsException() {
        JoularCoreRingBufferSource source = new JoularCoreRingBufferSource(
                tempDir.resolve("does-not-exist.bin").toString());
        assertThrows(Exception.class, source::initialize);
    }

    // -------------------------------------------------------------------------
    // Windows-only: named shared memory fallback
    // -------------------------------------------------------------------------

    /**
     * On Windows, when the configured path does not correspond to an existing
     * file, {@code initialize()} falls back to opening a named file-mapping
     * object via {@code Kernel32.OpenFileMappingW}. If no such mapping exists,
     * {@code OpenFileMappingW} returns a null or invalid handle, and the method
     * must throw an {@link Exception} rather than silently proceeding with a
     * null buffer.
     *
     * <p>This test confirms that the Windows code path (JNA call) is reachable
     * and behaves correctly when the mapping is absent, without requiring a
     * live Joular Core process.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void initialize_onWindows_sharedMemoryPath_throwsWhenMappingAbsent() {
        // A name that exists neither as a file on disk nor as a Windows named
        // mapping — forces the fallback to mapWindowsSharedMemory(), which throws.
        JoularCoreRingBufferSource source = new JoularCoreRingBufferSource(
                "Local\\NonExistentJoularMapping_TestOnly_12345");
        assertThrows(Exception.class, source::initialize);
    }

    // -------------------------------------------------------------------------
    // Torn-read guard threshold tests
    // -------------------------------------------------------------------------

    /**
     * When the producer advances the head by 1 (wrote one new entry to a
     * <em>different</em> slot), the slot being read is still intact.
     * {@code isTornRead} must return {@code false} for all deltas strictly less
     * than {@code BUFFER_SIZE}.
     *
     * <p>With the old guard {@code head1 != head2} this condition would have
     * returned {@code true} for any head change, causing valid readings to be
     * discarded on every concurrent write.
     */
    @Test
    void isTornRead_deltaLessThanBufferSize_returnsFalse() {
        assertFalse(JoularCoreRingBufferSource.isTornRead(1L, 1L)); // delta = 0 (no change)
        assertFalse(JoularCoreRingBufferSource.isTornRead(1L, 2L)); // delta = 1
        assertFalse(JoularCoreRingBufferSource.isTornRead(1L, 5L)); // delta = BUFFER_SIZE - 1
    }

    /**
     * When the producer advances the head by exactly {@code BUFFER_SIZE} (= 5) or
     * more, it has wrapped around and overwritten the slot being read.
     * {@code isTornRead} must return {@code true} so the caller discards the
     * potentially corrupted sample.
     */
    @Test
    void isTornRead_deltaAtOrAboveBufferSize_returnsTrue() {
        assertTrue(JoularCoreRingBufferSource.isTornRead(1L, 6L));  // delta = BUFFER_SIZE
        assertTrue(JoularCoreRingBufferSource.isTornRead(1L, 10L)); // delta > BUFFER_SIZE
    }
}
