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
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads the energy of the CPU packages straight from the Linux powercap RAPL interface.
 *
 * <p>Every package zone is summed, so a machine with several sockets is measured whole.
 * A package zone is a {@code /sys/class/powercap/intel-rapl:N} whose {@code name} starts with {@code package}, on AMD processors too.
 * Its sub-zones ({@code intel-rapl:N:M}: cores, uncore, DRAM) are left out, as is {@code psys}, which covers more than the CPU.
 */
final class LinuxRapl implements PowerSource {

    private static final Logger logger = Logger.getLogger(LinuxRapl.class.getName());

    private static final Path POWERCAP = Path.of("/sys/class/powercap");

    /** One package's energy counter, in microjoules. */
    private static final class Zone {
        final Path energyFile;
        final long maxEnergyUj;
        long lastEnergyUj;

        Zone(Path energyFile, long maxEnergyUj) {
            this.energyFile = energyFile;
            this.maxEnergyUj = maxEnergyUj;
        }
    }

    private final List<Zone> zones;
    private final LongSupplier nanoTime;
    private long lastReadNs;

    private LinuxRapl(List<Zone> zones, LongSupplier nanoTime) {
        this.zones = zones;
        this.nanoTime = nanoTime;
        this.lastReadNs = nanoTime.getAsLong();
    }

    /** Whether this is Linux with RAPL, readable or not. */
    static boolean isPresent() {
        // Checked in this order: Path.of rejects the ':' of the zone name on Windows
        return isLinux() && Files.isDirectory(POWERCAP.resolve("intel-rapl:0"));
    }

    static LinuxRapl open() throws IOException {
        if (!isLinux()) {
            throw new IOException("power-source-type=rapl only works on Linux. Use auto or ringbuffer instead.");
        }
        return open(POWERCAP, System::nanoTime);
    }

    static LinuxRapl open(Path powercapDir, LongSupplier nanoTime) throws IOException {
        List<Zone> zones;
        try {
            zones = packageZones(powercapDir);
            for (Zone zone : zones) {
                zone.lastEnergyUj = readEnergy(zone);
            }
        } catch (AccessDeniedException e) {
            throw new IOException("Cannot read " + e.getFile() + ". RAPL is only readable by root on most Linux"
                    + " systems: run the application as root, give its user read access to the energy_uj files, or"
                    + " run PowerJoular with -r as root and set power-source-type=ringbuffer.", e);
        }
        logger.log(Level.INFO, "Reading CPU power from Linux RAPL: "
                + zones.stream().map(zone -> zone.energyFile.getParent().toString()).toList());
        return new LinuxRapl(zones, nanoTime);
    }

    private static List<Zone> packageZones(Path powercapDir) throws IOException {
        List<Zone> zones = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(powercapDir, "intel-rapl:*")) {
            for (Path zoneDir : entries) {
                boolean topLevel = zoneDir.getFileName().toString().matches("intel-rapl:\\d+");
                if (topLevel && Files.readString(zoneDir.resolve("name")).trim().startsWith("package")) {
                    zones.add(new Zone(zoneDir.resolve("energy_uj"),
                            parseMicrojoules(zoneDir.resolve("max_energy_range_uj"))));
                }
            }
        }
        if (zones.isEmpty()) {
            throw new IOException("No RAPL package zone found in " + powercapDir + ".");
        }
        zones.sort(Comparator.comparing(zone -> zone.energyFile));
        return zones;
    }

    @Override
    public double watts() throws IOException {
        // Every counter is read before any is updated, so a failure leaves them all where they were
        long[] energyUj = new long[zones.size()];
        for (int i = 0; i < energyUj.length; i++) {
            energyUj[i] = readEnergy(zones.get(i));
        }
        long readNs = nanoTime.getAsLong();
        long elapsedNs = readNs - lastReadNs;
        if (elapsedNs <= 0) {
            throw new IOException("RAPL was read twice at the same instant.");
        }

        long usedUj = 0;
        for (int i = 0; i < energyUj.length; i++) {
            Zone zone = zones.get(i);
            usedUj += energyBetween(zone.lastEnergyUj, energyUj[i], zone.maxEnergyUj);
            zone.lastEnergyUj = energyUj[i];
        }
        lastReadNs = readNs;
        return (usedUj / 1e6) / (elapsedNs / 1e9);
    }

    @Override
    public void close() {
    }

    /** The energy used between two readings of a counter that wraps back to 0 past {@code maxUj}. */
    static long energyBetween(long beforeUj, long afterUj, long maxUj) {
        if (afterUj >= beforeUj) {
            return afterUj - beforeUj;
        }
        long wrapped = maxUj - beforeUj + afterUj;
        // More than half the range in one window is not a wrap: the counter was reset, e.g. after suspend
        return wrapped > maxUj / 2 ? 0 : wrapped;
    }

    private static long readEnergy(Zone zone) throws IOException {
        long energy = parseMicrojoules(zone.energyFile);
        if (energy > zone.maxEnergyUj) {
            throw new IOException("RAPL counter " + zone.energyFile + " is above its range: " + energy + " uJ.");
        }
        return energy;
    }

    /** Reads a file holding a number of microjoules, which cannot be negative. */
    private static long parseMicrojoules(Path file) throws IOException {
        String text = Files.readString(file).trim();
        try {
            long value = Long.parseLong(text);
            if (value >= 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Reported below, like a negative value
        }
        throw new IOException("Unexpected value in " + file + ": '" + text + "'.");
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }
}
