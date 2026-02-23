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

/**
 * Interface for different power data sources from Joular Core
 */
public interface PowerSource {
    /**
     * Initialize the power source connection.
     */
    void initialize() throws Exception;

    /**
     * Get the current power consumption in Watts.
     *
     * @return Power in Watts.
     */
    double getCurrentPower();

    /**
     * Close the power source connection.
     */
    void close();
}