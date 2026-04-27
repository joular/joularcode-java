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
 * Interface for different power data sources from Joular Core.
 *
 * <p>All implementations must follow the same failure semantics:
 * <ul>
 *   <li>Before any successful read, {@link #getCurrentPower()} returns {@code 0.0}.</li>
 *   <li>On a successful read, the returned value is cached as the "last known power".</li>
 *   <li>On transient errors (network blip, mid-write torn reads, parse failures, missing
 *       row), implementations return the last known power so the agent smooths over momentary
 *       producer unavailability.</li>
 *   <li>Implementations must reject {@code NaN}, infinite, and negative values returned by
 *       the underlying source — these are treated as transient errors.</li>
 *   <li>Returned values are CPU power in Watts and must be {@code &gt;= 0} and finite.</li>
 * </ul>
 */
public interface PowerSource {
    /**
     * Initialize the power source connection.
     *
     * @throws Exception if the source cannot be opened. The agent will log and abort startup.
     */
    void initialize() throws Exception;

    /**
     * Get the current power consumption in Watts.
     *
     * @return power in Watts; always finite and {@code >= 0}.
     */
    double getCurrentPower();

    /**
     * Close the power source connection. Must be safe to call even if {@link #initialize()}
     * was never called or threw.
     */
    void close();
}