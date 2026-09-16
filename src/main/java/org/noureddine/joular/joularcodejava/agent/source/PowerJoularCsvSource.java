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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads CPU power from the system CSV file PowerJoular writes with {@code -f} or {@code -o}.
 *
 * <p>The system file holds five columns:
 *
 * <pre>
 *   Timestamp,CPU Usage,Total Power,CPU Power,GPU Power
 *   1756681930,0.2460,18.4500,15.2000,3.2500
 * </pre>
 *
 * <p>{@code -f} adds a row every second and starts the file with the header. {@code -o} rewrites the file every second with the latest row only and no header, which is the better option here. Both are supported: the file is recognised by how many columns its rows carry, so the header is only ever skipped over and never has to be parsed.
 *
 * <p>Monitoring a process makes PowerJoular write a second, three column file holding the power of that process alone. That file is rejected here.
 */
public class PowerJoularCsvSource implements PowerSource {

    private static final Logger logger = Logger.getLogger(PowerJoularCsvSource.class.getName());

    // How much of the end of the file is read to find the last row
    private static final int TAIL_READ_SIZE = 8192;

    // Number of columns in the system file, and in the file of a monitored process
    static final int SYSTEM_COLUMNS = 5;
    static final int TARGET_COLUMNS = 3;

    // Where CPU Power sits in the system file
    static final int SYSTEM_CPU_POWER_INDEX = 3;

    /**
     * How old a row may be before it is treated as unreadable. PowerJoular writes once a second, so anything beyond this means it is no longer running.
     */
    static final long MAX_ROW_AGE_SECONDS = 10;

    private final Path csvPath;

    private double lastKnownPower = 0.0;
    private int staleCycles = 0;
    private boolean missingFileWarningLogged = false;
    private boolean targetFileErrorLogged = false;

    public PowerJoularCsvSource(String csvPath) {
        this.csvPath = Path.of(csvPath);
    }

    @Override
    public void initialize() {
        logger.log(Level.INFO, () -> "Initializing PowerJoular CSV source: " + csvPath);
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
            // In overwrite mode PowerJoular empties the file before writing the new row, so a read can land on nothing at all
            return lastKnownPower;
        }

        if (staleCycles == MAX_STALE_CYCLES + 1) {
            logger.log(Level.WARNING,
                    () -> "No fresh power data in " + csvPath + " for " + MAX_STALE_CYCLES
                            + " cycles. Is PowerJoular still running? No energy will be attributed"
                            + " until it comes back.");
        }
        // Reporting zero stops the agent attributing energy from a value that is no longer true
        return 0.0;
    }

    /**
     * Reads CPU Power from the last row of the file.
     *
     * @return the power in watts, or {@code null} when this cycle could not be read.
     */
    private Double readPower() {
        if (!Files.isRegularFile(csvPath) || !Files.isReadable(csvPath)) {
            if (!missingFileWarningLogged) {
                logger.log(Level.WARNING,
                        () -> "Could not read power data from " + csvPath
                                + ". Ensure PowerJoular is running and writing this file with -f or -o.");
                missingFileWarningLogged = true;
            }
            return null;
        }
        missingFileWarningLogged = false;

        try {
            String lastRow = readLastDataRow();
            if (lastRow == null) {
                return null;
            }

            String[] columns = lastRow.split(",", -1);
            Double timestamp = parseTimestamp(columns[0]);
            if (timestamp == null || isStale(timestamp)) {
                return null;
            }

            return parseCpuPower(columns, cpuPowerIndex(columns.length));
        } catch (IOException e) {
            logger.log(Level.FINE, () -> "Could not read the latest row from " + csvPath);
            return null;
        }
    }

    /**
     * Check the power index in the CSV file, allowing us to know if we're using the whole system file, or the monitored process file
     *
     * @return the column index, or -1 when the row cannot be used.
     */
    private int cpuPowerIndex(int columns) {
        if (columns == SYSTEM_COLUMNS) {
            return SYSTEM_CPU_POWER_INDEX;
        }

        if (columns == TARGET_COLUMNS) {
            if (!targetFileErrorLogged) {
                logger.log(Level.SEVERE,
                        () -> csvPath + " has " + TARGET_COLUMNS + " columns, so it is the file"
                                + " PowerJoular writes for one monitored process, whose power is already the power of that process alone. Joular Code needs the power of the whole system: point this at the system file instead.");
                targetFileErrorLogged = true;
            }
            return -1;
        }

        logger.log(Level.FINE, () -> "Unexpected column count " + columns + " in " + csvPath);
        return -1;
    }

    /**
     * The first column of a row, which PowerJoular writes as a Unix time in seconds
     *
     * @return the time, or {@code null} when the column holds no number, which is what marks the header row and any comment
     */
    static Double parseTimestamp(String firstColumn) {
        try {
            return Double.parseDouble(firstColumn.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double parseCpuPower(String[] columns, int columnIndex) {
        if (columnIndex < 0 || columns.length <= columnIndex) {
            return null;
        }

        String powerText = columns[columnIndex].trim();
        if (powerText.isEmpty()) {
            return null;
        }

        try {
            double value = Double.parseDouble(powerText);
            // PowerJoular writes -1 for a value it could not read at all.
            if (!Double.isFinite(value) || value < 0) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            logger.log(Level.FINE, () -> "Could not parse power value: " + powerText);
            return null;
        }
    }

    /**
     *  Reads the last row of the file that carries data, skipping the header and any blank line.
     */
    private String readLastDataRow() throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(csvPath.toFile(), "r")) {
            long length = file.length();
            if (length <= 0) {
                // In overwrite mode the file is emptied before the new row is written.
                return null;
            }

            int bytesToRead = (int) Math.min(TAIL_READ_SIZE, length);
            long start = length - bytesToRead;
            file.seek(start);

            byte[] data = new byte[bytesToRead];
            file.readFully(data);

            String[] lines = new String(data, StandardCharsets.UTF_8).split("\\r?\\n");
            // Reading from part way into the file almost certainly lands mid-row, so the first slice is skipped: a truncated row can still parse, as a wrong but valid number
            int firstSafeIndex = (start > 0) ? 1 : 0;

            for (int i = lines.length - 1; i >= firstSafeIndex; i--) {
                String line = lines[i].trim();
                // A data row opens with a time; anything else is the header, a comment or a blank.
                if (!line.isEmpty() && parseTimestamp(firstColumnOf(line)) != null) {
                    return line;
                }
            }
            return null;
        }
    }

    private static String firstColumnOf(String line) {
        int comma = line.indexOf(',');
        return comma == -1 ? line : line.substring(0, comma);
    }

    // Whether the row is older than we are willing to use
    private static boolean isStale(double timestamp) {
        // A timestamp ahead of us is left alone: the clock may have stepped, and that is not staleness
        return System.currentTimeMillis() / 1000L - timestamp > MAX_ROW_AGE_SECONDS;
    }

    @Override
    public void close() {}
}
