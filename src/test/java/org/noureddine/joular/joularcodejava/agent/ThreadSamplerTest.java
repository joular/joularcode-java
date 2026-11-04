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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.noureddine.joular.joularcodejava.agent.BranchTest.branch;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noureddine.joular.joularcodejava.agent.ThreadSampler.Samples;

/** Sampling this very JVM, whose test thread is running while it is sampled, and charging CPU time to branches. */
class ThreadSamplerTest {

    private final ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    @Test
    void sample_recordsTheRunningTestThread() {
        ThreadSampler sampler = new ThreadSampler(threadBean, -1, false, 64);
        Samples samples = new Samples(Map.of());

        sampler.sample(samples);

        assertEquals(1, samples.taken);
        assertTrue(samples.lastCpuNs.containsKey(Thread.currentThread().threadId()));
        assertFalse(samples.cpuNs.isEmpty(), "the test thread has used CPU since it began");
        assertTrue(sampler.cpuTimes().containsKey(Thread.currentThread().threadId()));
    }

    /** The agent's own thread is never measured, so it is left out of both the samples and the CPU times. */
    @Test
    void ownThread_isLeftOut() {
        long self = Thread.currentThread().threadId();
        ThreadSampler sampler = new ThreadSampler(threadBean, self, false, 64);
        Samples samples = new Samples(Map.of());

        sampler.sample(samples);

        assertFalse(samples.lastCpuNs.containsKey(self));
        assertFalse(sampler.cpuTimes().containsKey(self));
        assertTrue(sampler.ownCpuTime() >= 0, "its time is read apart");
    }

    /** Only a thread whose CPU time moved since the previous sample has its stack taken. */
    @Test
    void usedCpu_onlyWhenTheCpuTimeMovedSinceThePreviousSample() {
        Samples samples = new Samples(Map.of(1L, 100L));

        assertFalse(samples.usedCpu(1L, 100L));
        assertTrue(samples.usedCpu(1L, 150L));
        assertFalse(samples.usedCpu(1L, 150L), "it waited since the previous sample");
        assertTrue(samples.usedCpu(2L, 10L), "it began inside the window");
    }

    /** A thread blocked in native code is RUNNABLE, but uses no CPU, so the branch it waits on is charged nothing. */
    @Test
    void charge_branchCaughtWhileTheThreadUsedNoCpu_getsNothing() {
        Samples samples = new Samples(Map.of(1L, 100L));

        samples.charge(1L, branch("a.Worker.compute"), 600L);
        samples.charge(1L, branch("a.Worker.read"), 600L);
        samples.charge(1L, branch("a.Worker.read"), 600L);
        samples.end(Map.of(1L, 600L));

        assertEquals(Map.of(branch("a.Worker.compute"), 500L), samples.cpuNs);
    }

    /** What a thread uses after its last sample goes to the branch that sample caught. */
    @Test
    void end_cpuAfterTheLastSample_goesToItsBranch() {
        Samples samples = new Samples(Map.of(1L, 0L));

        samples.charge(1L, branch("a.A.a"), 100L);
        samples.charge(1L, branch("b.B.b"), 300L);
        samples.end(Map.of(1L, 1000L));

        assertEquals(Map.of(branch("a.A.a"), 100L, branch("b.B.b"), 900L), samples.cpuNs);
    }

    @Test
    void charge_sameBranchOnTwoThreads_isSummed() {
        Samples samples = new Samples(Map.of());

        samples.charge(1L, branch("a.A.a"), 100L);
        samples.charge(2L, branch("a.A.a"), 300L);
        samples.end(Map.of());

        assertEquals(Map.of(branch("a.A.a"), 400L), samples.cpuNs);
    }
}
