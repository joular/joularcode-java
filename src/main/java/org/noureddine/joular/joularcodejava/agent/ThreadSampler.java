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
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the JVM's threads: which branch each running thread is on, and how much CPU time each has used.
 * The agent's own thread is left out of both.
 */
final class ThreadSampler {

    private final ThreadMXBean threadBean;
    private final long ownThreadId;

    ThreadSampler(ThreadMXBean threadBean, long ownThreadId) {
        this.threadBean = threadBean;
        this.ownThreadId = ownThreadId;
    }

    /** What was seen during one window. */
    static final class Samples {
        /** Per thread, how many times it was caught on each branch. */
        final Map<Long, Map<Branch, Integer>> branches = new HashMap<>();
        int taken = 0;
        int missed = 0;
    }

    /** Records the branch each running thread is on. */
    void sample(Samples samples) {
        samples.taken++;
        for (ThreadInfo info : threadBean.dumpAllThreads(false, false)) {
            if (info == null || info.getThreadId() == ownThreadId || info.getThreadState() != Thread.State.RUNNABLE) {
                continue;
            }
            StackTraceElement[] stack = info.getStackTrace();
            if (stack.length > 0) {
                samples.branches.computeIfAbsent(info.getThreadId(), id -> new HashMap<>())
                        .merge(Branch.of(stack), 1, Integer::sum);
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
