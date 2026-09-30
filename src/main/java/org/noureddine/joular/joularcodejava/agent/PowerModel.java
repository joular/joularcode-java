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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Splits the CPU power drawn over a window between the call branches seen on the JVM's threads.
 *
 * <pre>
 *   processPower   = cpuPower x processShare
 *   threadPower(t) = processPower x cpuTime(t) / totalCpuTime
 *   branchPower(b) = threadPower(t) x samples(t, b) / samples(t)
 * </pre>
 *
 * <p>Threads are weighted by CPU time rather than by sample count, so a thread blocked in native I/O (which Java
 * still reports as {@code RUNNABLE}) is not charged for waiting.
 *
 * <p>{@code totalCpuTime} includes threads that were never sampled. Their share is left unattributed rather than
 * spread over the threads that were seen; {@link CpuUsage#coverage()} says how much that is.
 *
 * <p>The JVM's native threads (GC, JIT) are in {@code processPower} but in no Java thread's CPU time, so their
 * share is spread over the Java threads in proportion to theirs.
 *
 * <p>Blind spots: threads that end inside a window (their CPU time is no longer reported), virtual threads
 * (not in thread dumps), and whether a sampled thread was actually on a CPU at that instant.
 */
final class PowerModel {

    private PowerModel() {
    }

    /**
     * The JVM's share of the machine's CPU power: its own CPU load over the machine's.
     *
     * @param processCpuNs CPU time the whole JVM used over the window
     * @param elapsedNs    the window's length
     * @param cpus         the processors available to the JVM
     * @param systemLoad   the machine's CPU load over the window, from 0 to 1; 0, negative or NaN when unknown
     * @return a share from 0 to 1
     */
    static double processShare(long processCpuNs, long elapsedNs, int cpus, double systemLoad) {
        if (processCpuNs <= 0 || elapsedNs <= 0 || cpus <= 0) {
            return 0.0;
        }
        double processLoad = Math.min(1.0, (double) processCpuNs / ((double) elapsedNs * cpus));
        if (!(systemLoad > 0)) {
            // Take the machine as fully loaded: this under-states the JVM's share, never over-states it
            return processLoad;
        }
        return Math.min(1.0, processLoad / Math.min(1.0, systemLoad));
    }

    /**
     * CPU time used over a window.
     *
     * @param sampledThreadsNs CPU time of each thread that was sampled at least once
     * @param totalNs          CPU time of all threads, sampled or not
     * @param coverage         the sampled share of {@code totalNs}, from 0 to 1
     */
    record CpuUsage(Map<Long, Long> sampledThreadsNs, long totalNs, double coverage) {
    }

    /** Compares two CPU-time snapshots, keyed by thread id. */
    static CpuUsage cpuUsage(Set<Long> sampledThreads, Map<Long, Long> start, Map<Long, Long> end) {
        Map<Long, Long> sampledThreadsNs = new HashMap<>();
        long totalNs = 0;
        long sampledNs = 0;

        for (Map.Entry<Long, Long> entry : end.entrySet()) {
            long threadId = entry.getKey();
            // A thread missing from the start snapshot began inside the window (thread ids are never reused)
            long usedNs = entry.getValue() - start.getOrDefault(threadId, 0L);
            if (usedNs <= 0) {
                continue;
            }
            totalNs += usedNs;
            if (sampledThreads.contains(threadId)) {
                sampledNs += usedNs;
                sampledThreadsNs.put(threadId, usedNs);
            }
        }

        double coverage = totalNs > 0 ? (double) sampledNs / totalNs : 0.0;
        return new CpuUsage(sampledThreadsNs, totalNs, coverage);
    }

    /** Each sampled thread's share of the process power, in watts. */
    static Map<Long, Double> threadPower(CpuUsage usage, double processPower) {
        Map<Long, Double> threadPower = new HashMap<>();
        if (processPower <= 0 || usage.totalNs() <= 0) {
            return threadPower;
        }
        usage.sampledThreadsNs().forEach((threadId, usedNs) ->
                threadPower.put(threadId, processPower * usedNs / usage.totalNs()));
        return threadPower;
    }

    /**
     * Spreads each thread's power over the branches seen on it, in proportion to how often each was seen.
     *
     * @param samples     per thread, how many times each branch was seen
     * @param threadPower per thread, its power in watts
     * @param naming      the name a branch is reported under; an empty name leaves its share unattributed
     * @return the power of each branch name, summed over threads
     */
    static Map<String, Double> branchPower(Map<Long, Map<Branch, Integer>> samples, Map<Long, Double> threadPower,
            Function<Branch, String> naming) {
        Map<String, Double> branchPower = new HashMap<>();
        samples.forEach((threadId, branches) -> {
            Double power = threadPower.get(threadId);
            if (power == null) {
                return;
            }
            int threadSamples = branches.values().stream().mapToInt(Integer::intValue).sum();
            branches.forEach((branch, count) -> {
                String name = naming.apply(branch);
                if (!name.isEmpty()) {
                    branchPower.merge(name, power * count / threadSamples, Double::sum);
                }
            });
        });
        return branchPower;
    }
}
