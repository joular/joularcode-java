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
        /** The CPU time charged to each branch, over all threads. */
        final Map<Branch, Long> cpuNs = new HashMap<>();
        /** Per thread, its CPU time at its last sample, else at the window's start: all that is left of one that ends. */
        final Map<Long, Long> lastCpuNs;
        /** Per sampled thread, the branch its last sample caught it on. */
        private final Map<Long, Branch> lastBranch = new HashMap<>();
        int taken = 0;
        int missed = 0;

        /** @param startCpuNs the CPU time of each thread as the window starts */
        Samples(Map<Long, Long> startCpuNs) {
            lastCpuNs = new HashMap<>(startCpuNs);
        }

        /**
         * Charges the CPU time a thread used since its last reading to the branch it is caught on now.
         * A thread blocked in native code is RUNNABLE too, but uses no CPU, so the branch it waits on gets nothing.
         */
        void charge(long threadId, Branch branch, long threadCpuNs) {
            // A thread missing from the start began inside the window, so all its CPU time was spent there
            add(branch, threadCpuNs - lastCpuNs.getOrDefault(threadId, 0L));
            lastCpuNs.put(threadId, threadCpuNs);
            lastBranch.put(threadId, branch);
        }

        /** Called once, as the window ends: what each sampled thread used after its last sample goes to that sample's branch. */
        void end(Map<Long, Long> endCpuNs) {
            for (Map.Entry<Long, Branch> last : lastBranch.entrySet()) {
                Long threadCpuNs = endCpuNs.get(last.getKey());
                // A thread gone by then ended inside the window, and stays charged up to its last sample
                if (threadCpuNs != null) {
                    add(last.getValue(), threadCpuNs - lastCpuNs.get(last.getKey()));
                }
            }
        }

        private void add(Branch branch, long usedNs) {
            if (usedNs > 0) {
                cpuNs.merge(branch, usedNs, Long::sum);
            }
        }
    }

    /** Charges each running thread's CPU time since its last reading to the branch it is on. */
    void sample(Samples samples) {
        samples.taken++;
        ThreadInfo[] infos = threadBean.dumpAllThreads(false, false, maxDepth);
        long[] ids = new long[infos.length];
        Branch[] branches = new Branch[infos.length];
        int running = 0;
        for (ThreadInfo info : infos) {
            if (info == null || info.getThreadId() == ownThreadId || info.getThreadState() != Thread.State.RUNNABLE) {
                continue;
            }
            StackTraceElement[] stack = info.getStackTrace();
            if (stack.length > 0) {
                ids[running] = info.getThreadId();
                branches[running++] = Branch.of(stack, lines);
            }
        }
        // Read now rather than at the window's end, which a thread may not live to see
        long[] times = threadBean.getThreadCpuTime(Arrays.copyOf(ids, running));
        for (int i = 0; i < running; i++) {
            // -1 means the thread ended since the dump
            if (times[i] >= 0) {
                samples.charge(ids[i], branches[i], times[i]);
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
