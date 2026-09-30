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

import java.io.IOException;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads CPU power from the shared memory ring buffer PowerJoular writes with {@code -r}.
 * PowerJoular runs as a process of its own, so the privileges needed to measure the hardware stay out of the Java application.
 *
 * <p>The area is a plain file on every OS, mapped read-only here. Layout, in the machine's byte order:
 *
 * <pre>
 *   Offset 0  : u64 head, the number of cycles written so far
 *   Offset 8  : entry[0], then entry[1] ... entry[4], 48 bytes each
 *   Each entry:
 *     +0  u64 timestamp      (Unix seconds)
 *     +8  f64 cpu_power      (watts)  &lt;- read here
 *     +16 f64 gpu_power
 *     +24 f64 total_power
 *     +32 f64 cpu_usage
 *     +40 f64 pid_app_power
 *   Total: 8 + 5 * 48 = 248 bytes
 * </pre>
 *
 * <p>PowerJoular writes entry {@code head mod 5}, then increments {@code head}, once a second.
 * Windows end when {@code head} moves, so the stack samples of a window describe the same second as the power charged to it.
 *
 * <p>When PowerJoular restarts, it deletes the file and creates a new one. The old one stays mapped here and stops
 * moving, so a window without a new entry drops the mapping, and the next read maps whatever file is at the path by
 * then. Not on Windows, which refuses to delete a mapped file: there PowerJoular has to be started before the
 * application, and not restarted while it runs. A file left behind by a PowerJoular that is gone is told by the age
 * of its newest entry.
 */
final class PowerJoularRingBuffer implements PowerSource {

    private static final Logger logger = Logger.getLogger(PowerJoularRingBuffer.class.getName());

    static final int ENTRY_SIZE = 48;
    static final int ENTRY_COUNT = 5;
    static final int FILE_SIZE = 8 + ENTRY_COUNT * ENTRY_SIZE;
    private static final int CPU_POWER_OFFSET = 8;

    /** How long a window waits for PowerJoular's next cycle. */
    static final long MAX_WINDOW_NS = 2 * ONE_SECOND_NS;

    /** PowerJoular writes once a second, so an entry older than this means it has stopped. */
    static final long MAX_AGE_SECONDS = 5;

    private final Path path;

    /** The mapped area, or null while there is none. */
    private ByteBuffer buffer;

    /** The head when the current window began. */
    private long windowStartHead;

    private PowerJoularRingBuffer(Path path) {
        this.path = path;
    }

    /** Opens the source even if the ring buffer is not there yet: PowerJoular may start after the application. */
    static PowerJoularRingBuffer open(Path path) {
        PowerJoularRingBuffer ring = new PowerJoularRingBuffer(path);
        try {
            ring.attach();
            ring.windowStartHead = ring.buffer.getLong(0);
        } catch (IOException ignored) {
            // Reported by the first reading, which tries again
        }
        logger.log(Level.INFO, "Reading CPU power from the PowerJoular ring buffer " + path);
        return ring;
    }

    @Override
    public boolean windowOver(long elapsedNs) {
        if (buffer == null) {
            return elapsedNs >= ONE_SECOND_NS;
        }
        return buffer.getLong(0) != windowStartHead || elapsedNs >= MAX_WINDOW_NS;
    }

    @Override
    public double watts() throws IOException {
        if (buffer == null) {
            attach();
        }

        long head = buffer.getLong(0);
        long previousHead = windowStartHead;
        windowStartHead = head;
        if (head <= 0) {
            throw new IOException("PowerJoular has not published a measurement in " + path + " yet.");
        }
        if (head == previousHead) {
            // Nothing measured this window. PowerJoular may have stopped, or restarted on a new file, which the next reading maps.
            buffer = null;
            throw new IOException("PowerJoular published no measurement in " + path + " for "
                    + MAX_WINDOW_NS / ONE_SECOND_NS + " s. Is it still running with -r?");
        }

        // The entry is complete once the counter past it is published, and PowerJoular only writes that slot again four cycles later.
        // The counter is still read again after the entry: if it moved that far, this thread was paused and the entry may have been rewritten under it.
        // The fence pairs with PowerJoular's barrier before it publishes the counter; without it an ARM CPU may reorder the reads.
        VarHandle.acquireFence();

        int offset = 8 + (int) ((head - 1) % ENTRY_COUNT) * ENTRY_SIZE;
        long timestamp = buffer.getLong(offset);
        double power = buffer.getDouble(offset + CPU_POWER_OFFSET);

        VarHandle.acquireFence();
        if (buffer.getLong(0) != head) {
            throw new IOException("PowerJoular was writing to " + path + " while it was read.");
        }

        // A timestamp in the future is accepted: the clock may have stepped
        long ageSeconds = System.currentTimeMillis() / 1000L - timestamp;
        if (ageSeconds > MAX_AGE_SECONDS) {
            buffer = null;
            throw new IOException("The latest measurement in " + path + " is " + ageSeconds
                    + " s old. Is PowerJoular still running with -r?");
        }
        // A negative or NaN value is no power. PowerJoular writes 0 here when it has no CPU source
        if (!Double.isFinite(power) || power < 0) {
            throw new IOException("PowerJoular could not measure the CPU power (it wrote " + power + ").");
        }
        return power;
    }

    @Override
    public void close() {
        // The mapping is released by the garbage collector
        buffer = null;
    }

    private void attach() throws IOException {
        // Not following links, and only a plain file: the default path is in a directory every user can write to,
        // and a pipe planted there would block the open until someone writes to it
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(path + " is not a regular file.");
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            // Mapping past the end of the file would fault on access rather than fail here
            if (channel.size() < FILE_SIZE) {
                throw new IOException(path + " is too short to be a PowerJoular ring buffer, or is still being created.");
            }
            buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, FILE_SIZE).order(ByteOrder.nativeOrder());
        } catch (NoSuchFileException e) {
            throw new IOException("There is no PowerJoular ring buffer at " + path + ". Is PowerJoular running with -r?", e);
        }
    }
}
