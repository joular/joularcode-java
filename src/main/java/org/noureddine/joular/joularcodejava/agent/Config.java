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
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The agent's configuration, read from {@code joularcodejava.properties}.
 *
 * <p>The file is the one named by the {@code joularcodejava.properties} system property, or else the one in the
 * working directory. A missing file or an empty value means the default.
 *
 * @param powerSourceType {@code auto}, {@code rapl}, {@code ringbuffer} or {@code vm}
 * @param ringBufferPath  the ring buffer PowerJoular writes with {@code -r}
 * @param vmPowerFile     the file the host writes this virtual machine's power to, or null when not set
 * @param vmPowerFormat   {@code powerjoular} or {@code watts}, the format of {@code vmPowerFile}
 * @param sampleRateMs    how often the stacks are sampled, in milliseconds
 * @param resultsDir      where the CSV files are written
 * @param appPrefixes     what a frame must start with to be in the app file; empty means no filter
 */
record Config(String powerSourceType, Path ringBufferPath, Path vmPowerFile, String vmPowerFormat,
        long sampleRateMs, Path resultsDir, List<String> appPrefixes) {

    private static final Logger logger = Logger.getLogger(Config.class.getName());

    static final long DEFAULT_SAMPLE_RATE_MS = 10;
    private static final long LOW_SAMPLE_RATE_MS = 5;
    // Samples past a window's length would stretch the window itself
    private static final long MAX_SAMPLE_RATE_MS = 1000;

    static Config load() {
        String configuredPath = System.getProperty("joularcodejava.properties", "");
        Path file = Path.of(configuredPath.isBlank() ? "joularcodejava.properties" : configuredPath);

        Properties properties = new Properties();
        if (!Files.isRegularFile(file)) {
            logger.log(Level.INFO, "No configuration file at " + file.toAbsolutePath() + ", using defaults.");
        } else {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not read " + file.toAbsolutePath() + ", using defaults.", e);
            }
        }
        return from(properties);
    }

    static Config from(Properties properties) {
        String vmPowerFile = get(properties, "vm-power-file", "");
        return new Config(
                get(properties, "power-source-type", "auto").toLowerCase(Locale.ROOT),
                Path.of(get(properties, "powerjoular-ringbuffer-path", defaultRingBufferPath())),
                vmPowerFile.isEmpty() ? null : Path.of(vmPowerFile),
                get(properties, "vm-power-format", "powerjoular").toLowerCase(Locale.ROOT),
                sampleRate(get(properties, "stack-monitoring-sample-rate", "")),
                Path.of(get(properties, "results-path", "joular-code-java-results")),
                prefixes(get(properties, "methods-filtering-prefix", "")));
    }

    private static String get(Properties properties, String key, String defaultValue) {
        String value = properties.getProperty(key, "").trim();
        return value.isEmpty() ? defaultValue : value;
    }

    private static long sampleRate(String value) {
        if (value.isEmpty()) {
            return DEFAULT_SAMPLE_RATE_MS;
        }
        try {
            long rate = Long.parseLong(value);
            if (rate > 0 && rate <= MAX_SAMPLE_RATE_MS) {
                if (rate < LOW_SAMPLE_RATE_MS) {
                    logger.log(Level.WARNING, "stack-monitoring-sample-rate is " + rate + " ms. Below "
                            + LOW_SAMPLE_RATE_MS + " ms, sampling can noticeably slow the application down.");
                }
                return rate;
            }
        } catch (NumberFormatException ignored) {
            // Reported below, like a value out of range
        }
        logger.log(Level.WARNING, "stack-monitoring-sample-rate must be a number of milliseconds from 1 to "
                + MAX_SAMPLE_RATE_MS + ", not '" + value + "'. Using " + DEFAULT_SAMPLE_RATE_MS + " ms.");
        return DEFAULT_SAMPLE_RATE_MS;
    }

    private static List<String> prefixes(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(Predicate.not(String::isEmpty))
                .toList();
    }

    /** Where PowerJoular puts its ring buffer on each OS. */
    private static String defaultRingBufferPath() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String programData = System.getenv("PROGRAMDATA");
            return (programData == null || programData.isBlank() ? "C:\\ProgramData" : programData) + "\\powerjoular";
        }
        return os.contains("mac") ? "/tmp/powerjoular" : "/dev/shm/powerjoular";
    }
}
