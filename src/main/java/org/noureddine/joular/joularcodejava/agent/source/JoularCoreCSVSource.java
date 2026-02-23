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

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

public class JoularCoreCSVSource implements PowerSource {

    private static final Logger logger = Logger.getLogger(
        JoularCoreCSVSource.class.getName()
    );
    private static final int TAIL_READ_SIZE = 8192;
    private final String csvPath;
    private double lastKnownPower = 0.0;
    private long lastKnownLength = -1L;
    private long lastKnownModified = -1L;
    private boolean missingFileWarningLogged = false;

    public JoularCoreCSVSource(String csvPath) {
        this.csvPath = csvPath;
    }

    @Override
    public void initialize() throws Exception {
        logger.log(Level.INFO, () -> "Initializing CSV source: " + csvPath);
    }

    @Override
    public double getCurrentPower() {
        File csvFile = new File(csvPath);
        if (!csvFile.exists() || !csvFile.isFile()) {
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

        long currentLength = csvFile.length();
        long currentModified = csvFile.lastModified();
        if (currentLength == lastKnownLength && currentModified == lastKnownModified) {
            return lastKnownPower;
        }

        try {
            String lastLine = readLastNonEmptyLine(csvFile);
            if (lastLine == null) {
                updateFileMetadata(currentLength, currentModified);
                return lastKnownPower;
            }

            Double parsedPower = parseCpuPower(lastLine);
            if (parsedPower != null) {
                lastKnownPower = parsedPower;
            }
        } catch (IOException e) {
            logger.log(Level.FINE, () -> "Could not read latest power row from CSV: " + csvPath);
        }

        updateFileMetadata(currentLength, currentModified);
        return lastKnownPower;
    }

    @Override
    public void close() {}

    private String readLastNonEmptyLine(File csvFile) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(csvFile, "r")) {
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
            for (int i = lines.length - 1; i >= 0; i--) {
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

    private Double parseCpuPower(String csvLine) {
        String[] parts = csvLine.split(",");
        if (parts.length <= 1) {
            return null;
        }

        String cpuPowerText = parts[1].trim();
        if (cpuPowerText.isEmpty()) {
            return null;
        }

        try {
            return Double.parseDouble(cpuPowerText);
        } catch (NumberFormatException e) {
            logger.log(Level.FINE, () -> "Could not parse power value: " + cpuPowerText);
            return null;
        }
    }

    private boolean isHeaderLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("total")
            || lower.startsWith("timestamp")
            || lower.contains("cpu_power");
    }

    private void updateFileMetadata(long length, long modified) {
        lastKnownLength = length;
        lastKnownModified = modified;
    }
}
