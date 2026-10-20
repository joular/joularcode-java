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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Appends each window's power per branch to the CSV files of the results directory: every branch, and the branches of the application only.
 * The lines files, written only when lines are monitored, have the same branches with each frame's line.
 */
final class ResultWriter implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(ResultWriter.class.getName());

    static final String ALL_METHODS_FILE = "methods-power-all.csv";
    static final String APP_METHODS_FILE = "methods-power-app.csv";
    static final String ALL_LINES_FILE = "lines-power-all.csv";
    static final String APP_LINES_FILE = "lines-power-app.csv";
    static final String CSV_HEADER = "timestamp,branch,power_watts,energy_joules,interval_seconds,coverage";

    private final Path resultsDir;
    private final Map<String, BufferedWriter> writers = new HashMap<>();
    private boolean writeErrorLogged = false;

    /** Creates the results directory and opens the files, so a path that cannot be written to fails at startup. */
    ResultWriter(Path resultsDir, Collection<String> files) throws IOException {
        this.resultsDir = resultsDir;
        Files.createDirectories(resultsDir);
        try {
            for (String file : files) {
                writerFor(file);
            }
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    /**
     * Appends one row per branch with a positive power, to each file.
     *
     * @param powerByFile each file's power per branch
     * @param coverage the share of the JVM's CPU time that belonged to sampled threads; below 1.0 the rows are a lower bound
     */
    void write(Map<String, Map<String, Double>> powerByFile, long timestampMs, double intervalSeconds,
            double coverage) {
        try {
            for (Map.Entry<String, Map<String, Double>> entry : powerByFile.entrySet()) {
                append(entry.getKey(), entry.getValue(), timestampMs, intervalSeconds, coverage);
            }
            writeErrorLogged = false;
        } catch (IOException e) {
            // Drop the writers so the next window opens the files again
            close();
            if (!writeErrorLogged) {
                logger.log(Level.SEVERE, "Could not write results to " + resultsDir, e);
                writeErrorLogged = true;
            }
        }
    }

    /** Flushes and closes the files. Safe to call more than once. */
    @Override
    public void close() {
        for (BufferedWriter writer : writers.values()) {
            try {
                writer.close();
            } catch (IOException e) {
                logger.log(Level.FINE, "Could not close a results file", e);
            }
        }
        writers.clear();
    }

    private void append(String file, Map<String, Double> branchPower, long timestampMs, double intervalSeconds,
            double coverage) throws IOException {
        if (branchPower.isEmpty()) {
            return;
        }
        BufferedWriter writer = writerFor(file);
        for (Map.Entry<String, Double> entry : branchPower.entrySet()) {
            double power = entry.getValue();
            if (power > 0) {
                writer.write(String.format(Locale.ROOT, "%d,%s,%.9f,%.9f,%.9f,%.4f%n", timestampMs,
                        csvEscape(entry.getKey()), power, power * intervalSeconds, intervalSeconds, coverage));
            }
        }
        writer.flush();
    }

    private BufferedWriter writerFor(String file) throws IOException {
        BufferedWriter writer = writers.get(file);
        if (writer == null) {
            Path path = resultsDir.resolve(file);
            // Not following links: the agent may run as root, and a link planted in a shared results directory
            // would otherwise have it append to any file on the system
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
            writers.put(file, writer);
            if (Files.size(path) == 0) {
                writer.write(CSV_HEADER);
                writer.newLine();
                writer.flush();
            }
        }
        return writer;
    }

    static String csvEscape(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
