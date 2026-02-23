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
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.logging.Level;
import java.util.logging.Logger;

public class JoularCoreRingBufferSource implements PowerSource {

    private static final Logger logger = Logger.getLogger(JoularCoreRingBufferSource.class.getName());
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private final String path;
    private ByteBuffer buffer;
    private HANDLE mappingHandle;
    private Pointer mappingPointer;
    private static final int ENTRY_SIZE = 40; // 5 * f64 (8 bytes each)
    private static final int BUFFER_SIZE = 5;
    private static final int FILE_SIZE = 8 + BUFFER_SIZE * ENTRY_SIZE;

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
            File file = new File(path);
            if (file.exists() && file.isFile()) {
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

        long head = buffer.getLong(0);
        int idx = (int) ((head - 1) % BUFFER_SIZE);
        if (idx < 0) return 0;

        int offset = 8 + idx * ENTRY_SIZE;
        // RingBufferStruct: cpu_power, gpu_power, total_power, cpu_usage, pid_app_power
        // cpu_power is the 1st f64 (no additional offset)
        return buffer.getDouble(offset);
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