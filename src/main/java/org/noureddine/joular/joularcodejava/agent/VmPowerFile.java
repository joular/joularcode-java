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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads the power of this virtual machine from a file its host writes, shared with the guest (virtiofs, 9p, a shared folder).
 * The CPU of a virtual machine cannot be measured from inside it, but the host can measure the process the virtual machine runs as.
 *
 * <p>Only the first line is read, in one of the two formats:
 * <ul>
 *   <li>{@code powerjoular}: the file PowerJoular rewrites every second with {@code -o} for a monitored process,
 *       one row of {@code timestamp,cpu_usage,cpu_power} and no header</li>
 *   <li>{@code watts}: the power alone, in watts, written by anything. It is used as it is.</li>
 * </ul>
 *
 * <p>The host writes automatically one-second cycle, which windows cannot follow through a shared folder, so a window may see the row the previous one saw, or catch the file while it is rewritten.
 * A row keeps covering for up to {@link #MAX_REUSES} more windows; after that, the host is taken to have stopped.
 * Its own timestamp tells, so the clocks of the host and the guest are never compared.
 */
final class VmPowerFile implements PowerSource {

    private static final Logger logger = Logger.getLogger(VmPowerFile.class.getName());

    /** A power value never takes that many characters, so a longer line is not one. */
    private static final int MAX_LINE_LENGTH = 256;

    /** How many more windows the last row covers when the next one cannot be read. */
    static final int MAX_REUSES = 2;

    private final Path file;
    private final boolean powerJoularFormat;

    /** The host's timestamp of the last row read, or -1 before the first one. */
    private long lastTimestamp = -1;
    private double lastPower;
    private int reuses = 0;

    private VmPowerFile(Path file, boolean powerJoularFormat) {
        this.file = file;
        this.powerJoularFormat = powerJoularFormat;
    }

    /** Opens the source even if the file is not there yet: the host may start writing it after the application. */
    static VmPowerFile open(Path file, String format) throws IOException {
        if (file == null) {
            throw new IOException("power-source-type=vm needs vm-power-file, the file the host writes the power of"
                    + " this virtual machine to.");
        }
        if (!format.equals("powerjoular") && !format.equals("watts")) {
            throw new IOException("Unknown vm-power-format '" + format + "'. Use powerjoular or watts.");
        }
        logger.log(Level.INFO, "Reading the power of this virtual machine from " + file + " (" + format + " format)");
        return new VmPowerFile(file, format.equals("powerjoular"));
    }

    @Override
    public double watts() throws IOException {
        if (!powerJoularFormat) {
            return parsePower(firstLine(false));
        }

        IOException problem;
        try {
            Row row = readRow();
            if (row.timestamp() != lastTimestamp) {
                lastTimestamp = row.timestamp();
                lastPower = row.power();
                reuses = 0;
                return lastPower;
            }
            problem = new IOException("The host has not updated " + file + " for " + (MAX_REUSES + 1)
                    + " windows. Is PowerJoular still running on the host?");
        } catch (IOException e) {
            problem = e;
        }

        // The last row covers a window that saw no new one, for a little while
        if (lastTimestamp < 0 || reuses == MAX_REUSES) {
            throw problem;
        }
        reuses++;
        return lastPower;
    }

    @Override
    public void close() {
    }

    /** One row of the file PowerJoular writes for a process: the host's Unix time, and the power in watts. */
    private record Row(long timestamp, double power) {
    }

    private Row readRow() throws IOException {
        String[] columns = firstLine(true).split(",", -1);
        if (columns.length == 5) {
            throw new IOException(file + " holds the power of the whole host. Point vm-power-file at the file"
                    + " PowerJoular writes for the process of this virtual machine: with -p <pid> -o <file>, that is"
                    + " <file>-<pid>.csv.");
        }
        if (columns.length != 3) {
            throw new IOException(file + " does not hold the row PowerJoular writes with -o for a process"
                    + " (timestamp,cpu_usage,cpu_power).");
        }
        return new Row(parseTimestamp(columns[0]), parsePower(columns[2]));
    }

    /**
     * The first line of the file, trimmed.
     *
     * @param complete whether the line must end with a newline: a row without one may have been cut short while the
     *                 host was writing it, and a cut number still parses
     */
    private String firstLine(boolean complete) throws IOException {
        // A pipe or a device would block the open until someone writes to it; a missing file is reported below
        if (Files.exists(file) && !Files.isRegularFile(file)) {
            throw new IOException(file + " is not a regular file.");
        }
        byte[] start;
        try (InputStream input = Files.newInputStream(file)) {
            start = input.readNBytes(MAX_LINE_LENGTH + 1);
        } catch (NoSuchFileException e) {
            throw new IOException("There is no " + file + ". Is it shared with this virtual machine, and is the host"
                    + " writing it?", e);
        }
        String text = new String(start, StandardCharsets.UTF_8);
        int end = text.indexOf('\n');
        if (end < 0 && (complete || start.length > MAX_LINE_LENGTH)) {
            throw new IOException("The first line of " + file + " is incomplete or too long.");
        }
        return (end < 0 ? text : text.substring(0, end)).trim();
    }

    private long parseTimestamp(String text) throws IOException {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw new IOException("The first column of " + file + " is not a Unix timestamp. On the host, write the"
                    + " file with -o, not -f: -f starts it with a header.", e);
        }
    }

    private double parsePower(String text) throws IOException {
        double power;
        try {
            power = Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            throw new IOException(file + " does not hold a power in watts where one is expected.", e);
        }
        // PowerJoular writes -1 for a process it could not measure
        if (!Double.isFinite(power) || power < 0) {
            throw new IOException("The host could not measure the power of this virtual machine (" + file
                    + " holds " + power + ").");
        }
        return power;
    }
}
