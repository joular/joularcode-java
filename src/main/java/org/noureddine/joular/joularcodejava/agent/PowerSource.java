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

/**
 * Where the agent gets the machine's CPU power from.
 * A source is opened, read and closed on the monitor thread only.
 *
 * <p>The monitor calls {@link #watts()} once per window, as the window ends, so a window always runs from one call to the next.
 */
interface PowerSource extends AutoCloseable {

    /** Length of a window, for a source with no cadence of its own. */
    long ONE_SECOND_NS = 1_000_000_000L;

    /**
     * The CPU's average power since the previous call, or since the source was opened.
     *
     * @return watts, finite and {@code >= 0}
     * @throws IOException when nothing usable could be read, in which case the window gets no energy
     */
    double watts() throws IOException;

    /**
     * Whether the window should end now. Called on every stack sample, so it must be cheap.
     *
     * @param elapsedNs time since the window began
     */
    default boolean windowOver(long elapsedNs) {
        return elapsedNs >= ONE_SECOND_NS;
    }

    @Override
    void close();

    /**
     * Opens the source named by {@code power-source-type}. {@code auto} reads RAPL directly on Linux, and the
     * PowerJoular ring buffer everywhere else, including Linux machines without RAPL such as the Raspberry Pi.
     */
    static PowerSource open(Config config) throws IOException {
        return switch (config.powerSourceType()) {
            case "auto" -> LinuxRapl.isPresent()
                    ? LinuxRapl.open()
                    : PowerJoularRingBuffer.open(config.ringBufferPath());
            case "rapl" -> LinuxRapl.open();
            case "ringbuffer" -> PowerJoularRingBuffer.open(config.ringBufferPath());
            case "vm" -> VmPowerFile.open(config.vmPowerFile(), config.vmPowerFormat());
            default -> throw new IOException("Unknown power-source-type '" + config.powerSourceType() + "'. Use auto, rapl, ringbuffer or vm.");
        };
    }
}
