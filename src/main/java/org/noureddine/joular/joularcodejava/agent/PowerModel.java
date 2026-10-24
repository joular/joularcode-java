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
import java.util.function.Function;

/**
 * Splits the CPU power drawn over a window between the call branches seen on the JVM's threads.
 *
 * <pre>
 *   processPower   = cpuPower x processShare
 *   branchPower(b) = processPower x cpuTime(b) / totalCpuTime
 * </pre>
 *
 * <p>{@code cpuTime(b)} is the CPU time threads used on branch {@code b}: at each sample, what a running thread used
 * since its previous sample goes to the branch it is caught on, and what it used after its last sample goes to the
 * branch that sample caught. A thread blocked in native I/O, which Java still reports as {@code RUNNABLE}, uses no
 * CPU, so the branch it waits on is not charged for waiting.
 *
 * <p>{@code totalCpuTime} includes threads that were never sampled. Their share is left unattributed rather than
 * spread over the threads that were seen; {@link #coverage} says how much that is.
 *
 * <p>The JVM's native threads (GC, JIT) are in {@code processPower} but in no Java thread's CPU time, so their
 * share is spread over the Java threads in proportion to theirs.
 *
 * <p>A sampled thread that ends inside a window is charged up to the sample that last saw it, since the JVM stops
 * reporting its CPU time once it has ended. Blind spots: threads that end without ever being sampled, and virtual
 * threads (not in thread dumps).
 */
final class PowerModel {

    private PowerModel() {
    }

    /**
     * The JVM's share of the machine's CPU power: its own CPU load over the machine's.
     *
     * @param processCpuNs CPU time the whole JVM used over the window
     * @param elapsedNs    the window's length
     * @param cpus         the processors {@code systemLoad} is over: the machine's, which in a container are the
     *                     host's, not the few the cgroup lets the JVM see
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
     * The CPU time all of the JVM's threads used over a window, sampled or not, from snapshots keyed by thread id.
     *
     * @param lastCpuNs per thread, its CPU time at its last sample, else at the start: where one that is gone from
     *                  {@code end}, having ended inside the window, stops
     */
    static long totalCpuNs(Map<Long, Long> lastCpuNs, Map<Long, Long> start, Map<Long, Long> end) {
        // The live threads are read at the end; one missing there keeps its last reading
        Map<Long, Long> endNs = new HashMap<>(lastCpuNs);
        endNs.putAll(end);

        long totalNs = 0;
        for (Map.Entry<Long, Long> entry : endNs.entrySet()) {
            // A thread missing from the start snapshot began inside the window (thread ids are never reused)
            totalNs += Math.max(0, entry.getValue() - start.getOrDefault(entry.getKey(), 0L));
        }
        return totalNs;
    }

    /** The share of {@code totalNs} charged to a branch, from 0 to 1. */
    static double coverage(Map<Branch, Long> cpuNs, long totalNs) {
        long chargedNs = cpuNs.values().stream().mapToLong(Long::longValue).sum();
        return totalNs > 0 ? (double) chargedNs / totalNs : 0.0;
    }

    /**
     * Splits the process power between the branches, in proportion to the CPU time charged to each.
     *
     * @param cpuNs   the CPU time charged to each branch
     * @param totalNs the CPU time of all of the JVM's threads
     * @param naming  the name a branch is reported under; an empty name leaves its share unattributed
     * @return the power of each branch name, in watts
     */
    static Map<String, Double> branchPower(Map<Branch, Long> cpuNs, long totalNs, double processPower,
            Function<Branch, String> naming) {
        Map<String, Double> branchPower = new HashMap<>();
        if (processPower <= 0 || totalNs <= 0) {
            return branchPower;
        }
        cpuNs.forEach((branch, usedNs) -> {
            String name = naming.apply(branch);
            if (!name.isEmpty()) {
                branchPower.merge(name, processPower * usedNs / totalNs, Double::sum);
            }
        });
        return branchPower;
    }
}
