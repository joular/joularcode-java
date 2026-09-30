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

import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.noureddine.joular.joularcodejava.agent.PowerModel.CpuUsage;
import org.noureddine.joular.joularcodejava.agent.ThreadSampler.Samples;

/**
 * The monitoring loop. It runs on its own daemon thread, one window at a time: sample the stacks until the power
 * source ends the window, split that window's power between the branches seen, and append the results.
 */
final class Monitor {

    private static final Logger logger = Logger.getLogger(Monitor.class.getName());

    static final String THREAD_NAME = "Joular-Agent-Monitor";

    private static final double LOW_COVERAGE_THRESHOLD = 0.5;
    private static final double HIGH_OVERRUN_THRESHOLD = 0.25;
    private static final long STOP_TIMEOUT_MS = 2000;

    private final Callable<PowerSource> powerSourceOpener;
    private final long sampleRateNs;
    private final List<String> appPrefixes;
    private final ResultWriter results;
    private final OperatingSystemMXBean osBean;
    private final ThreadSampler sampler;
    private final Thread thread;

    private volatile boolean running = true;
    private boolean powerLost = false;
    private boolean windowErrorLogged = false;
    private boolean overrunWarned = false;
    private boolean lowCoverageWarned = false;

    /**
     * Checks the JVM and opens the results files. The power source is opened later, on the monitor thread.
     *
     * @throws IllegalStateException if this JVM cannot report the CPU time of its threads and of itself
     */
    Monitor(Config config, Callable<PowerSource> powerSourceOpener) throws IOException {
        if (!(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean threadBean)
                || !threadBean.isThreadCpuTimeSupported()) {
            throw new IllegalStateException("Unsupported JVM: it cannot report the CPU time of its threads.");
        }
        if (!(ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os)
                || os.getProcessCpuTime() < 0) {
            throw new IllegalStateException("Unsupported JVM: it cannot report its own CPU time.");
        }
        threadBean.setThreadCpuTimeEnabled(true);

        this.powerSourceOpener = powerSourceOpener;
        this.sampleRateNs = TimeUnit.MILLISECONDS.toNanos(config.sampleRateMs());
        this.appPrefixes = config.appPrefixes();
        this.osBean = os;
        this.results = new ResultWriter(config.resultsDir());
        this.thread = new Thread(this::run, THREAD_NAME);
        this.thread.setDaemon(true);
        this.sampler = new ThreadSampler(threadBean, thread.threadId());
    }

    void start() {
        thread.start();
    }

    boolean isAlive() {
        return thread.isAlive();
    }

    /** Stops the loop and waits a little for it to close the source and the files. */
    void stop() {
        running = false;
        // Not an interrupt: it would close the results files if it came while they are written
        LockSupport.unpark(thread);
        try {
            thread.join(STOP_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** CPU times at one instant. */
    private record CpuSnapshot(long nanoTime, long processCpuNs, Map<Long, Long> threadCpuNs) {
    }

    private CpuSnapshot snapshot() {
        // The monitor's own thread is taken out of the JVM's CPU time, as it is out of the threads'
        return new CpuSnapshot(System.nanoTime(), osBean.getProcessCpuTime() - sampler.ownCpuTime(),
                sampler.cpuTimes());
    }

    private void run() {
        // Nothing may escape: the monitor must never disturb the application
        try (PowerSource powerSource = powerSourceOpener.call()) {
            monitor(powerSource);
        } catch (IOException e) {
            // The message says what to fix, a stack trace would only bury it
            logger.log(Level.SEVERE, "Cannot measure the CPU power: " + e.getMessage()
                    + " Monitoring is off, the application carries on.");
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "Monitoring stopped on an unexpected error, the application carries on.", t);
        } finally {
            results.close();
        }
    }

    private void monitor(PowerSource powerSource) {
        // getCpuLoad covers the time since its previous call, by anyone in this JVM, so the first one covers nothing
        osBean.getCpuLoad();

        // Each window starts where the previous one ended, so the time spent writing results is not lost
        CpuSnapshot windowStart = snapshot();
        while (running) {
            try {
                windowStart = runWindow(powerSource, windowStart);
                windowErrorLogged = false;
            } catch (RuntimeException e) {
                logger.log(windowErrorLogged ? Level.FINE : Level.SEVERE, "Error in a monitoring window, continuing.", e);
                windowErrorLogged = true;
                windowStart = snapshot();
            }
        }
    }

    /** Runs one window and returns the snapshot it ended on, which the next window starts from. */
    private CpuSnapshot runWindow(PowerSource powerSource, CpuSnapshot start) {
        Samples samples = sampleWindow(powerSource, start.nanoTime());
        if (!running) {
            // Cut short by shutdown: a fraction of a window is not worth a row
            return start;
        }
        CpuSnapshot end = snapshot();
        // Read right after the snapshot, so the power and the CPU times cover the same window
        double cpuPower = readPower(powerSource);
        double systemLoad = osBean.getCpuLoad();
        writeResults(samples, start, end, cpuPower, systemLoad);
        return end;
    }

    /** Samples the stacks at the configured rate until the power source ends the window. */
    private Samples sampleWindow(PowerSource powerSource, long windowStartNs) {
        Samples samples = new Samples();

        // Samples are due at fixed times, so the time a thread dump takes does not stretch the interval
        long nextSampleNs = System.nanoTime();
        while (running && !powerSource.windowOver(System.nanoTime() - windowStartNs)) {
            sampler.sample(samples);

            nextSampleNs += sampleRateNs;
            long waitNs = nextSampleNs - System.nanoTime();
            if (waitNs > 0) {
                // Returns early on unpark, which is how stop() ends the wait
                LockSupport.parkNanos(waitNs);
            } else {
                samples.missed++;
                nextSampleNs = System.nanoTime();
            }
        }
        return samples;
    }

    /** The CPU power over the window, or 0 when it could not be read: attributing nothing beats inventing energy. */
    private double readPower(PowerSource powerSource) {
        try {
            double watts = powerSource.watts();
            if (!Double.isFinite(watts) || watts < 0) {
                throw new IOException("The power source reported " + watts + " W.");
            }
            if (powerLost) {
                logger.log(Level.INFO, "The CPU power can be read again, energy is attributed again.");
                powerLost = false;
            }
            return watts;
        } catch (IOException e) {
            if (!powerLost) {
                logger.log(Level.WARNING, "Could not read the CPU power: " + e.getMessage()
                        + " No energy is attributed until it can be read again.");
                powerLost = true;
            }
            return 0.0;
        }
    }

    private void writeResults(Samples samples, CpuSnapshot start, CpuSnapshot end, double cpuPower,
            double systemLoad) {
        long elapsedNs = end.nanoTime() - start.nanoTime();
        double intervalSeconds = elapsedNs / 1e9;
        warnIfOverrunning(samples, intervalSeconds);

        CpuUsage usage = PowerModel.cpuUsage(samples.branches.keySet(), start.threadCpuNs(), end.threadCpuNs());
        warnIfLowCoverage(usage.coverage());

        double processShare = PowerModel.processShare(end.processCpuNs() - start.processCpuNs(), elapsedNs,
                Runtime.getRuntime().availableProcessors(), systemLoad);
        Map<Long, Double> threadPower = PowerModel.threadPower(usage, cpuPower * processShare);

        Map<String, Double> allPower = PowerModel.branchPower(samples.branches, threadPower, Branch::name);
        Map<String, Double> appPower = appPrefixes.isEmpty()
                ? allPower
                : PowerModel.branchPower(samples.branches, threadPower, branch -> branch.appName(appPrefixes));

        results.write(allPower, appPower, System.currentTimeMillis(), intervalSeconds, usage.coverage());
    }

    private void warnIfOverrunning(Samples samples, double intervalSeconds) {
        logger.log(Level.FINE, () -> "Window: " + samples.taken + " samples over " + intervalSeconds + " s, "
                + samples.missed + " overran the interval");

        boolean overrunning = samples.taken > 0 && (double) samples.missed / samples.taken >= HIGH_OVERRUN_THRESHOLD;
        if (overrunning && !overrunWarned) {
            long achievedMs = Math.round(intervalSeconds * 1000 / samples.taken);
            logger.log(Level.WARNING, "The stack sampler cannot keep up with stack-monitoring-sample-rate="
                    + TimeUnit.NANOSECONDS.toMillis(sampleRateNs) + " ms: " + samples.taken + " samples were taken"
                    + " in the last window, about one every " + achievedMs + " ms. Dumping the stacks of this many"
                    + " threads costs more than the interval allows. Raise the sample rate, or accept coarser data.");
        }
        overrunWarned = overrunning;
    }

    private void warnIfLowCoverage(double coverage) {
        logger.log(Level.FINE, () -> "Window coverage: " + coverage);

        boolean low = coverage > 0 && coverage < LOW_COVERAGE_THRESHOLD;
        if (low && !lowCoverageWarned) {
            logger.log(Level.WARNING, "Only " + Math.round(coverage * 100) + "% of this JVM's CPU time belonged to"
                    + " threads that were sampled, so the power reported per method is a lower bound. Short-lived"
                    + " threads and virtual threads, which the JVM does not report here, are the usual causes;"
                    + " a sample rate too coarse for the number of threads is another.");
        }
        lowCoverageWarned = low;
    }
}
