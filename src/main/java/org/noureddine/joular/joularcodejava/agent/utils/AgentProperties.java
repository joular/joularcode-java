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

package org.noureddine.joular.joularcodejava.agent.utils;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AgentProperties {

    private static final Logger logger = Logger.getLogger(AgentProperties.class.getName());
    private final Properties properties = new Properties();

    public AgentProperties() {
        loadClasspathDefaults();

        // Check system properties
        String configPath = System.getProperty("joularcodejava.properties");
        
        // If not provided, check local directory for properties file
        if (configPath == null || configPath.isEmpty()) {
            String workingDir = System.getProperty("user.dir");
            configPath = workingDir + File.separator + "joularcodejava.properties";
        }
        
        File configFile = new File(configPath);
        
        // If file does not exist, load defaults
        if (!configFile.exists() || !configFile.isFile()) {
            logger.log(Level.INFO, "Could not load properties from " + configPath + ", using defaults.");
            return;
        }
        
        // Load properties from file
        try (FileInputStream in = new FileInputStream(configPath)) {
            properties.load(in);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not load properties from " + configPath + ", using defaults.");
        }
    }

    public String getPowerSourceType() {
        String value = properties.getProperty("power-source-type", "ringbuffer");
        if (value == null || value.trim().isEmpty()) {
            return "ringbuffer";
        }
        return value.trim();
    }

    public String getJoularCoreCsvPath() {
        return properties.getProperty(
            "joular-core-csv-path",
            "joularcore-data.csv"
        );
    }

    public String getJoularCoreHttpUrl() {
        return properties.getProperty(
            "joular-core-http-url",
            "http://localhost:8080/data"
        );
    }

    public String getRingBufferPath() {
        String defaultPath;
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            defaultPath = "Local\\JoularCoreRing";
        } else if (os.contains("mac")) {
            defaultPath = "/tmp/joularcorering";
        } else {
            defaultPath = "/dev/shm/joularcorering";
        }
        return properties.getProperty(
            "joular-core-ringbuffer-path",
            defaultPath
        );
    }

    public long getSampleRateMs() {
        return getPositiveLongProperty("stack-monitoring-sample-rate", 10L);
    }

    public String getResultsPath() {
        return properties.getProperty("results-path", "joular-agent-results");
    }

    public String getMethodsFilteringPrefix() {
        return properties.getProperty("methods-filtering-prefix", "");
    }

    public List<String> getMethodsFilteringPrefixes() {
        String value = getMethodsFilteringPrefix();
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String[] rawPrefixes = value.split(",");
        List<String> prefixes = new ArrayList<>(rawPrefixes.length);
        for (String prefix : rawPrefixes) {
            if (prefix != null) {
                String cleaned = prefix.trim();
                if (!cleaned.isEmpty()) {
                    prefixes.add(cleaned);
                }
            }
        }

        return prefixes;
    }

    private void loadClasspathDefaults() {
        try (InputStream in = AgentProperties.class.getClassLoader().getResourceAsStream("joularcodejava.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            logger.log(Level.FINE, "Could not load classpath default properties.", e);
        }
    }

    private long getPositiveLongProperty(String key, long defaultValue) {
        String rawValue = properties.getProperty(key);
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(rawValue.trim());
            if (value > 0) {
                return value;
            }
            logger.log(Level.WARNING, () -> "Property " + key + " must be > 0. Using default: " + defaultValue);
        } catch (NumberFormatException e) {
            logger.log(Level.WARNING, () -> "Property " + key + " has invalid value '" + rawValue + "'. Using default: " + defaultValue);
        }
        return defaultValue;
    }
}
