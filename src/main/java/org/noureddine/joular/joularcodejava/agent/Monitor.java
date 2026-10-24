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
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.noureddine.joular.joularcodejava.agent.PowerModel.CpuUsage;
import org.noureddine.joular.joularcodejava.agent.ThreadSampler.Samples;

/**
 * The monitoring loop. It runs on its own daemon thread, one window at a time: sample the stacks until the power
 * source ends the window, split that window's power between the branches seen, and append the results.
 */
final class Monitor {

    private static final Logger logger = Agent.logger(Monitor.class);

    static final String THREAD_NAME = "Joular-Agent-Monitor";

    private static final double LOW_COVERAGE_THRESHOLD = 0.5;
    private static final double HIGH_OVERRUN_THRESHOLD = 0.25;
    private static final long STOP_TIMEOUT_MS = 2000;

    private final Callable<PowerSource> powerSourceOpener;
    private final long sampleRateNs;
    // Each results file and how it names a branch
    private final Map<String, Function<Branch, String>> namings = new LinkedHashMap<>();
    private final Path resultsDir;
    private final boolean aggregate;
    private final OperatingSystemMXBean osBean;
    private final ThreadSampler sampler;
    private final Thread thread;

    /** Opened on the monitor thread, after the power source. */
    private ResultWriter results;
    // The machine's load from /proc/stat where there is one, else from the JVM
    private boolean procStat = ProcStat.isAvailable();
    private volatile boolean running = true;
    private boolean powerLost = false;
    private boolean windowErrorLogged = false;
    private boolean overrunWarned = false;
    private boolean lowCoverageWarned = false;

    /**
     * Checks the JVM. The power source and the results files are opened later, on the monitor thread.
     *
     * @throws IllegalStateException if this JVM cannot report the CPU time of its threads and of itself
     */
    Monitor(Config config, Callable<PowerSource> powerSourceOpener) {
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
        // Without a filter an app file shares its all file's function, and so its power, computed once
        List<String> appPrefixes = config.appPrefixes();
        Function<Branch, String> name = Branch::name;
        namings.put(ResultWriter.ALL_METHODS_FILE, name);
        namings.put(ResultWriter.APP_METHODS_FILE,
                appPrefixes.isEmpty() ? name : branch -> branch.appName(appPrefixes));
        if (config.linesMonitoring()) {
            Function<Branch, String> lineName = Branch::lineName;
            namings.put(ResultWriter.ALL_LINES_FILE, lineName);
            namings.put(ResultWriter.APP_LINES_FILE,
                    appPrefixes.isEmpty() ? lineName : branch -> branch.appLineName(appPrefixes));
            namings.put(ResultWriter.APP_LINES_SELF_FILE, branch -> branch.selfLineName(appPrefixes));
        }
        this.resultsDir = ResultWriter.runDir(config.resultsDir());
        this.aggregate = config.aggregate();
        this.osBean = os;
        this.thread = new Thread(this::run, THREAD_NAME);
        this.thread.setDaemon(true);
        this.sampler = new ThreadSampler(threadBean, thread.threadId(), config.linesMonitoring(),
                config.maxStackDepth());
    }

    void start() {
        thread.start();
    }

    boolean isAlive() {
        return thread.isAlive();
    }

    /** Where this run's files go, once the power source is open. */
    Path resultsDir() {
        return resultsDir;
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

    /** CPU times at one instant. {@code machine} is null where /proc/stat cannot be read. */
    private record CpuSnapshot(long nanoTime, long processCpuNs, Map<Long, Long> threadCpuNs,
            ProcStat.Reading machine) {
    }

    private CpuSnapshot snapshot() {
        // The monitor's own thread is taken out of the JVM's CPU time, as it is out of the threads'
        return new CpuSnapshot(System.nanoTime(), osBean.getProcessCpuTime() - sampler.ownCpuTime(),
                sampler.cpuTimes(), machine());
    }

    private ProcStat.Reading machine() {
        if (!procStat) {
            return null;
        }
        try {
            return ProcStat.read();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Cannot read /proc/stat, using the JVM's own CPU load instead.", e);
            procStat = false;
            // The first getCpuLoad covers nothing
            osBean.getCpuLoad();
            return null;
        }
    }

    private void run() {
        // Nothing may escape: the monitor must never disturb the application
        try (PowerSource powerSource = powerSourceOpener.call()) {
            if (openResults()) {
                monitor(powerSource);
            }
        } catch (IOException e) {
            // The message says what to fix, a stack trace would only bury it
            logger.log(Level.SEVERE, "Cannot measure the CPU power: " + e.getMessage()
                    + " Monitoring is off, the application carries on.");
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "Monitoring stopped on an unexpected error, the application carries on.", t);
        } finally {
            if (results != null) {
                results.close();
            }
        }
    }

    /** Once the source is open, so a source that cannot be opened leaves no header-only files behind. */
    private boolean openResults() {
        try {
            results = new ResultWriter(resultsDir, namings.keySet(), aggregate);
            logger.log(Level.INFO, "Results are written to " + resultsDir.toAbsolutePath());
            return true;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Cannot write the results to " + resultsDir.toAbsolutePath()
                    + ". Monitoring is off, the application carries on.", e);
            return false;
        }
    }

    private void monitor(PowerSource powerSource) {
        if (!procStat) {
            // getCpuLoad covers the time since its previous call, by anyone in this JVM, so the first one covers nothing
            osBean.getCpuLoad();
        }

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
        if (!running && !powerSource.measuresSincePreviousCall()) {
            // Cut short by shutdown: the power of a fraction of a window is only known to a source that measures up to now
            return start;
        }
        CpuSnapshot end = snapshot();
        // Read right after the snapshot, so the power and the CPU times cover the same window
        double cpuPower = readPower(powerSource);
        boolean machine = start.machine() != null && end.machine() != null;
        // Over this window only, where getCpuLoad covers the time since anyone in the JVM last called it
        double systemLoad = machine ? start.machine().loadUntil(end.machine()) : osBean.getCpuLoad();
        int cpus = machine ? end.machine().cpus() : Runtime.getRuntime().availableProcessors();
        writeResults(samples, start, end, cpuPower, systemLoad, cpus);
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
            double systemLoad, int cpus) {
        long elapsedNs = end.nanoTime() - start.nanoTime();
        double intervalSeconds = elapsedNs / 1e9;
        warnIfOverrunning(samples, intervalSeconds);

        CpuUsage usage = PowerModel.cpuUsage(samples.lastCpuNs, start.threadCpuNs(), end.threadCpuNs());
        warnIfLowCoverage(usage.coverage());

        double processShare = PowerModel.processShare(end.processCpuNs() - start.processCpuNs(), elapsedNs, cpus,
                systemLoad);
        Map<Long, Double> threadPower = PowerModel.threadPower(usage, cpuPower * processShare);

        Map<Function<Branch, String>, Map<String, Double>> powerByNaming = new IdentityHashMap<>();
        Map<String, Map<String, Double>> powerByFile = new LinkedHashMap<>();
        namings.forEach((file, naming) -> powerByFile.put(file, powerByNaming.computeIfAbsent(naming,
                n -> PowerModel.branchPower(samples.branches, threadPower, n))));
        results.write(powerByFile, System.currentTimeMillis(), intervalSeconds, usage.coverage());
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
                    + " threads that were sampled, so the power reported per method is a lower bound. Virtual"
                    + " threads, which the JVM does not report here, and threads that end before a sample catches"
                    + " them are the usual causes; a sample rate too coarse for the number of threads is another.");
        }
        lowCoverageWarned = low;
    }
}
