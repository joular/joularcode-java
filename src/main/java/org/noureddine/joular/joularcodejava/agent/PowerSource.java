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
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Where the agent gets the machine's CPU power from.
 * A source is opened, read and closed on the monitor thread only.
 *
 * <p>The monitor calls {@link #watts()} once per window, as the window ends, so a window always runs from one call to the next.
 */
interface PowerSource extends AutoCloseable {

    Logger logger = Agent.logger(PowerSource.class);

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

    /**
     * Whether {@link #watts()} covers exactly the time since the previous call, so that a window cut short by
     * shutdown can still be charged for what it lasted.
     */
    default boolean measuresSincePreviousCall() {
        return false;
    }

    @Override
    void close();

    /**
     * Opens the source named by {@code power-source-type}. {@code auto} takes the first that opens: RAPL where there
     * is one, then the PowerJoular ring buffer, then the virtual machine file.
     */
    static PowerSource open(Config config) throws IOException {
        return switch (config.powerSourceType()) {
            case "auto" -> openAuto(config);
            case "rapl" -> LinuxRapl.open();
            case "ringbuffer" -> PowerJoularRingBuffer.open(config.ringBufferPath());
            case "vm" -> VmPowerFile.open(config.vmPowerFile(), config.vmPowerFormat());
            default -> throw new IOException("Unknown power-source-type '" + config.powerSourceType() + "'. Use auto, rapl, ringbuffer or vm.");
        };
    }

    private static PowerSource openAuto(Config config) throws IOException {
        // A RAPL that is there but only readable by root falls through to the ring buffer, instead of ending monitoring
        PowerSource source = LinuxRapl.isPresent() ? tryOpen("rapl", LinuxRapl::open) : null;
        if (source == null) {
            source = tryOpen("ringbuffer", () -> PowerJoularRingBuffer.open(config.ringBufferPath()));
        }
        if (source == null) {
            source = tryOpen("vm", () -> VmPowerFile.open(config.vmPowerFile(), config.vmPowerFormat()));
        }
        if (source == null) {
            throw new IOException("No power source could be opened. Set power-source-type to the one to use.");
        }
        return source;
    }

    /** The source, or null when it cannot be opened. */
    private static PowerSource tryOpen(String type, Callable<PowerSource> opener) {
        try {
            PowerSource source = opener.call();
            logger.log(Level.INFO, "power-source-type=auto picked " + type + ".");
            return source;
        } catch (Exception e) {
            logger.log(Level.WARNING, "power-source-type=auto: " + type + " cannot be used, trying the next one. "
                    + e.getMessage());
            return null;
        }
    }
}
