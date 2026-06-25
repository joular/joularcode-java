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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LinuxRaplPowerSource implements PowerSource {

    private static final Logger logger = Logger.getLogger(LinuxRaplPowerSource.class.getName());
    private static final String DEFAULT_PKG_PATH = "/sys/class/powercap/intel-rapl/intel-rapl:0";
    private static final String EXPECTED_PKG_NAME = "package-0";
    private static final double MICROJOULES_PER_JOULE = 1_000_000.0;

    private final Path packagePath;
    private final String osName;
    private final Clock clock;

    private Path energyPath;
    private double maxEnergyRangeJoules;
    private double lastEnergyJoules;
    private Instant lastReadAt;
    private double lastKnownPower = 0.0;
    private boolean initialized = false;

    public LinuxRaplPowerSource() {
        this(System.getProperty("os.name", ""));
    }

    private LinuxRaplPowerSource(String osName) {
        this(defaultPackagePath(osName), osName, Clock.systemUTC());
    }

    LinuxRaplPowerSource(Path packagePath, String osName, Clock clock) {
        this.packagePath = Objects.requireNonNull(packagePath, "packagePath");
        this.osName = osName == null ? "" : osName;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void initialize() throws Exception {
        if (!isLinux()) {
            throw new IllegalStateException("Linux RAPL power source is only supported on Linux.");
        }

        Path namePath = packagePath.resolve("name");
        Path maxEnergyRangePath = packagePath.resolve("max_energy_range_uj");
        energyPath = packagePath.resolve("energy_uj");

        requireReadableFile(namePath, "RAPL package name");
        requireReadableFile(energyPath, "RAPL package energy");
        requireReadableFile(maxEnergyRangePath, "RAPL package max energy range");

        String packageName = Files.readString(namePath).trim();
        if (!EXPECTED_PKG_NAME.equals(packageName)) {
            throw new IOException("Unsupported RAPL package domain '" + packageName
                    + "' at " + packagePath + "; expected " + EXPECTED_PKG_NAME + ".");
        }

        maxEnergyRangeJoules = readJoules(maxEnergyRangePath);
        if (!Double.isFinite(maxEnergyRangeJoules) || maxEnergyRangeJoules <= 0) {
            throw new IOException("Invalid RAPL max energy range: " + maxEnergyRangeJoules + " J.");
        }

        lastEnergyJoules = readEnergyJoules();
        lastReadAt = clock.instant();
        lastKnownPower = 0.0;
        initialized = true;

        logger.log(Level.INFO, () -> "Initialized Linux RAPL PKG source: " + packagePath);
    }

    @Override
    public double getCurrentPower() {
        if (!initialized) {
            return 0.0;
        }

        try {
            double currentEnergyJoules = readEnergyJoules();
            Instant currentReadAt = clock.instant();
            double elapsedSeconds = elapsedSeconds(lastReadAt, currentReadAt);
            if (elapsedSeconds <= 0 || !Double.isFinite(elapsedSeconds)) {
                return lastKnownPower;
            }

            double energyDelta = currentEnergyJoules >= lastEnergyJoules
                    ? currentEnergyJoules - lastEnergyJoules
                    : currentEnergyJoules - lastEnergyJoules + maxEnergyRangeJoules;
            if (!Double.isFinite(energyDelta) || energyDelta < 0) {
                return lastKnownPower;
            }

            double power = energyDelta / elapsedSeconds;
            if (!Double.isFinite(power) || power < 0) {
                return lastKnownPower;
            }

            lastEnergyJoules = currentEnergyJoules;
            lastReadAt = currentReadAt;
            lastKnownPower = power;
            return power;
        } catch (Exception e) {
            logger.log(Level.FINE, "Could not read Linux RAPL power data", e);
            return lastKnownPower;
        }
    }

    @Override
    public void close() {
        initialized = false;
        energyPath = null;
        maxEnergyRangeJoules = 0.0;
        lastEnergyJoules = 0.0;
        lastReadAt = null;
        lastKnownPower = 0.0;
    }

    private boolean isLinux() {
        return isLinux(osName);
    }

    private static boolean isLinux(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("linux");
    }

    private static Path defaultPackagePath(String osName) {
        if (isLinux(osName)) {
            return Path.of(DEFAULT_PKG_PATH);
        }
        return Path.of(".");
    }

    private double readEnergyJoules() throws IOException {
        double energyJoules = readJoules(energyPath);
        if (!Double.isFinite(energyJoules) || energyJoules < 0 || energyJoules > maxEnergyRangeJoules) {
            throw new IOException("Invalid RAPL energy reading: " + energyJoules + " J.");
        }
        return energyJoules;
    }

    private static double readJoules(Path path) throws IOException {
        String text = Files.readString(path).trim();
        double microjoules;
        try {
            microjoules = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IOException("Could not parse RAPL value from " + path + ": " + text, e);
        }
        return microjoules / MICROJOULES_PER_JOULE;
    }

    private static void requireReadableFile(Path path, String description) throws IOException {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IOException(description + " file is not readable: " + path);
        }
    }

    private static double elapsedSeconds(Instant start, Instant end) {
        if (start == null || end == null) {
            return 0.0;
        }
        Duration elapsed = Duration.between(start, end);
        return elapsed.toNanos() / 1_000_000_000.0;
    }
}
