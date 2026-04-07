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

import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.noureddine.joular.joularcodejava.agent.result.ResultWriter;
import org.noureddine.joular.joularcodejava.agent.source.PowerSource;
import org.noureddine.joular.joularcodejava.agent.utils.AgentProperties;
import com.sun.management.OperatingSystemMXBean;

/**
 * Handles the monitoring loop: sampling at high frequency (10ms)
 * and computing branch-level attribution at lower frequency (1s by default).
 */
public class MonitoringHandler implements Runnable {

    private static final Logger logger = Logger.getLogger(MonitoringHandler.class.getName());

    private volatile boolean running = true;
    private volatile Thread monitoringThread;
    private final PowerSource powerSource;
    private final ResultWriter resultWriter;
    private final long sampleRateMs;
    private final List<String> methodsFilteringPrefixes;
    private final long computationIntervalMs = 1000;
    private final long computationIntervalNs = computationIntervalMs * 1_000_000L;
    private final ThreadMXBean threadBean;
    private final OperatingSystemMXBean osBean;
    private final CountDownLatch startupLatch = new CountDownLatch(1);
    private volatile boolean startupSuccessful = false;
    private volatile Throwable startupFailure;

    public MonitoringHandler(AgentProperties properties, PowerSource powerSource, ThreadMXBean threadBean,
            OperatingSystemMXBean osBean) {
        this.powerSource = powerSource;
        this.resultWriter = new ResultWriter(properties.getResultsPath());
        this.sampleRateMs = Math.max(1L, properties.getSampleRateMs());
        this.methodsFilteringPrefixes = properties.getMethodsFilteringPrefixes();
        this.threadBean = threadBean;
        this.osBean = osBean;
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
        long monitoringThreadId = Thread.currentThread().getId(); // Is deprecated in Java 19, use threadId() instead

        try {
            try {
                powerSource.initialize();
                startupSuccessful = true;
            } catch (Exception e) {
                startupFailure = e;
                logger.log(Level.SEVERE, "Failed to initialize monitoring loop", e);
                return;
            } finally {
                startupLatch.countDown();
            }

            while (running) {
                try {
                    long windowStartNs = System.nanoTime();
                    Map<Long, Long> windowStartThreadCpuTime = snapshotThreadCpuTime(monitoringThreadId);

                    // 1. Sample stack traces over the computation interval
                    Map<Long, Map<String, Integer>> allStatsSamples = new HashMap<>();
                    Map<Long, Map<String, Integer>> appStatsSamples = new HashMap<>();
                    Map<Long, Integer> totalThreadSamples = new HashMap<>();

                    while ((System.nanoTime() - windowStartNs) < computationIntervalNs && running) {
                        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                            Thread t = entry.getKey();
                            long threadId = t.getId(); // Is deprecated in Java 19, use threadId() instead
                            if (threadId == monitoringThreadId) {
                                continue;
                            }
                            if (t.getState() == Thread.State.RUNNABLE) {
                                StackTraceElement[] stack = entry.getValue();

                                totalThreadSamples.merge(threadId, 1, Integer::sum);

                                // Group 1: All branches
                                String branchAll = getBranchString(stack, s -> true);
                                if (!branchAll.isEmpty()) {
                                    allStatsSamples.computeIfAbsent(threadId, k -> new HashMap<>()).merge(branchAll, 1,
                                            Integer::sum);
                                }

                                // Group 2: App branches
                                String branchApp = getBranchString(stack, this::matchesMethodFilter);
                                if (!branchApp.isEmpty()) {
                                    appStatsSamples.computeIfAbsent(threadId, k -> new HashMap<>()).merge(branchApp, 1,
                                            Integer::sum);
                                }
                            }
                        }
                        try {
                            Thread.sleep(sampleRateMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    long windowEndNs = System.nanoTime();
                    long nowMs = System.currentTimeMillis();
                    long measuredIntervalNs = windowEndNs - windowStartNs;
                    double measuredIntervalSeconds = measuredIntervalNs / 1_000_000_000.0;
                    if (measuredIntervalSeconds <= 0) {
                        continue;
                    }
                    // Use the effective attribution window duration for per-interval energy.
                    // Joular Core power at T is a trailing 1-second average, so this is an
                    // approximation when the loop duration differs from 1 second.
                    double energyIntervalSeconds = measuredIntervalSeconds;

                    // 2. Calculate thread CPU deltas strictly within the current attribution
                    // window.
                    Map<Long, Long> intervalThreadsCpuTime = new HashMap<>();
                    double totalDeltaCpuTime = 0;

                    // Use totalThreadSamples keys to identify active threads
                    for (long threadId : totalThreadSamples.keySet()) {
                        long currentCpuTime = threadBean.getThreadCpuTime(threadId);
                        if (currentCpuTime < 0) {
                            continue;
                        }

                        long startCpuTime = windowStartThreadCpuTime.getOrDefault(threadId, 0L);
                        long delta = Math.max(0, currentCpuTime - startCpuTime);

                        if (delta > 0) {
                            intervalThreadsCpuTime.put(threadId, delta);
                            totalDeltaCpuTime += delta;
                        }
                    }

                    // 3. Get power and scale to process power
                    double totalCpuPower = powerSource.getCurrentPower();
                    double processShare = estimateProcessShare();
                    double totalJHPower = 0.0;
                    if (totalCpuPower > 0 && processShare > 0) {
                        totalJHPower = totalCpuPower * processShare;
                    }
                    if (!Double.isFinite(totalJHPower) || totalJHPower < 0) {
                        totalJHPower = 0.0;
                    }

                    Map<Long, Double> threadPowerMap = new HashMap<>();
                    if (totalDeltaCpuTime > 0) {
                        for (Map.Entry<Long, Long> entry : intervalThreadsCpuTime.entrySet()) {
                            long threadId = entry.getKey();
                            long threadCpuTime = entry.getValue();

                            // Power for each thread based on JVM power consumption and thread CPU usage
                            double threadPower = totalJHPower * ((double) threadCpuTime / totalDeltaCpuTime);
                            threadPowerMap.put(threadId, threadPower);
                        }
                    }

                    // 4. Save results
                    attributeAndSave(allStatsSamples, totalThreadSamples, threadPowerMap, nowMs, energyIntervalSeconds,
                            "methods-power-all.csv");
                    attributeAndSave(appStatsSamples, totalThreadSamples, threadPowerMap, nowMs, energyIntervalSeconds,
                            "methods-power-app.csv");

                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Error during monitoring iteration, continuing.", e);
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in monitoring loop", e);
        } finally {
            startupLatch.countDown();
            powerSource.close();
        }
    }

    private double estimateProcessShare() {
        double processCpuLoad = sanitizeCpuLoad(osBean.getProcessCpuLoad());
        double systemCpuLoad = sanitizeCpuLoad(osBean.getSystemCpuLoad());
        if (processCpuLoad <= 0 || systemCpuLoad <= 0) {
            return 0.0;
        }
        return sanitizeCpuLoad(processCpuLoad / systemCpuLoad);
    }

    private boolean matchesMethodFilter(String methodName) {
        if (methodsFilteringPrefixes.isEmpty()) {
            return true;
        }
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

    private String getBranchString(StackTraceElement[] stackTrace, Predicate<String> filter) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int i = stackTrace.length - 1; i >= 0; i--) {
            StackTraceElement element = stackTrace[i];
            String methodName = element.getClassName() + "." + element.getMethodName();
            if (!filter.test(methodName)) {
                continue;
            }
            if (!first) {
                sb.append(";");
            }
            sb.append(methodName);
            first = false;
        }
        return sb.toString();
    }

    private Map<Long, Long> snapshotThreadCpuTime(long excludedThreadId) {
        Map<Long, Long> snapshot = new HashMap<>();
        for (long threadId : threadBean.getAllThreadIds()) {
            if (threadId == excludedThreadId) {
                continue;
            }
            long cpuTime = threadBean.getThreadCpuTime(threadId);
            if (cpuTime >= 0) {
                snapshot.put(threadId, cpuTime);
            }
        }
        return snapshot;
    }

    private void attributeAndSave(Map<Long, Map<String, Integer>> stats, Map<Long, Integer> totalThreadSamples,
            Map<Long, Double> threadPowerMap, long timestampMs, double intervalSeconds, String fileName) {
        Map<String, Double> methodPowerTotals = new HashMap<>();

        for (Map.Entry<Long, Map<String, Integer>> threadEntry : stats.entrySet()) {
            long threadId = threadEntry.getKey();
            Double threadPower = threadPowerMap.get(threadId);
            if (threadPower == null || threadPower <= 0)
                continue;

            Integer totalSamples = totalThreadSamples.get(threadId);
            if (totalSamples == null || totalSamples <= 0)
                continue;

            Map<String, Integer> methodCounts = threadEntry.getValue();

            for (var methodEntry : methodCounts.entrySet()) {
                double methodPower = (methodEntry.getValue().doubleValue() / totalSamples) * threadPower;
                methodPowerTotals.merge(methodEntry.getKey(), methodPower, Double::sum);
            }
        }

        if (!methodPowerTotals.isEmpty()) {
            resultWriter.writeRuntimeMethods(methodPowerTotals, timestampMs, intervalSeconds, fileName);
        }
    }
}
