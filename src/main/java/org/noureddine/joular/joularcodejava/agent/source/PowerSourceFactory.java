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

import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.noureddine.joular.joularcodejava.agent.utils.AgentProperties;

public class PowerSourceFactory {

    private static final Logger logger = Logger.getLogger(PowerSourceFactory.class.getName());

    public static PowerSource getPowerSource(AgentProperties properties) {
        String type = properties.getPowerSourceType();
        String normalizedType = type.trim().toLowerCase(Locale.ROOT);
        logger.log(Level.INFO, () -> "Selecting power source type: " + type);
        try {
            return switch (normalizedType) {
                case "csv"        -> new JoularCoreCSVSource(properties.getJoularCoreCsvPath());
                case "http"       -> new JoularCoreHttpSource(properties.getJoularCoreHttpUrl());
                case "ringbuffer" -> new JoularCoreRingBufferSource(properties.getRingBufferPath());
                case "rapl"       -> new LinuxRaplPowerSource();
                default           -> {
                    logger.log(Level.SEVERE, () -> "Unknown power source type: " + type);
                    yield null;
                }
            };
        } catch (IllegalArgumentException e) {
            logger.log(Level.SEVERE, "Invalid configuration for power source '" + type + "': " + e.getMessage());
            return null;
        }
    }
}
