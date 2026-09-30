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

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;
import org.noureddine.joular.joularcodejava.agent.ThreadSampler.Samples;

/** Sampling this very JVM, whose test thread is running while it is sampled. */
class ThreadSamplerTest {

    private final ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    @Test
    void sample_recordsTheRunningTestThread() {
        ThreadSampler sampler = new ThreadSampler(threadBean, -1);
        Samples samples = new Samples();

        sampler.sample(samples);

        assertEquals(1, samples.taken);
        assertTrue(samples.branches.containsKey(Thread.currentThread().threadId()));
        assertTrue(sampler.cpuTimes().containsKey(Thread.currentThread().threadId()));
    }

    /** The agent's own thread is never measured, so it is left out of both the samples and the CPU times. */
    @Test
    void ownThread_isLeftOut() {
        long self = Thread.currentThread().threadId();
        ThreadSampler sampler = new ThreadSampler(threadBean, self);
        Samples samples = new Samples();

        sampler.sample(samples);

        assertFalse(samples.branches.containsKey(self));
        assertFalse(sampler.cpuTimes().containsKey(self));
        assertTrue(sampler.ownCpuTime() >= 0, "its time is read apart");
    }
}
