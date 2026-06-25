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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinuxRaplPowerSourceTest {

    @TempDir
    Path tempDir;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    private Path writeRaplPackage(String name, String energyMicrojoules, String maxMicrojoules) throws Exception {
        Path pkg = tempDir.resolve("intel-rapl-0");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("name"), name + "\n", StandardCharsets.UTF_8);
        Files.writeString(pkg.resolve("energy_uj"), energyMicrojoules + "\n", StandardCharsets.UTF_8);
        Files.writeString(pkg.resolve("max_energy_range_uj"), maxMicrojoules + "\n", StandardCharsets.UTF_8);
        return pkg;
    }

    private void writeEnergy(Path pkg, String energyMicrojoules) throws Exception {
        Files.writeString(pkg.resolve("energy_uj"), energyMicrojoules + "\n", StandardCharsets.UTF_8);
    }

    private LinuxRaplPowerSource sourceFor(Path pkg) {
        return new LinuxRaplPowerSource(pkg, "Linux", clock);
    }

    @Test
    void getCurrentPower_validPackage_calculatesPowerFromEnergyDelta() throws Exception {
        Path pkg = writeRaplPackage("package-0", "1000000", "10000000");
        LinuxRaplPowerSource source = sourceFor(pkg);
        source.initialize();

        writeEnergy(pkg, "3000000");
        clock.advance(Duration.ofSeconds(2));

        assertEquals(1.0, source.getCurrentPower(), 1e-9);
    }

    @Test
    void getCurrentPower_seededInitialEnergyDoesNotBecomePower() throws Exception {
        Path pkg = writeRaplPackage("package-0", "1000000", "10000000");
        LinuxRaplPowerSource source = sourceFor(pkg);
        source.initialize();

        clock.advance(Duration.ofSeconds(1));

        assertEquals(0.0, source.getCurrentPower(), 1e-9);
    }

    @Test
    void getCurrentPower_counterWrap_usesMaxEnergyRange() throws Exception {
        Path pkg = writeRaplPackage("package-0", "9000000", "10000000");
        LinuxRaplPowerSource source = sourceFor(pkg);
        source.initialize();

        writeEnergy(pkg, "1000000");
        clock.advance(Duration.ofSeconds(2));

        assertEquals(1.0, source.getCurrentPower(), 1e-9);
    }

    @Test
    void initialize_nonLinux_throwsException() throws Exception {
        Path pkg = writeRaplPackage("package-0", "1000000", "10000000");
        LinuxRaplPowerSource source = new LinuxRaplPowerSource(pkg, "Windows 11", clock);

        assertThrows(Exception.class, source::initialize);
    }

    @Test
    void initialize_wrongPackageDomain_throwsException() throws Exception {
        Path pkg = writeRaplPackage("core", "1000000", "10000000");
        LinuxRaplPowerSource source = sourceFor(pkg);

        assertThrows(Exception.class, source::initialize);
    }

    @Test
    void initialize_missingEnergyFile_throwsException() throws Exception {
        Path pkg = tempDir.resolve("intel-rapl-0");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("name"), "package-0\n", StandardCharsets.UTF_8);
        Files.writeString(pkg.resolve("max_energy_range_uj"), "10000000\n", StandardCharsets.UTF_8);
        LinuxRaplPowerSource source = sourceFor(pkg);

        assertThrows(Exception.class, source::initialize);
    }

    @Test
    void initialize_nonRegularMaxFile_throwsException() throws Exception {
        Path pkg = tempDir.resolve("intel-rapl-0");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("name"), "package-0\n", StandardCharsets.UTF_8);
        Files.writeString(pkg.resolve("energy_uj"), "1000000\n", StandardCharsets.UTF_8);
        Files.createDirectory(pkg.resolve("max_energy_range_uj"));
        LinuxRaplPowerSource source = sourceFor(pkg);

        assertThrows(Exception.class, source::initialize);
    }

    @Test
    void initialize_malformedMaxEnergyRange_throwsException() throws Exception {
        Path pkg = writeRaplPackage("package-0", "1000000", "not-a-number");
        LinuxRaplPowerSource source = sourceFor(pkg);

        assertThrows(Exception.class, source::initialize);
    }

    @Test
    void initialize_impossibleInitialEnergy_throwsException() throws Exception {
        Path pkg = writeRaplPackage("package-0", "11000000", "10000000");
        LinuxRaplPowerSource source = sourceFor(pkg);

        assertThrows(Exception.class, source::initialize);
    }

    @Test
    void getCurrentPower_parseFailure_returnsLastKnownPower() throws Exception {
        Path pkg = writeRaplPackage("package-0", "1000000", "10000000");
        LinuxRaplPowerSource source = sourceFor(pkg);
        source.initialize();
        writeEnergy(pkg, "3000000");
        clock.advance(Duration.ofSeconds(1));
        assertEquals(2.0, source.getCurrentPower(), 1e-9);

        writeEnergy(pkg, "not-a-number");
        clock.advance(Duration.ofSeconds(1));

        assertEquals(2.0, source.getCurrentPower(), 1e-9);
    }

    @Test
    void getCurrentPower_negativeEnergy_returnsLastKnownPower() throws Exception {
        Path pkg = writeRaplPackage("package-0", "1000000", "10000000");
        LinuxRaplPowerSource source = sourceFor(pkg);
        source.initialize();
        writeEnergy(pkg, "2000000");
        clock.advance(Duration.ofSeconds(1));
        assertEquals(1.0, source.getCurrentPower(), 1e-9);

        writeEnergy(pkg, "-1");
        clock.advance(Duration.ofSeconds(1));

        assertEquals(1.0, source.getCurrentPower(), 1e-9);
    }

    @Test
    void getCurrentPower_nonFiniteEnergy_returnsLastKnownPower() throws Exception {
        Path pkg = writeRaplPackage("package-0", "1000000", "10000000");
        LinuxRaplPowerSource source = sourceFor(pkg);
        source.initialize();
        writeEnergy(pkg, "2000000");
        clock.advance(Duration.ofSeconds(1));
        assertEquals(1.0, source.getCurrentPower(), 1e-9);

        writeEnergy(pkg, "NaN");
        clock.advance(Duration.ofSeconds(1));

        assertEquals(1.0, source.getCurrentPower(), 1e-9);
    }

    @Test
    void getCurrentPower_energyAboveMaxRange_returnsLastKnownPower() throws Exception {
        Path pkg = writeRaplPackage("package-0", "1000000", "10000000");
        LinuxRaplPowerSource source = sourceFor(pkg);
        source.initialize();
        writeEnergy(pkg, "2000000");
        clock.advance(Duration.ofSeconds(1));
        assertEquals(1.0, source.getCurrentPower(), 1e-9);

        writeEnergy(pkg, "11000000");
        clock.advance(Duration.ofSeconds(1));

        assertEquals(1.0, source.getCurrentPower(), 1e-9);
    }

    @Test
    void getCurrentPower_clockDoesNotAdvance_returnsLastKnownPowerWithoutUpdatingState() throws Exception {
        Path pkg = writeRaplPackage("package-0", "1000000", "10000000");
        LinuxRaplPowerSource source = sourceFor(pkg);
        source.initialize();

        writeEnergy(pkg, "2000000");
        assertEquals(0.0, source.getCurrentPower(), 1e-9);

        clock.advance(Duration.ofSeconds(1));
        assertEquals(1.0, source.getCurrentPower(), 1e-9);
    }

    @Test
    void close_resetsSourceState() throws Exception {
        Path pkg = writeRaplPackage("package-0", "1000000", "10000000");
        LinuxRaplPowerSource source = sourceFor(pkg);
        source.initialize();
        writeEnergy(pkg, "2000000");
        clock.advance(Duration.ofSeconds(1));
        assertEquals(1.0, source.getCurrentPower(), 1e-9);

        source.close();

        assertEquals(0.0, source.getCurrentPower(), 1e-9);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant) {
            this(instant, ZoneOffset.UTC);
        }

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
