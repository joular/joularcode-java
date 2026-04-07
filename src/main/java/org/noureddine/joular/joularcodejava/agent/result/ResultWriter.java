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

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ResultWriter {

    private static final Logger logger = Logger.getLogger(ResultWriter.class.getName());
    private final String resultsPath;

    public ResultWriter(String resultsPath) {
        this.resultsPath = resultsPath;
        try {
            Files.createDirectories(Paths.get(resultsPath));
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not create results directory: " + resultsPath, e);
        }
    }

    public void writeRuntimeMethods(
            Map<String, Double> methodPower,
            long timestamp,
            double intervalSeconds,
            String fileName) {
        String filePath = Paths.get(resultsPath, fileName).toString();
        logger.log(Level.INFO, () -> "Writing " + methodPower.size() + " methods to " + filePath);
        try (
                PrintWriter writer = new PrintWriter(new FileWriter(filePath, true))) {
            Path path = Paths.get(filePath);
            if (Files.size(path) == 0L) {
                writer.println("timestamp,branch,power_watts,energy_joules,interval_seconds");
            }
            for (Map.Entry<String, Double> entry : methodPower.entrySet()) {
                double powerWatts = entry.getValue();
                if (powerWatts > 0) {
                    double energyJoules = powerWatts * intervalSeconds;
                    writer.printf(
                            Locale.US,
                            "%d,%s,%.9f,%.9f,%.9f%n",
                            timestamp,
                            entry.getKey(),
                            powerWatts,
                            energyJoules,
                            intervalSeconds);
                }
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error writing runtime methods to " + filePath, e);
        }
    }
}
