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

package org.noureddine.joular.joularcodejava.agent.result;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ResultWriter {

    private static final Logger logger = Logger.getLogger(ResultWriter.class.getName());
    private static final String CSV_HEADER = "timestamp,branch,power_watts,energy_joules,interval_seconds";

    private final Path resultsDir;
    private final Map<String, BufferedWriter> openWriters = new HashMap<>();
    private boolean anyWriteErrorLogged = false;

    public ResultWriter(String resultsPath) {
        this.resultsDir = Paths.get(resultsPath);
    }

    /**
     * Verifies that the results directory exists (creating it if necessary) and is writable.
     * Throws IOException if the directory cannot be created or is not writable, so the
     * agent fails fast at startup instead of silently dropping results.
     */
    public void verifyWritable() throws IOException {
        Files.createDirectories(resultsDir);
        if (!Files.isDirectory(resultsDir)) {
            throw new IOException("Results path is not a directory: " + resultsDir);
        }
        Path probe = resultsDir.resolve(".joular-write-probe");
        try {
            Files.write(probe, new byte[0],
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException ignored) {
                // probe cleanup is best-effort
            }
        }
    }

    public void writeRuntimeMethods(
            Map<String, Double> methodPower,
            long timestamp,
            double intervalSeconds,
            String fileName) {
        Path path = resultsDir.resolve(fileName);
        logger.log(Level.INFO, () -> "Writing " + methodPower.size() + " methods to " + path);

        try {
            BufferedWriter writer = openWriters.get(fileName);
            if (writer == null) {
                boolean needsHeader = !Files.exists(path) || Files.size(path) == 0L;
                writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (needsHeader) {
                    writer.write(CSV_HEADER);
                    writer.newLine();
                }
                openWriters.put(fileName, writer);
            }

            for (Map.Entry<String, Double> entry : methodPower.entrySet()) {
                double powerWatts = entry.getValue();
                if (powerWatts > 0) {
                    double energyJoules = powerWatts * intervalSeconds;
                    writer.write(String.format(
                            Locale.US,
                            "%d,%s,%.9f,%.9f,%.9f",
                            timestamp,
                            csvEscape(entry.getKey()),
                            powerWatts,
                            energyJoules,
                            intervalSeconds));
                    writer.newLine();
                }
            }
            writer.flush();
            anyWriteErrorLogged = false;
        } catch (IOException e) {
            // If the writer is in a bad state, drop it so the next call retries from scratch.
            BufferedWriter stale = openWriters.remove(fileName);
            if (stale != null) {
                try {
                    stale.close();
                } catch (IOException ignored) {
                    // best-effort
                }
            }
            if (!anyWriteErrorLogged) {
                logger.log(Level.SEVERE, "Error writing runtime methods to " + path, e);
                anyWriteErrorLogged = true;
            } else {
                logger.log(Level.FINE, () -> "Error writing runtime methods to " + path + ": " + e.getMessage());
            }
        }
    }

    /**
     * Flush and close any open writers. Safe to call multiple times.
     */
    public void close() {
        for (Map.Entry<String, BufferedWriter> entry : openWriters.entrySet()) {
            try {
                entry.getValue().flush();
                entry.getValue().close();
            } catch (IOException e) {
                logger.log(Level.FINE, () -> "Error closing writer for " + entry.getKey() + ": " + e.getMessage());
            }
        }
        openWriters.clear();
    }

    static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuoting = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                needsQuoting = true;
                break;
            }
        }
        if (!needsQuoting) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                sb.append('"').append('"');
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
