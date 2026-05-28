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

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;
import com.sun.jna.WString;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

public class JoularCoreRingBufferSource implements PowerSource {

    private static final Logger logger = Logger.getLogger(JoularCoreRingBufferSource.class.getName());
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final int ENTRY_SIZE = 48; // u64 timestamp + 5 * f64 (8 bytes each)
    private static final int BUFFER_SIZE = 5;
    private static final int FILE_SIZE = 8 + BUFFER_SIZE * ENTRY_SIZE;
    private static final int STALENESS_THRESHOLD = 10;

    private final String path;
    private ByteBuffer buffer;
    private HANDLE mappingHandle;
    private Pointer mappingPointer;
    private double lastKnownPower = 0.0;
    private long lastObservedHead = Long.MIN_VALUE;
    private int staleCycles = 0;
    private boolean staleWarningLogged = false;

    interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        int FILE_MAP_READ = 0x0004;

        HANDLE OpenFileMappingW(int desiredAccess, boolean inheritHandle, WString name);
        Pointer MapViewOfFile(HANDLE hFileMappingObject, int dwDesiredAccess, int dwFileOffsetHigh, int dwFileOffsetLow, int dwNumberOfBytesToMap);
        boolean UnmapViewOfFile(Pointer lpBaseAddress);
        boolean CloseHandle(HANDLE hObject);
    }

    public static class HANDLE extends PointerType {
        public HANDLE() {
            super();
        }
    }

    public JoularCoreRingBufferSource(String path) {
        this.path = path;
    }

    @Override
    public void initialize() throws Exception {
        logger.log(Level.INFO, () -> "Initializing ring buffer source: " + path);
        if (IS_WINDOWS) {
            if (Files.exists(Path.of(path)) && Files.isRegularFile(Path.of(path))) {
                mapFile(path);
            } else {
                mapWindowsSharedMemory(path);
            }
        } else {
            mapFile(path);
        }
    }

    private void mapFile(String filePath) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(filePath, "r")) {
            this.buffer = file.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, FILE_SIZE);
        }
        this.buffer.order(ByteOrder.nativeOrder());
    }

    private void mapWindowsSharedMemory(String mappingName) throws Exception {
        mappingHandle = Kernel32.INSTANCE.OpenFileMappingW(Kernel32.FILE_MAP_READ, false, new WString(mappingName));
        if (mappingHandle == null || Pointer.nativeValue(mappingHandle.getPointer()) == 0) {
            throw new Exception("Could not open file mapping '" + mappingName + "'");
        }

        mappingPointer = Kernel32.INSTANCE.MapViewOfFile(mappingHandle, Kernel32.FILE_MAP_READ, 0, 0, FILE_SIZE);
        if (mappingPointer == null || Pointer.nativeValue(mappingPointer) == 0) {
            Kernel32.INSTANCE.CloseHandle(mappingHandle);
            mappingHandle = null;
            throw new Exception("Could not map view of file '" + mappingName + "'");
        }

        this.buffer = mappingPointer.getByteBuffer(0, FILE_SIZE);
        this.buffer.order(ByteOrder.nativeOrder());
    }

    @Override
    public double getCurrentPower() {
        if (buffer == null) return 0;

        // Load-verify pattern: the producer (Joular Core) is a separate process,
        // so the data entry at the head slot may still be mid-write when we read it.
        // Re-read head after the data load; if head advanced, discard this read.
        long head1 = buffer.getLong(0);
        if (head1 <= 0) {
            trackStaleness(head1);
            return lastKnownPower;
        }

        int idx = (int) ((head1 - 1) % BUFFER_SIZE);
        int offset = 8 + idx * ENTRY_SIZE;
        // RingBufferStruct: timestamp (u64), cpu_power, gpu_power, total_power, cpu_usage, pid_app_power
        // cpu_power is the 2nd field; skip 8-byte timestamp
        double value = buffer.getDouble(offset + 8);

        long head2 = buffer.getLong(0);
        if (isTornRead(head1, head2)) {
            // The writer wrapped around and overwrote the slot we just read; discard.
            // A delta of < BUFFER_SIZE means the writer advanced to a different slot,
            // so the data we read is still intact.
            trackStaleness(head2);
            return lastKnownPower;
        }

        trackStaleness(head1);
        if (!Double.isFinite(value) || value < 0) {
            return lastKnownPower;
        }
        lastKnownPower = value;
        return value;
    }

    /**
     * Returns {@code true} when the producer has advanced the head counter by
     * {@code BUFFER_SIZE} or more positions between the two head reads, meaning
     * it wrapped around and overwrote the slot that was being read.  A delta of
     * less than {@code BUFFER_SIZE} means the producer wrote to a <em>different</em>
     * slot and the data we read is still intact.
     *
     * <p>Package-private for unit testing.
     */
    static boolean isTornRead(long head1, long head2) {
        return head2 - head1 >= BUFFER_SIZE;
    }

    private void trackStaleness(long currentHead) {
        if (currentHead != lastObservedHead) {
            lastObservedHead = currentHead;
            staleCycles = 0;
            staleWarningLogged = false;
            return;
        }

        staleCycles++;
        if (staleCycles == STALENESS_THRESHOLD && !staleWarningLogged) {
            logger.log(Level.WARNING,
                    "Joular Core ring buffer appears stale - head has not advanced for "
                            + STALENESS_THRESHOLD + " reads. Is Joular Core still running?");
            staleWarningLogged = true;
        }
    }

    @Override
    public void close() {
        if (IS_WINDOWS) {
            if (mappingPointer != null) {
                Kernel32.INSTANCE.UnmapViewOfFile(mappingPointer);
                mappingPointer = null;
            }
            if (mappingHandle != null) {
                Kernel32.INSTANCE.CloseHandle(mappingHandle);
                mappingHandle = null;
            }
        }
        buffer = null;
    }
}
