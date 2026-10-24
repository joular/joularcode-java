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

import com.sun.management.ThreadMXBean;
import java.lang.management.ThreadInfo;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the JVM's threads: which branch each running thread is on, and how much CPU time each has used.
 * The agent's own thread is left out of both.
 */
final class ThreadSampler {

    private final ThreadMXBean threadBean;
    private final long ownThreadId;
    private final boolean lines;
    private final int maxDepth;

    /** @param maxDepth how many frames of each stack are kept, from the newest: deeper stacks lose their oldest frames */
    ThreadSampler(ThreadMXBean threadBean, long ownThreadId, boolean lines, int maxDepth) {
        this.threadBean = threadBean;
        this.ownThreadId = ownThreadId;
        this.lines = lines;
        this.maxDepth = maxDepth;
    }

    /** What was seen during one window. */
    static final class Samples {
        /** Per thread, how many times it was caught on each branch. */
        final Map<Long, Map<Branch, Integer>> branches = new HashMap<>();
        /** Per sampled thread, its CPU time when it was last sampled, which is all that is left of one that ends. */
        final Map<Long, Long> lastCpuNs = new HashMap<>();
        int taken = 0;
        int missed = 0;
    }

    /** Records the branch each running thread is on, and its CPU time so far. */
    void sample(Samples samples) {
        samples.taken++;
        ThreadInfo[] infos = threadBean.dumpAllThreads(false, false, maxDepth);
        long[] ids = new long[infos.length];
        int running = 0;
        for (ThreadInfo info : infos) {
            if (info == null || info.getThreadId() == ownThreadId || info.getThreadState() != Thread.State.RUNNABLE) {
                continue;
            }
            StackTraceElement[] stack = info.getStackTrace();
            if (stack.length > 0) {
                samples.branches.computeIfAbsent(info.getThreadId(), id -> new HashMap<>())
                        .merge(Branch.of(stack, lines), 1, Integer::sum);
                ids[running++] = info.getThreadId();
            }
        }
        // Read now rather than at the window's end, which a thread may not live to see
        long[] times = threadBean.getThreadCpuTime(Arrays.copyOf(ids, running));
        for (int i = 0; i < running; i++) {
            if (times[i] >= 0) {
                samples.lastCpuNs.put(ids[i], times[i]);
            }
        }
    }

    /** The cumulative CPU time of every live thread, in nanoseconds. */
    Map<Long, Long> cpuTimes() {
        long[] ids = threadBean.getAllThreadIds();
        long[] times = threadBean.getThreadCpuTime(ids);
        Map<Long, Long> cpuTimes = new HashMap<>();
        for (int i = 0; i < ids.length; i++) {
            // -1 means the thread ended since the ids were read
            if (ids[i] != ownThreadId && times[i] >= 0) {
                cpuTimes.put(ids[i], times[i]);
            }
        }
        return cpuTimes;
    }

    /** The cumulative CPU time of the agent's own thread, in nanoseconds. */
    long ownCpuTime() {
        return threadBean.getThreadCpuTime(ownThreadId);
    }
}
