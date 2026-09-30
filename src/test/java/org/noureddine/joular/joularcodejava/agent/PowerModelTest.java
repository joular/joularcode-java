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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.noureddine.joular.joularcodejava.agent.BranchTest.branch;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.noureddine.joular.joularcodejava.agent.PowerModel.CpuUsage;

class PowerModelTest {

    private static final long SECOND = 1_000_000_000L;

    // -------------------------------------------------------------------------
    // processShare: the JVM's part of the machine's CPU power
    // -------------------------------------------------------------------------

    /** Half of one core out of four is 12.5% of the machine; with the machine at 25%, the JVM is half of it. */
    @Test
    void processShare_isTheJvmsLoadOverTheMachinesLoad() {
        assertEquals(0.5, PowerModel.processShare(SECOND / 2, SECOND, 4, 0.25), 1e-9);
    }

    /** The two loads are not taken at the exact same instant, so the ratio can go past 1. */
    @Test
    void processShare_neverAboveOne() {
        assertEquals(1.0, PowerModel.processShare(SECOND, SECOND, 4, 0.1), 1e-9);
    }

    /** Without the machine's load, it is taken as fully loaded, which under-states the JVM's share. */
    @Test
    void processShare_unknownSystemLoad_fallsBackToTheJvmsLoad() {
        assertEquals(0.25, PowerModel.processShare(SECOND, SECOND, 4, -1.0), 1e-9);
        assertEquals(0.25, PowerModel.processShare(SECOND, SECOND, 4, Double.NaN), 1e-9);
        assertEquals(0.25, PowerModel.processShare(SECOND, SECOND, 4, 0.0), 1e-9);
    }

    @Test
    void processShare_nothingToDivide_isZero() {
        assertEquals(0.0, PowerModel.processShare(0, SECOND, 4, 0.5));
        assertEquals(0.0, PowerModel.processShare(SECOND, 0, 4, 0.5));
        assertEquals(0.0, PowerModel.processShare(SECOND, SECOND, 0, 0.5));
    }

    // -------------------------------------------------------------------------
    // cpuUsage: which CPU time goes in the denominator, and what coverage says
    // -------------------------------------------------------------------------

    /** A thread that used CPU without being sampled still counts in the total, so the sampled ones are not overpaid. */
    @Test
    void cpuUsage_unsampledThreadStaysInTheTotal() {
        CpuUsage usage = PowerModel.cpuUsage(Set.of(1L), Map.of(1L, 0L, 2L, 0L), Map.of(1L, 300L, 2L, 700L));

        assertEquals(1000L, usage.totalNs(), "both threads' CPU time counts");
        assertEquals(Map.of(1L, 300L), usage.sampledThreadsNs(), "only the sampled one is paid");
        assertEquals(0.3, usage.coverage(), 1e-9);
    }

    /** A thread missing from the start snapshot began inside the window, so all its CPU time was spent there. */
    @Test
    void cpuUsage_threadStartedMidWindow_countsAllOfItsCpuTime() {
        CpuUsage usage = PowerModel.cpuUsage(Set.of(7L), Map.of(), Map.of(7L, 500L));

        assertEquals(500L, usage.totalNs());
        assertEquals(1.0, usage.coverage(), 1e-9);
    }

    @Test
    void cpuUsage_idleThreadIgnored() {
        CpuUsage usage = PowerModel.cpuUsage(Set.of(1L), Map.of(1L, 100L, 2L, 900L), Map.of(1L, 600L, 2L, 900L));

        assertEquals(500L, usage.totalNs());
        assertEquals(1.0, usage.coverage(), 1e-9, "the idle thread is not missing coverage");
    }

    @Test
    void cpuUsage_noCpuUsed_coverageIsZero() {
        CpuUsage usage = PowerModel.cpuUsage(Set.of(1L), Map.of(1L, 100L), Map.of(1L, 100L));

        assertEquals(0L, usage.totalNs());
        assertEquals(0.0, usage.coverage(), 1e-9);
        assertTrue(usage.sampledThreadsNs().isEmpty());
    }

    // -------------------------------------------------------------------------
    // threadPower
    // -------------------------------------------------------------------------

    @Test
    void threadPower_splitsByCpuTime() {
        Map<Long, Double> power = PowerModel.threadPower(new CpuUsage(Map.of(1L, 25L, 2L, 75L), 100L, 1.0), 40.0);

        assertEquals(10.0, power.get(1L), 1e-9);
        assertEquals(30.0, power.get(2L), 1e-9);
    }

    /**
     * What is handed out is less than the JVM drew, by exactly the share never observed. Normalising over the
     * sampled threads instead would balance the totals by inflating the methods that happened to be seen.
     */
    @Test
    void threadPower_unobservedShareIsLeftUnattributed() {
        CpuUsage usage = PowerModel.cpuUsage(Set.of(1L), Map.of(1L, 0L, 2L, 0L), Map.of(1L, 250L, 2L, 750L));

        Map<Long, Double> power = PowerModel.threadPower(usage, 80.0);

        assertEquals(20.0, power.get(1L), 1e-9);
        assertEquals(usage.coverage() * 80.0, power.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
    }

    @Test
    void threadPower_noPowerOrNoCpu_isEmpty() {
        assertTrue(PowerModel.threadPower(new CpuUsage(Map.of(), 0L, 0.0), 80.0).isEmpty());
        assertTrue(PowerModel.threadPower(new CpuUsage(Map.of(1L, 10L), 10L, 1.0), 0.0).isEmpty());
    }

    // -------------------------------------------------------------------------
    // branchPower
    // -------------------------------------------------------------------------

    @Test
    void branchPower_splitsEachThreadBySampleCount() {
        Map<String, Double> power = PowerModel.branchPower(
                Map.of(1L, Map.of(branch("a.A.a"), 3, branch("b.B.b"), 1)), Map.of(1L, 8.0), Branch::name);

        assertEquals(6.0, power.get("a.A.a"), 1e-9);
        assertEquals(2.0, power.get("b.B.b"), 1e-9);
    }

    @Test
    void branchPower_sameBranchOnTwoThreads_isSummed() {
        Map<String, Double> power = PowerModel.branchPower(
                Map.of(1L, Map.of(branch("a.A.a"), 1), 2L, Map.of(branch("a.A.a"), 1)),
                Map.of(1L, 2.0, 2L, 3.0), Branch::name);

        assertEquals(5.0, power.get("a.A.a"), 1e-9);
    }

    /** Samples that matched no app frame still count for the thread, so their share is not given to the app branches. */
    @Test
    void branchPower_unmatchedSamplesStayInTheThreadTotal() {
        List<String> prefixes = List.of("com.app");
        Map<String, Double> power = PowerModel.branchPower(
                Map.of(1L, Map.of(branch("com.app.Main.run"), 1, branch("java.lang.Thread.run"), 3)),
                Map.of(1L, 8.0), branch -> branch.appName(prefixes));

        assertEquals(Map.of("com.app.Main.run", 2.0), power);
    }

    @Test
    void branchPower_threadWithoutPower_isSkipped() {
        assertTrue(PowerModel.branchPower(Map.of(1L, Map.of(branch("a.A.a"), 1)), Map.of(), Branch::name).isEmpty());
    }
}
