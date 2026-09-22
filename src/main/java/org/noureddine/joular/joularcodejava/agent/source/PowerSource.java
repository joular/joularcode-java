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

    /** How long an attribution window runs for a source that has no cadence of its own to follow
     */
    long DEFAULT_WINDOW_NANOS = 1_000_000_000L;

    /**
     * Called by the agent as it opens an attribution window, before the first stack sample.
     *
     * <p>A source that closes its windows on its producer's cadence latches here whatever it needs to recognise the next measurement.
     * The default does nothing, which is right for a source whose windows are simply a fixed length.
     */
    default void beginWindow() {
        // Nothing to latch: the default policy is a fixed-length window
    }

    /**
     * Whether the attribution window opened by the last {@link #beginWindow()}, and running for {@code elapsedNs} so far, should be closed now.
     *
     * <p>This is where a source decides how its own cadence maps onto the agent's windows, because it is the only party that knows that cadence.
     * A source fed by an external producer that publishes an average over the second ending at T closes the window on the edge where that value lands, so the stack samples in the window describe the same second as the power they are charged with.
     * It also owns any bound on that wait, since the sensible bound follows from the cadence.
     * The default is the fixed {@link #DEFAULT_WINDOW_NANOS} window, which is right for a source read on demand rather than published to.
     *
     * <p>Called on every sample tick, so it must be cheap and must not block.
     *
     * @param elapsedNs nanoseconds since {@link #beginWindow()} was called
     * @return {@code true} to close the window now
     */
    default boolean isWindowComplete(long elapsedNs) {
        return elapsedNs >= DEFAULT_WINDOW_NANOS;
    }

    /**
     * Close the power source connection. Must be safe to call even if {@link #initialize()}
     * was never called or threw.
     */
    void close();
}
