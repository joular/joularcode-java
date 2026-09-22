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

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads CPU power from the shared memory ring buffer written by PowerJoular's {@code -r} option.
 *
 * <p>The area is a plain file on every OS, mapped read-only here. Its layout, in the byte order of the machine, is an 8-byte counter followed by 5 entries of 48 bytes:
 *
 * <pre>
 *   Offset 0      : u64  head (number of cycles written so far)
 *   Offset 8      : entry[0]  (48 bytes)
 *   Offset 56     : entry[1]
 *   ...
 *   Each entry:
 *     +0  u64  timestamp     (Unix time in seconds)
 *     +8  f64  cpu_power     (watts)   &lt;- the field we read
 *     +16 f64  gpu_power     (watts)
 *     +24 f64  total_power   (watts)
 *     +32 f64  cpu_usage     (0.0 to 1.0)
 *     +40 f64  pid_app_power (watts, -1 when the monitored process could not be read)
 *
 *   Total size: 8 + 5 * 48 = 248 bytes.
 * </pre>
 *
 * <p>PowerJoular writes a cycle into the entry the counter points at ({@code head mod 5}) and raises the counter afterwards. This class follows the counter to know when a new cycle has landed, and the entry timestamp to know how old it is.
 *
 * <p>The area deliberately outlives PowerJoular on every OS, so a stale file always looks plausible. Freshness is therefore decided by the entry timestamp, not by the counter.
 */
public class PowerJoularRingBufferSource implements PowerSource {

    private static final Logger logger = Logger.getLogger(PowerJoularRingBufferSource.class.getName());

    // Entry size in bytes: one u64 timestamp plus five f64 values
    static final int ENTRY_SIZE = 48;

    // Number of entries in the ring
    static final int BUFFER_SIZE = 5;

    // Total area size: the 8-byte head counter followed by the entries
    static final int FILE_SIZE = 8 + BUFFER_SIZE * ENTRY_SIZE;

    // Offset of cpu_power within an entry, just past the timestamp
    private static final int CPU_POWER_OFFSET = 8;

    /**
     * How old an entry may be before it is treated as unreadable. PowerJoular writes once a second, so anything beyond this means it is no longer running.
     */
    static final long MAX_ENTRY_AGE_SECONDS = 10;

    /**
     * The longest a window waits for the next cycle before giving up on aligning to PowerJoular.
     * It writes once a second, so twice that is already well past due.
     */
    static final long MAX_WINDOW_NANOS = 2_000_000_000L;

    private final String path;

    private volatile ByteBuffer buffer;
    private double lastKnownPower = 0.0;
    private int staleCycles = 0;
    private boolean attachWarningLogged = false;

    /** The counter as it stood when the current window opened, or -1 when there was nothing to follow. */
    private long windowOpeningCycle = -1;

    /** Set once the producer has missed its cycle, so windows stop waiting on a counter that is not moving. */
    private boolean producerStalled = false;

    public PowerJoularRingBufferSource(String path) {
        this.path = path;
    }

    @Override
    public void initialize() {
        logger.log(Level.INFO, () -> "Initializing PowerJoular ring buffer source: " + path);
        // A missing area is not fatal: PowerJoular may not have started yet, so attaching is retried on every read instead of aborting the agent here.
        attach();
    }

    @Override
    public double getCurrentPower() {
        Double power = readPower();

        if (power != null) {
            lastKnownPower = power;
            staleCycles = 0;
            return power;
        }

        staleCycles++;
        if (staleCycles <= MAX_STALE_CYCLES) {
            // Cover a momentary hiccup with the last value we trust
            return lastKnownPower;
        }

        if (staleCycles == MAX_STALE_CYCLES + 1) {
            logger.log(Level.WARNING,
                    () -> "No fresh power data in the PowerJoular ring buffer " + path + " for "
                            + MAX_STALE_CYCLES + " cycles. Is PowerJoular still running with -r?"
                            + " No energy will be attributed until it comes back.");
        }
        // Reporting zero stops the agent attributing energy from a value that is no longer true
        return 0.0;
    }

    @Override
    public void beginWindow() {
        windowOpeningCycle = headCounter();
    }

    /**
     * Closes the window on the edge where PowerJoular publishes its next cycle, so the stack samples in the window describe the same second as the power charged to them.
     *
     * <p>If that edge does not arrive within {@link #MAX_WINDOW_NANOS} the producer has stopped, and waiting on it would stretch every window from then on.
     * The source says so once and falls back to the plain fixed window until the counter moves again, so a dead PowerJoular costs the alignment rather than the cadence.
     */
    @Override
    public boolean isWindowComplete(long elapsedNs) {
        if (windowOpeningCycle <= 0) {
            // Not attached, or PowerJoular has yet to write its first cycle: there is no edge to follow
            return elapsedNs >= DEFAULT_WINDOW_NANOS;
        }

        if (headCounter() != windowOpeningCycle) {
            markProducerPublishing();
            return true;
        }

        if (producerStalled) {
            return elapsedNs >= DEFAULT_WINDOW_NANOS;
        }

        if (elapsedNs >= MAX_WINDOW_NANOS) {
            markProducerStalled();
            return true;
        }
        return false;
    }

    /** PowerJoular raises the counter once it has written a cycle, so a change is a published measurement. */
    private long headCounter() {
        ByteBuffer area = this.buffer;
        return area == null ? -1 : area.getLong(0);
    }

    private void markProducerStalled() {
        if (!producerStalled) {
            producerStalled = true;
            logger.log(Level.WARNING,
                    () -> "The PowerJoular ring buffer " + path + " has published no new cycle for "
                            + MAX_WINDOW_NANOS / 1_000_000L + " ms, so monitoring windows are no longer aligned to it and fall back to a fixed "
                            + DEFAULT_WINDOW_NANOS / 1_000_000L + " ms. Is PowerJoular still running with -r? Results stay on their usual cadence, but each window and the power charged to it may now describe slightly different seconds.");
        }
    }

    private void markProducerPublishing() {
        if (producerStalled) {
            producerStalled = false;
            logger.log(Level.INFO,
                    () -> "The PowerJoular ring buffer " + path + " is publishing cycles again. Monitoring windows are realigned to it.");
        }
    }

    /**
     * Reads cpu_power from the newest complete entry.
     *
     * @return the power in watts, or {@code null} when this cycle could not be read.
     */
    private Double readPower() {
        ByteBuffer area = this.buffer;
        if (area == null) {
            area = attach();
            if (area == null) {
                return null;
            }
        }

        // Seqlock read: the producer is a separate process, so the entry the counter points at may still be mid-write. Read the counter, then the entry, then the counter again.
        // A counter that moved means the entry may be torn, so the sample is dropped
        long head1 = area.getLong(0);
        if (head1 <= 0) {
            return null;
        }

        // The fences pair with the __sync_synchronize() PowerJoular issues before publishing the counter.
        // ByteBuffer loads are plain, so without them a processor that reorders its reads, as the ARM ones of Apple Silicon and the Raspberry Pi do, could pair a new counter with a previous entry
        VarHandle.acquireFence();

        int index = (int) ((head1 - 1) % BUFFER_SIZE);
        int offset = 8 + index * ENTRY_SIZE;
        long timestamp = area.getLong(offset);
        double value = area.getDouble(offset + CPU_POWER_OFFSET);

        VarHandle.acquireFence();

        if (head1 != area.getLong(0)) {
            return null;
        }

        if (isStale(timestamp)) {
            return null;
        }

        if (!Double.isFinite(value) || value < 0) {
            // PowerJoular writes -1 for a value it could not read at all.
            return null;
        }

        return value;
    }

    /**
     * Maps the area, or returns {@code null} when it is not there yet or is too short to hold a complete ring. A short file means PowerJoular is between creating and sizing it
     */
    private ByteBuffer attach() {
        try (RandomAccessFile file = new RandomAccessFile(path, "r")) {
            if (file.length() < FILE_SIZE) {
                // Mapping past the end of a file faults on access rather than failing here.
                return null;
            }
            ByteBuffer mapped = file.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, FILE_SIZE);
            mapped.order(ByteOrder.nativeOrder());
            this.buffer = mapped;
            attachWarningLogged = false;
            logger.log(Level.FINE, () -> "Attached to the PowerJoular ring buffer: " + path);
            return mapped;
        } catch (IOException | RuntimeException e) {
            if (!attachWarningLogged) {
                logger.log(Level.WARNING,
                        () -> "Could not open the PowerJoular ring buffer " + path
                                + ". Ensure PowerJoular is running with -r. Retrying on every cycle.");
                attachWarningLogged = true;
            }
            return null;
        }
    }

    private static boolean isStale(long timestamp) {
        // A timestamp ahead of us is left alone: the clock may have stepped, and that is not staleness
        return System.currentTimeMillis() / 1000L - timestamp > MAX_ENTRY_AGE_SECONDS;
    }

    @Override
    public void close() {
        // The mapping is released by the garbage collector, so dropping the reference is all we can do
        buffer = null;
    }
}
