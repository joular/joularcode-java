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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Appends each window's power per branch to the two CSV files of the results directory: every branch, and the branches of the application only.
 */
final class ResultWriter implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(ResultWriter.class.getName());

    static final String ALL_METHODS_FILE = "methods-power-all.csv";
    static final String APP_METHODS_FILE = "methods-power-app.csv";
    static final String CSV_HEADER = "timestamp,branch,power_watts,energy_joules,interval_seconds,coverage";

    private final Path allFile;
    private final Path appFile;
    private final Map<Path, BufferedWriter> writers = new HashMap<>();
    private boolean writeErrorLogged = false;

    /** Creates the results directory and opens both files, so a path that cannot be written to fails at startup. */
    ResultWriter(Path resultsDir) throws IOException {
        Files.createDirectories(resultsDir);
        allFile = resultsDir.resolve(ALL_METHODS_FILE);
        appFile = resultsDir.resolve(APP_METHODS_FILE);
        try {
            writerFor(allFile);
            writerFor(appFile);
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    /**
     * Appends one row per branch with a positive power, to each file.
     *
     * @param coverage the share of the JVM's CPU time that belonged to sampled threads; below 1.0 the rows are a lower bound
     */
    void write(Map<String, Double> allPower, Map<String, Double> appPower, long timestampMs, double intervalSeconds,
            double coverage) {
        try {
            append(allFile, allPower, timestampMs, intervalSeconds, coverage);
            append(appFile, appPower, timestampMs, intervalSeconds, coverage);
            writeErrorLogged = false;
        } catch (IOException e) {
            // Drop the writers so the next window opens the files again
            close();
            if (!writeErrorLogged) {
                logger.log(Level.SEVERE, "Could not write results to " + allFile.getParent(), e);
                writeErrorLogged = true;
            }
        }
    }

    /** Flushes and closes both files. Safe to call more than once. */
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

    private void append(Path file, Map<String, Double> branchPower, long timestampMs, double intervalSeconds,
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

    private BufferedWriter writerFor(Path file) throws IOException {
        BufferedWriter writer = writers.get(file);
        if (writer == null) {
            // Not following links: the agent may run as root, and a link planted in a shared results directory
            // would otherwise have it append to any file on the system
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
            writers.put(file, writer);
            if (Files.size(file) == 0) {
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
