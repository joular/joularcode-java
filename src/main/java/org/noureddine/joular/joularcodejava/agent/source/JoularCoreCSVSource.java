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

public class JoularCoreCSVSource implements PowerSource {

    private static final Logger logger = Logger.getLogger(
        JoularCoreCSVSource.class.getName()
    );
    private static final int TAIL_READ_SIZE = 8192;
    private final Path csvPath;
    private double lastKnownPower = 0.0;
    private long lastKnownLength = -1L;
    private long lastKnownModified = -1L;
    private boolean missingFileWarningLogged = false;

    public JoularCoreCSVSource(String csvPath) {
        this.csvPath = Path.of(csvPath);
    }

    @Override
    public void initialize() throws Exception {
        logger.log(Level.INFO, () -> "Initializing CSV source: " + csvPath);
    }

    @Override
    public double getCurrentPower() {
        if (!Files.exists(csvPath) || !Files.isRegularFile(csvPath)) {
            if (!missingFileWarningLogged) {
                logger.log(
                    Level.WARNING,
                    () -> "Could not read power data from CSV: " + csvPath
                        + ". Ensure Joular Core is running and generating this file."
                );
                missingFileWarningLogged = true;
            }
            return 0;
        }

        missingFileWarningLogged = false;

        try {
            long currentLength = Files.size(csvPath);
            long currentModified = Files.getLastModifiedTime(csvPath).toMillis();
            if (currentLength == lastKnownLength && currentModified == lastKnownModified) {
                return lastKnownPower;
            }

            String lastLine = readLastNonEmptyLine();
            if (lastLine == null) {
                updateFileMetadata(currentLength, currentModified);
                return lastKnownPower;
            }

            Double parsedPower = parseCpuPower(lastLine);
            if (parsedPower != null) {
                lastKnownPower = parsedPower;
            }
            updateFileMetadata(currentLength, currentModified);
        } catch (IOException e) {
            logger.log(Level.FINE, () -> "Could not read latest power row from CSV: " + csvPath);
        }

        return lastKnownPower;
    }

    @Override
    public void close() {}

    private String readLastNonEmptyLine() throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(csvPath.toFile(), "r")) {
            long length = file.length();
            if (length <= 0) {
                return null;
            }

            int bytesToRead = (int) Math.min(TAIL_READ_SIZE, length);
            long start = length - bytesToRead;
            file.seek(start);

            byte[] data = new byte[bytesToRead];
            file.readFully(data);

            String chunk = new String(data, StandardCharsets.UTF_8);
            String[] lines = chunk.split("\\r?\\n");
            // If we did not start reading at the beginning of the file, the first slice in
            // `lines` is almost certainly a partial line (we landed mid-record). Skipping
            // it avoids parsing a truncated CSV row whose 2nd field may be a wrong-but-valid
            // numeric prefix (e.g., "2.3" of "2.345").
            int firstSafeIndex = (start > 0) ? 1 : 0;
            for (int i = lines.length - 1; i >= firstSafeIndex; i--) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (isHeaderLine(line)) {
                    continue;
                }
                return line;
            }
            return null;
        }
    }

    static Double parseCpuPower(String csvLine) {
        String[] parts = csvLine.split(",");
        if (parts.length <= 1) {
            return null;
        }

        String cpuPowerText = parts[1].trim();
        if (cpuPowerText.isEmpty()) {
            return null;
        }

        try {
            double value = Double.parseDouble(cpuPowerText);
            if (!Double.isFinite(value) || value < 0) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            logger.log(Level.FINE, () -> "Could not parse power value: " + cpuPowerText);
            return null;
        }
    }

    static boolean isHeaderLine(String line) {
        // A data row's first column is a numeric timestamp; any non-numeric first column
        // is treated as a header/comment row. This is structurally robust and avoids
        // false positives from data rows that happen to contain known column names.
        int comma = line.indexOf(',');
        String first = (comma == -1 ? line : line.substring(0, comma)).trim();
        if (first.isEmpty()) {
            return true;
        }
        try {
            Double.parseDouble(first);
            return false;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private void updateFileMetadata(long length, long modified) {
        lastKnownLength = length;
        lastKnownModified = modified;
    }
}
