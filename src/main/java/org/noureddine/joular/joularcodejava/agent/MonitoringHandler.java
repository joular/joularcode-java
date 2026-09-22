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

import java.lang.management.ThreadInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.noureddine.joular.joularcodejava.agent.result.ResultWriter;
import org.noureddine.joular.joularcodejava.agent.source.PowerSource;
import org.noureddine.joular.joularcodejava.agent.utils.AgentProperties;
import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;

/**
 * Samples the stacks of the running threads and divides the power drawn over a window between the call branches that were seen on them.
 *
 * <h2>The model</h2>
 * <pre>
 *   threadPower(t) = processPower x cpuDelta(t) / totalCpuDelta
 *   branchPower(b) = threadPower(t) x samples(t, b) / totalSamples(t)
 * </pre>
 *
 * <p>Power is split between threads by the CPU time each one used, and within a thread between branches by how often each was seen on its stack.
 * Splitting between threads by CPU time rather than by sample count is what keeps a thread that was blocked in native I/O from being charged for the time it spent waiting: Java reports such a thread as {@code RUNNABLE} even though it is not on a processor, so it is sampled, but its CPU time barely moves.
 *
 * <p>The denominator is every thread that used CPU, not only the threads that were sampled.
 * Power drawn by a thread never caught in a sample is therefore left unattributed rather than shared out over the threads that were.
 *
 * <h2>What this cannot see</h2>
 * <ul>
 *   <li><b>Threads that die inside a window.</b> {@code getThreadCpuTime} returns -1 once a thread has gone, so a thread that finishes its work mid window is absent from the closing snapshot and drops out of both the numerator and the denominator.
 * Coverage therefore reads optimistically high in an application built on short lived threads.</li>
 *   <li><b>Virtual threads.</b> {@code dumpAllThreads} reports platform threads only.</li>
 *   <li><b>Where a thread is inside a window.</b> A sample says which branch a thread was on, not whether it was on a processor at that instant, so within one thread the samples taken while it was blocked still dilute the branches that were genuinely running.</li>
 * </ul>
 */
public class MonitoringHandler implements Runnable {

    private static final Logger logger = Logger.getLogger(MonitoringHandler.class.getName());

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long LOW_SAMPLE_RATE_WARNING_MS = 5L;

    /** Below this share of the JVM's CPU time being accounted for, the results are worth a warning. */
    private static final double LOW_COVERAGE_THRESHOLD = 0.5;

    /** Report a sampler that cannot keep up once it has missed this share of a window's samples. */
    private static final double HIGH_OVERRUN_THRESHOLD = 0.25;

    private volatile boolean running = true;
    private volatile Thread monitoringThread;
    private final PowerSource powerSource;
    private final ResultWriter resultWriter;
    private final long sampleRateMs;
    private final List<String> methodsFilteringPrefixes;
    private final ThreadMXBean threadBean;
    private final OperatingSystemMXBean osBean;
    private final CountDownLatch startupLatch = new CountDownLatch(1);
    private volatile boolean startupSuccessful = false;
    private volatile Throwable startupFailure;

    private boolean overrunWarningLogged = false;
    private boolean lowCoverageWarningLogged = false;

    public MonitoringHandler(AgentProperties properties, PowerSource powerSource, ThreadMXBean threadBean,
            OperatingSystemMXBean osBean) {
        this.powerSource = powerSource;
        this.resultWriter = new ResultWriter(properties.getResultsPath());
        this.sampleRateMs = Math.max(1L, properties.getSampleRateMs());
        this.methodsFilteringPrefixes = properties.getMethodsFilteringPrefixes();
        this.threadBean = threadBean;
        this.osBean = osBean;
        if (this.sampleRateMs < LOW_SAMPLE_RATE_WARNING_MS) {
            logger.log(Level.WARNING,
                    () -> "Configured stack-monitoring-sample-rate is " + this.sampleRateMs
                            + " ms. Values below " + LOW_SAMPLE_RATE_WARNING_MS
                            + " ms can noticeably increase monitoring overhead.");
        }
    }

    public void stop() {
        this.running = false;
        Thread t = this.monitoringThread;
        if (t != null) {
            t.interrupt();
        }
    }

    public void joinWithTimeout(long timeoutMs) {
        Thread t = this.monitoringThread;
        if (t != null) {
            try {
                t.join(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Waits up to {@code timeoutMs} for the startup attempt to settle (succeed or fail).
     *
     * <p>Returns {@code true} as soon as the latch is released — which happens both on
     * successful initialization <em>and</em> on a startup failure. To distinguish, the
     * caller must consult {@link #isStartupSuccessful()} / {@link #getStartupFailure()}.
     *
     * @return {@code true} if startup completed (success or failure) within the timeout,
     *         {@code false} if the timeout elapsed first (or the wait was interrupted).
     */
    public boolean awaitStartup(long timeoutMs) {
        try {
            return startupLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean isStartupSuccessful() {
        return startupSuccessful;
    }

    public Throwable getStartupFailure() {
        return startupFailure;
    }

    @Override
    public void run() {
        logger.log(Level.INFO, "Monitoring loop started.");
        this.monitoringThread = Thread.currentThread();
        long monitoringThreadId = Thread.currentThread().threadId();

        try {
            try {
                resultWriter.verifyWritable();
                powerSource.initialize();
                startupSuccessful = true;
            } catch (Exception e) {
                startupFailure = e;
                logger.log(Level.SEVERE, "Failed to initialize monitoring loop", e);
                return;
            } finally {
                // Counted down before the first window, so that the application's own main is not held up while the operating system bean warms itself.
                startupLatch.countDown();
            }

            while (running) {
                try {
                    runOneWindow(monitoringThreadId);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Error during monitoring iteration, continuing.", e);
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in monitoring loop", e);
        } finally {
            startupLatch.countDown();
            try {
                powerSource.close();
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "Error closing power source", e);
            }
            resultWriter.close();
        }
    }

    /** Samples for one monitoring window, then attributes that window's power and writes it out. */
    private void runOneWindow(long monitoringThreadId) {
        long windowStartNs = System.nanoTime();
        Map<Long, Long> windowStartCpuTime = snapshotThreadCpuTime(monitoringThreadId);

        SampleSet samples = new SampleSet();
        powerSource.beginWindow();

        // An absolute schedule: each sample is due at a fixed offset from the start of the window, so the time the stack dump takes is absorbed into the interval rather than added on top of it.
        // Sleeping for the sample rate after the dump instead, quietly turns a configured 10 ms into 10 ms plus the dump, halving the resolution on a JVM with many threads
        long sampleRateNs = sampleRateMs * NANOS_PER_MILLI;
        long nextSampleNs = windowStartNs;
        int missedSamples = 0;
        boolean windowRanItsCourse = false;

        while (running && !Thread.currentThread().isInterrupted()) {
            long now = System.nanoTime();
            if (powerSource.isWindowComplete(now - windowStartNs)) {
                windowRanItsCourse = true;
                break;
            }

            takeSample(samples, monitoringThreadId);

            nextSampleNs += sampleRateNs;
            long parkNs = nextSampleNs - System.nanoTime();
            if (parkNs > 0) {
                // parkNanos neither throws on interrupt nor clears the flag, so the loop condition above is what ends the window when stop() interrupts us
                LockSupport.parkNanos(parkNs);
            } else {
                // The dump alone already overran the interval, so there is nothing left to wait for
                missedSamples++;
                nextSampleNs = System.nanoTime();
            }
        }

        if (!windowRanItsCourse) {
            // The agent is shutting down and this window was cut off part way through.
            // Its handful of samples cover a fraction of a second of a JVM that is already winding down, so attributing them would put a row in the results that looks like a cycle and is not.
            return;
        }

        long windowEndNs = System.nanoTime();
        long nowMs = System.currentTimeMillis();
        double intervalSeconds = (double) (windowEndNs - windowStartNs) / NANOS_PER_SECOND;
        if (intervalSeconds <= 0) {
            return;
        }

        reportSampling(missedSamples, samples.samplesTaken, intervalSeconds);

        Map<Long, Long> windowEndCpuTime = snapshotThreadCpuTime(monitoringThreadId);
        Attribution attribution = attribute(samples.totalPerThread, windowStartCpuTime, windowEndCpuTime);
        reportCoverage(attribution.coverage());

        double processPower = processPower();
        Map<Long, Double> threadPower = calculateThreadPower(
                attribution.sampledCpuDeltas(), attribution.totalCpuDeltaNs(), processPower);

        attributeAndSave(samples.allBranches, samples.totalPerThread, threadPower,
                nowMs, intervalSeconds, attribution.coverage(), "methods-power-all.csv");
        attributeAndSave(samples.appBranches, samples.totalPerThread, threadPower,
                nowMs, intervalSeconds, attribution.coverage(), "methods-power-app.csv");
    }

    /** One pass over every live thread, recording the branch each runnable one is on */
    private void takeSample(SampleSet samples, long monitoringThreadId) {
        // dumpAllThreads avoids the Map<Thread, StackTraceElement[]> wrapping that Thread.getAllStackTraces() builds on top of the same native call
        ThreadInfo[] threadInfos = threadBean.dumpAllThreads(false, false);
        samples.samplesTaken++;

        for (ThreadInfo info : threadInfos) {
            if (info == null) {
                continue;
            }
            long threadId = info.getThreadId();
            if (threadId == monitoringThreadId || info.getThreadState() != Thread.State.RUNNABLE) {
                continue;
            }

            StackTraceElement[] stack = info.getStackTrace();
            if (stack.length == 0) {
                continue;
            }

            samples.totalPerThread.merge(threadId, 1, Integer::sum);

            if (!methodsFilteringPrefixes.isEmpty()) {
                StringBuilder app = new StringBuilder(estimatedBranchLength(stack));
                String all = buildBranch(stack, app);
                if (!all.isEmpty()) {
                    samples.recordAll(threadId, all);
                }
                if (app.length() > 0) {
                    samples.recordApp(threadId, app.toString());
                }
            } else {
                // With no filter the two groupings hold the same branches, so the stack is walked once and the one string is shared between them.
                String branch = buildBranch(stack);
                if (!branch.isEmpty()) {
                    samples.recordAll(threadId, branch);
                    samples.recordApp(threadId, branch);
                }
            }
        }
    }

    /**
     * Builds the branch, oldest frame first, as the viewer expects.
     *
     * <p>The class and the method go straight into the builder rather than through a joined {@code "class.method"} string.
     * Only the filter needs that string, and this is the path taken when there is no filter: one throwaway string per frame, per thread, per sample adds up to everal hundred thousand allocations a second on a JVM with many threads.
     */
    private static String buildBranch(StackTraceElement[] stack) {
        // Presized: the default 16 characters would grow to a couple of kilobytes through about eight array copies for a stack of any depth
        StringBuilder branch = new StringBuilder(estimatedBranchLength(stack));
        for (int i = stack.length - 1; i >= 0; i--) {
            StackTraceElement element = stack[i];
            appendSeparator(branch);
            branch.append(element.getClassName()).append('.').append(element.getMethodName());
        }
        return branch.toString();
    }

    /**
     * Builds the full branch and, in the same walk of the stack, the branch of just those frames passing the method filter.
     *
     * <p>The filter matches on the whole {@code "class.method"} name, so here that string has to be built. It is then reused for both branches rather than formed twice.
     */
    private String buildBranch(StackTraceElement[] stack, StringBuilder filtered) {
        StringBuilder all = new StringBuilder(estimatedBranchLength(stack));
        for (int i = stack.length - 1; i >= 0; i--) {
            StackTraceElement element = stack[i];
            String methodName = element.getClassName() + "." + element.getMethodName();
            append(all, methodName);
            if (matchesMethodFilter(methodName)) {
                append(filtered, methodName);
            }
        }
        return all.toString();
    }

    private static int estimatedBranchLength(StackTraceElement[] stack) {
        return Math.max(64, stack.length * 48);
    }

    private static void append(StringBuilder branch, String methodName) {
        appendSeparator(branch);
        branch.append(methodName);
    }

    private static void appendSeparator(StringBuilder branch) {
        if (branch.length() > 0) {
            branch.append(';');
        }
    }

    /** What was seen during one monitoring window. */
    private static final class SampleSet {
        final Map<Long, Map<String, Integer>> allBranches = new HashMap<>();
        final Map<Long, Map<String, Integer>> appBranches = new HashMap<>();
        final Map<Long, Integer> totalPerThread = new HashMap<>();
        int samplesTaken = 0;

        void recordAll(long threadId, String branch) {
            record(allBranches, threadId, branch);
        }

        void recordApp(long threadId, String branch) {
            record(appBranches, threadId, branch);
        }

        private static void record(Map<Long, Map<String, Integer>> branches, long threadId, String branch) {
            branches.computeIfAbsent(threadId, k -> new HashMap<>()).merge(branch, 1, Integer::sum);
        }
    }

    /** How a monitoring window's CPU time divided between the threads that were sampled and the rest */
    record Attribution(Map<Long, Long> sampledCpuDeltas, long totalCpuDeltaNs, double coverage) {
    }

    /**
     * Works out how much CPU each thread used over the monitoring window, and how much of that belonged to threads that were actually sampled.
     */
    static Attribution attribute(Map<Long, Integer> sampledThreads, Map<Long, Long> startCpuTime,
            Map<Long, Long> endCpuTime) {
        Map<Long, Long> sampledDeltas = new HashMap<>();
        long totalDeltaNs = 0;
        long sampledDeltaNs = 0;

        for (Map.Entry<Long, Long> entry : endCpuTime.entrySet()) {
            long threadId = entry.getKey();
            // A thread missing from the opening snapshot was started after the window began, so every nanosecond on its counter was spent inside the window and all of it counts.
            // Safe because a thread id is never reused.
            long delta = entry.getValue() - startCpuTime.getOrDefault(threadId, 0L);
            if (delta <= 0) {
                continue;
            }
            totalDeltaNs += delta;
            if (sampledThreads.containsKey(threadId)) {
                sampledDeltaNs += delta;
                sampledDeltas.put(threadId, delta);
            }
        }

        double coverage = totalDeltaNs > 0 ? (double) sampledDeltaNs / totalDeltaNs : 0.0;
        return new Attribution(sampledDeltas, totalDeltaNs, coverage);
    }

    private void reportSampling(int missedSamples, int samplesTaken, double intervalSeconds) {
        logger.log(Level.FINE, () -> "Window: " + samplesTaken + " samples over "
                + String.format(Locale.ROOT, "%.3f", intervalSeconds) + " s, "
                + missedSamples + " overran the interval");

        if (samplesTaken <= 0) {
            return;
        }

        if ((double) missedSamples / samplesTaken >= HIGH_OVERRUN_THRESHOLD) {
            if (!overrunWarningLogged) {
                long achievedMs = Math.round(intervalSeconds * 1000.0 / samplesTaken);
                logger.log(Level.WARNING,
                        () -> "The stack sampler cannot keep up with stack-monitoring-sample-rate="
                                + sampleRateMs + " ms: " + samplesTaken + " samples were taken in the last"
                                + " window, an effective rate of about " + achievedMs + " ms. Dumping the"
                                + " stacks of this many threads costs more than the interval allows. Raise"
                                + " the sample rate for an honest one, or accept the coarser data.");
                overrunWarningLogged = true;
            }
            return;
        }
        // Back within budget, so a later spell of overrunning is worth reporting again
        overrunWarningLogged = false;
    }

    private void reportCoverage(double coverage) {
        logger.log(Level.FINE, () -> "Window coverage: " + coverage);
        if (coverage <= 0) {
            return;
        }

        if (coverage < LOW_COVERAGE_THRESHOLD) {
            if (!lowCoverageWarningLogged) {
                logger.log(Level.WARNING,
                        () -> "Only " + Math.round(coverage * 100) + "% of this JVM's CPU time belonged to"
                                + " threads that were sampled, so the power reported per method is a lower bound. Threads that are short lived, or virtual threads, which the JVM does not report here, are the usual causes. Another cause is a sample rate too coarse for the number of threads.");
                lowCoverageWarningLogged = true;
            }
            return;
        }
        // Coverage recovered, so a later drop is worth reporting again
        lowCoverageWarningLogged = false;
    }

    /** The power this JVM is drawing: the machine's CPU power times this JVM's share of the work. */
    private double processPower() {
        double totalCpuPower = powerSource.getCurrentPower();
        double processShare = estimateProcessShare();
        double power = totalCpuPower > 0 && processShare > 0 ? totalCpuPower * processShare : 0.0;
        return Double.isFinite(power) && power > 0 ? power : 0.0;
    }

    /** The share of the machine's busy CPU time that belongs to this JVM */
    private double estimateProcessShare() {
        // The bean's first reading is negative, which sanitizeCpuLoad turns into zero, so the first window attributes nothing and warms the bean for the ones after it
        double processCpuLoad = sanitizeCpuLoad(osBean.getProcessCpuLoad());
        double systemCpuLoad = sanitizeCpuLoad(osBean.getCpuLoad());
        if (processCpuLoad <= 0) {
            return 0.0;
        }
        if (systemCpuLoad <= 0) {
            // The system CPU load could not be read while the JVM's own CPU load could.
            // Falling back to the JVM's load in order to keep the cycle rather than discarding it.
            // This means less accurate data, as it amounts to taking the machine as fully loaded, which under-states the share of the power the JVM is given rather than over-stating it.
            return processCpuLoad;
        }
        return sanitizeCpuLoad(processCpuLoad / systemCpuLoad);
    }

    private boolean matchesMethodFilter(String methodName) {
        for (String prefix : methodsFilteringPrefixes) {
            if (methodName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private double sanitizeCpuLoad(double value) {
        if (!Double.isFinite(value) || value <= 0) {
            return 0;
        }
        return Math.min(1.0, value);
    }

    /**
     * Every live thread's cumulative CPU time, the agent's own thread aside since its time is overhead rather than application work.
     */
    private Map<Long, Long> snapshotThreadCpuTime(long excludedThreadId) {
        long[] ids = threadBean.getAllThreadIds();
        // One call for every thread, rather than one native transition per thread
        long[] times = threadBean.getThreadCpuTime(ids);

        Map<Long, Long> snapshot = new HashMap<>(Math.max(16, ids.length * 2));
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] != excludedThreadId && times[i] >= 0) {
                snapshot.put(ids[i], times[i]);
            }
        }
        return snapshot;
    }

    /**
     * Divides the JVM's power between the sampled threads.
     *
     * <p>{@code totalDeltaCpuTime} covers every thread that used CPU, including those never caught in a sample, so their share is simply not handed out rather than being spread over the threads that were seen.
     */
    static Map<Long, Double> calculateThreadPower(Map<Long, Long> sampledCpuDeltas, double totalDeltaCpuTime,
            double totalJHPower) {
        Map<Long, Double> threadPowerMap = new HashMap<>();
        if (totalJHPower <= 0 || !Double.isFinite(totalJHPower) || totalDeltaCpuTime <= 0) {
            return threadPowerMap;
        }

        for (Map.Entry<Long, Long> entry : sampledCpuDeltas.entrySet()) {
            long threadCpuTime = entry.getValue();
            if (threadCpuTime > 0) {
                threadPowerMap.put(entry.getKey(), totalJHPower * (threadCpuTime / totalDeltaCpuTime));
            }
        }
        return threadPowerMap;
    }

    private void attributeAndSave(Map<Long, Map<String, Integer>> stats, Map<Long, Integer> totalThreadSamples,
            Map<Long, Double> threadPowerMap, long timestampMs, double intervalSeconds, double coverage,
            String fileName) {
        Map<String, Double> methodPowerTotals = new HashMap<>();

        for (Map.Entry<Long, Map<String, Integer>> threadEntry : stats.entrySet()) {
            long threadId = threadEntry.getKey();
            Double threadPower = threadPowerMap.get(threadId);
            if (threadPower == null || threadPower <= 0)
                continue;

            Integer totalSamples = totalThreadSamples.get(threadId);
            if (totalSamples == null || totalSamples <= 0)
                continue;

            for (var methodEntry : threadEntry.getValue().entrySet()) {
                double methodPower = (methodEntry.getValue().doubleValue() / totalSamples) * threadPower;
                methodPowerTotals.merge(methodEntry.getKey(), methodPower, Double::sum);
            }
        }

        if (!methodPowerTotals.isEmpty()) {
            resultWriter.writeRuntimeMethods(methodPowerTotals, timestampMs, intervalSeconds, coverage, fileName);
        }
    }
}
