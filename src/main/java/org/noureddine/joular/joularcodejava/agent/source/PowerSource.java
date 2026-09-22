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
 * Interface for different power data sources.
 *
 * <p>All implementations must follow the same failure semantics:
 * <ul>
 *   <li>Before any successful read, {@link #getCurrentPower()} returns {@code 0.0}.</li>
 *   <li>On a successful read, the returned value is cached as the "last known power".</li>
 *   <li>On transient errors (mid-write torn reads, parse failures, missing row, temporarily
 *       unreadable device data), implementations return the last known power so the agent smooths
 *       over momentary producer unavailability.</li>
 *   <li>That cover is bounded. After a handful of consecutive unreadable cycles, implementations
 *       backed by an external producer report {@code 0.0} rather than going on replaying a value
 *       that is no longer true, and log a warning once. Zero means no energy is attributed for the
 *       cycle, which is preferable to attributing energy that was never measured.</li>
 *   <li>Implementations must reject {@code NaN}, infinite, and negative values returned by
 *       the underlying source — these are treated as transient errors.</li>
 *   <li>Returned values are CPU power in Watts and must be {@code &gt;= 0} and finite.</li>
 * </ul>
 */
public interface PowerSource {

    /**
     * How many consecutive unreadable cycles an external source (csv, ring buffer) covers with the last known value before reporting {@code 0.0}.
     */
    int MAX_STALE_CYCLES = 5;

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
     * Counter incremented by the producer on each new measurement, or -1 if it has no such counter.
     *
     * <p>The agent ends its attribution window when this value changes, so the window covers the same time span as the producer's measurement.
     * PowerJoular reports the average power over the second ending at T, so the stack samples in that window belong to the same second as the power they are charged with.
     * If this returns -1, the agent uses its own one second timer instead, which can drift relative to the producer.
     *
     * <p>Called on every sample tick, so it must be cheap and must not block.
     *
     * @return the producer's cycle counter, or -1 when unknown
     */
    default long cycleCounter() {
        return -1;
    }

    /**
     * Close the power source connection. Must be safe to call even if {@link #initialize()}
     * was never called or threw.
     */
    void close();
}
