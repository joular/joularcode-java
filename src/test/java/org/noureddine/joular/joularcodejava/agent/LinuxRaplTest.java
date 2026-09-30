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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * RAPL read from a fake powercap tree. Its zone names hold a ':', which Windows does not allow in a file name, so
 * those tests only run elsewhere.
 */
class LinuxRaplTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long MAX_UJ = 10_000_000;

    @TempDir
    Path powercap;

    /** The time the source reads, moved by the tests. */
    private long now = 0;

    private LinuxRapl open() throws IOException {
        return LinuxRapl.open(powercap, () -> now);
    }

    private void zone(String dirName, String name, long energyUj) throws IOException {
        Path zone = Files.createDirectories(powercap.resolve(dirName));
        Files.writeString(zone.resolve("name"), name + "\n");
        Files.writeString(zone.resolve("max_energy_range_uj"), MAX_UJ + "\n");
        energy(dirName, String.valueOf(energyUj));
    }

    private void energy(String dirName, String energyUj) throws IOException {
        Files.writeString(powercap.resolve(dirName).resolve("energy_uj"), energyUj + "\n");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void watts_isTheEnergyUsedOverTheTimeElapsed() throws IOException {
        zone("intel-rapl:0", "package-0", 1_000_000);
        LinuxRapl rapl = open();

        energy("intel-rapl:0", "5000000");
        now += 2 * SECOND;

        assertEquals(2.0, rapl.watts(), 1e-9);
    }

    /** Sub-zones are part of their package, and psys covers more than the CPU. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void watts_sumsThePackagesOnly() throws IOException {
        zone("intel-rapl:0", "package-0", 0);
        zone("intel-rapl:1", "package-1", 0);
        zone("intel-rapl:0:0", "core", 0);
        zone("intel-rapl:2", "psys", 0);
        LinuxRapl rapl = open();

        for (String dir : new String[] {"intel-rapl:0", "intel-rapl:1", "intel-rapl:0:0", "intel-rapl:2"}) {
            energy(dir, "1000000");
        }
        now += SECOND;

        assertEquals(2.0, rapl.watts(), 1e-9, "one joule from each package, nothing from the other zones");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void watts_counterWrapped_countsTheEnergyAcrossTheWrap() throws IOException {
        zone("intel-rapl:0", "package-0", MAX_UJ - 1_000_000);
        LinuxRapl rapl = open();

        energy("intel-rapl:0", "1000000");
        now += SECOND;

        assertEquals(2.0, rapl.watts(), 1e-9);
    }

    /** A failed reading leaves the counters alone, so the next good one covers the whole time since the last. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void watts_afterAFailedReading_averagesOverTheLongerSpan() throws IOException {
        zone("intel-rapl:0", "package-0", 0);
        LinuxRapl rapl = open();

        for (String garbage : new String[] {"not-a-number", "-1", String.valueOf(MAX_UJ + 1)}) {
            energy("intel-rapl:0", garbage);
            now += SECOND;
            assertThrows(IOException.class, rapl::watts, garbage);
        }

        energy("intel-rapl:0", "8000000");
        now += SECOND;
        assertEquals(2.0, rapl.watts(), 1e-9, "8 J over 4 s");
    }

    /** After a suspend the counter restarts near 0, which is not a wrap: that window gets no energy. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void watts_counterReset_countsNothingForThatWindow() throws IOException {
        zone("intel-rapl:0", "package-0", 2_000_000);
        LinuxRapl rapl = open();

        energy("intel-rapl:0", "1000000");
        now += SECOND;
        assertEquals(0.0, rapl.watts(), 1e-9);

        energy("intel-rapl:0", "3000000");
        now += SECOND;
        assertEquals(2.0, rapl.watts(), 1e-9, "counting on from the reset value");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void open_noPackageZone_throws() throws IOException {
        zone("intel-rapl:0", "psys", 0);

        assertThrows(IOException.class, this::open);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void open_malformedRange_throws() throws IOException {
        zone("intel-rapl:0", "package-0", 0);
        Files.writeString(powercap.resolve("intel-rapl:0/max_energy_range_uj"), "lots\n");

        assertThrows(IOException.class, this::open);
    }

    @Test
    void energyBetween() {
        assertEquals(300, LinuxRapl.energyBetween(100, 400, 1000));
        assertEquals(0, LinuxRapl.energyBetween(400, 400, 1000));
        assertEquals(150, LinuxRapl.energyBetween(900, 50, 1000), "wrapped past 1000");
        assertEquals(0, LinuxRapl.energyBetween(100, 50, 1000), "a jump back over half the range is a reset");
    }
}
