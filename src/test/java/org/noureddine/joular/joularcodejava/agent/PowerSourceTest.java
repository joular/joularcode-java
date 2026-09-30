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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Which source {@code power-source-type} opens. */
class PowerSourceTest {

    @TempDir
    Path tempDir;

    private Config config(String type, Path vmPowerFile, String vmPowerFormat) {
        return new Config(type, tempDir.resolve("ring"), vmPowerFile, vmPowerFormat, 10, tempDir, List.of());
    }

    private Config config(String type) {
        return config(type, null, "powerjoular");
    }

    @Test
    void unknownType_throwsNamingTheChoices() {
        IOException e = assertThrows(IOException.class, () -> PowerSource.open(config("joularcore")));
        assertTrue(e.getMessage().contains("auto, rapl, ringbuffer or vm"), e.getMessage());
    }

    /** Without RAPL, the CPU power comes from PowerJoular, which may start after the application. */
    @Test
    void auto_withoutRapl_opensTheRingBuffer() throws IOException {
        assumeFalse(LinuxRapl.isPresent());

        try (PowerSource source = PowerSource.open(config("auto"))) {
            assertInstanceOf(PowerJoularRingBuffer.class, source);
        }
    }

    @Test
    void ringbuffer_opensEvenWithoutPowerJoular() throws IOException {
        try (PowerSource source = PowerSource.open(config("ringbuffer"))) {
            assertInstanceOf(PowerJoularRingBuffer.class, source);
        }
    }

    @Test
    @DisabledOnOs(OS.LINUX)
    void rapl_outsideLinux_throws() {
        assertThrows(IOException.class, () -> PowerSource.open(config("rapl")));
    }

    /** The host may start writing the file after the application, so it does not have to be there yet. */
    @Test
    void vm_opensEvenBeforeTheHostWrites() throws IOException {
        try (PowerSource source = PowerSource.open(config("vm", tempDir.resolve("vm.csv"), "powerjoular"))) {
            assertInstanceOf(VmPowerFile.class, source);
        }
    }

    @Test
    void vm_withoutAFile_throwsNamingTheSetting() {
        IOException e = assertThrows(IOException.class, () -> PowerSource.open(config("vm")));
        assertTrue(e.getMessage().contains("vm-power-file"), e.getMessage());
    }

    @Test
    void vm_unknownFormat_throwsNamingTheChoices() {
        IOException e = assertThrows(IOException.class,
                () -> PowerSource.open(config("vm", tempDir.resolve("vm.csv"), "json")));
        assertTrue(e.getMessage().contains("powerjoular or watts"), e.getMessage());
    }
}
