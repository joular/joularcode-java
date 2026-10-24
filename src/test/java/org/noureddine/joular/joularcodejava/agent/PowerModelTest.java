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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.noureddine.joular.joularcodejava.agent.ThreadSampler.Samples;

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
    // totalCpuNs and coverage: which CPU time goes in the denominator, and how much of it was charged to a branch
    // -------------------------------------------------------------------------

    /** A thread that used CPU without being sampled still counts in the total, so the sampled ones are not overpaid. */
    @Test
    void totalCpuNs_unsampledThreadStaysInTheTotal() {
        Map<Long, Long> start = Map.of(1L, 0L, 2L, 0L);
        Map<Long, Long> end = Map.of(1L, 300L, 2L, 700L);
        Samples samples = new Samples(start);
        samples.charge(1L, branch("a.A.a"), 200L);
        samples.end(end);

        long totalNs = PowerModel.totalCpuNs(samples.lastCpuNs, start, end);

        assertEquals(1000L, totalNs, "both threads' CPU time counts");
        assertEquals(Map.of(branch("a.A.a"), 300L), samples.cpuNs, "only the sampled one is charged");
        assertEquals(0.3, PowerModel.coverage(samples.cpuNs, totalNs), 1e-9);
    }

    /** A thread missing from the start snapshot began inside the window, so all its CPU time was spent there. */
    @Test
    void totalCpuNs_threadStartedMidWindow_countsAllOfItsCpuTime() {
        Map<Long, Long> end = Map.of(7L, 500L);
        Samples samples = new Samples(Map.of());
        samples.charge(7L, branch("a.A.a"), 200L);
        samples.end(end);

        long totalNs = PowerModel.totalCpuNs(samples.lastCpuNs, Map.of(), end);

        assertEquals(500L, totalNs);
        assertEquals(1.0, PowerModel.coverage(samples.cpuNs, totalNs), 1e-9);
    }

    /**
     * A sampled thread gone from the end snapshot ended inside the window.
     * It keeps what it used up to its last sample, instead of handing it to the threads still alive.
     */
    @Test
    void totalCpuNs_sampledThreadEndedMidWindow_keepsItsLastSeenCpuTime() {
        Map<Long, Long> start = Map.of(1L, 100L, 2L, 100L);
        Map<Long, Long> end = Map.of(2L, 600L);
        Samples samples = new Samples(start);
        samples.charge(1L, branch("a.A.a"), 400L);
        samples.charge(2L, branch("b.B.b"), 300L);
        samples.end(end);

        long totalNs = PowerModel.totalCpuNs(samples.lastCpuNs, start, end);

        assertEquals(Map.of(branch("a.A.a"), 300L, branch("b.B.b"), 500L), samples.cpuNs, "the live one runs to the end");
        assertEquals(800L, totalNs);
        assertEquals(1.0, PowerModel.coverage(samples.cpuNs, totalNs), 1e-9);
    }

    @Test
    void totalCpuNs_idleThreadIgnored() {
        Map<Long, Long> start = Map.of(1L, 100L, 2L, 900L);
        Map<Long, Long> end = Map.of(1L, 600L, 2L, 900L);
        Samples samples = new Samples(start);
        samples.charge(1L, branch("a.A.a"), 600L);
        samples.end(end);

        long totalNs = PowerModel.totalCpuNs(samples.lastCpuNs, start, end);

        assertEquals(500L, totalNs);
        assertEquals(1.0, PowerModel.coverage(samples.cpuNs, totalNs), 1e-9, "the idle thread is not missing coverage");
    }

    @Test
    void coverage_noCpuUsed_isZero() {
        assertEquals(0L, PowerModel.totalCpuNs(Map.of(1L, 100L), Map.of(1L, 100L), Map.of(1L, 100L)));
        assertEquals(0.0, PowerModel.coverage(Map.of(), 0L), 1e-9);
    }

    // -------------------------------------------------------------------------
    // branchPower
    // -------------------------------------------------------------------------

    @Test
    void branchPower_splitsByCpuTime() {
        Map<String, Double> power = PowerModel.branchPower(
                Map.of(branch("a.A.a"), 25L, branch("b.B.b"), 75L), 100L, 40.0, Branch::name);

        assertEquals(Map.of("a.A.a", 10.0, "b.B.b", 30.0), power);
    }

    /**
     * What is handed out is less than the JVM drew, by exactly the share never observed. Normalising over the
     * charged CPU time instead would balance the totals by inflating the methods that happened to be seen.
     */
    @Test
    void branchPower_unobservedShareIsLeftUnattributed() {
        Map<Branch, Long> cpuNs = Map.of(branch("a.A.a"), 250L);

        Map<String, Double> power = PowerModel.branchPower(cpuNs, 1000L, 80.0, Branch::name);

        assertEquals(20.0, power.get("a.A.a"), 1e-9);
        assertEquals(PowerModel.coverage(cpuNs, 1000L) * 80.0, power.get("a.A.a"), 1e-9);
    }

    /** CPU time charged to branches with no app frame still counts in the total, so it is not given to the app branches. */
    @Test
    void branchPower_unmatchedBranchesStayInTheTotal() {
        List<String> prefixes = List.of("com.app");
        Map<String, Double> power = PowerModel.branchPower(
                Map.of(branch("com.app.Main.run"), 1L, branch("java.lang.Thread.run"), 3L), 4L, 8.0,
                branch -> branch.appName(prefixes));

        assertEquals(Map.of("com.app.Main.run", 2.0), power);
    }

    /** Telling lines apart splits the CPU time, but branchPower merges it back by name, so the methods files stay the same. */
    @Test
    void branchPower_linesMonitored_methodPowerUnchanged() {
        StackTraceElement main = new StackTraceElement("a.Main", "main", "Main.java", 5);
        List<StackTraceElement[]> stacks = List.of(
                new StackTraceElement[] {new StackTraceElement("a.Worker", "compute", "Worker.java", 10), main},
                new StackTraceElement[] {new StackTraceElement("a.Worker", "compute", "Worker.java", 10), main},
                new StackTraceElement[] {new StackTraceElement("a.Worker", "compute", "Worker.java", 20), main},
                new StackTraceElement[] {new StackTraceElement("a.Worker", "save", "Worker.java", 30), main});
        Map<Branch, Long> byMethod = new HashMap<>();
        Map<Branch, Long> byLine = new HashMap<>();
        for (StackTraceElement[] stack : stacks) {
            byMethod.merge(Branch.of(stack, false), 1L, Long::sum);
            byLine.merge(Branch.of(stack, true), 1L, Long::sum);
        }
        List<String> prefixes = List.of("a.Worker");

        assertEquals(PowerModel.branchPower(byMethod, 4L, 8.0, Branch::name),
                PowerModel.branchPower(byLine, 4L, 8.0, Branch::name));
        assertEquals(PowerModel.branchPower(byMethod, 4L, 8.0, branch -> branch.appName(prefixes)),
                PowerModel.branchPower(byLine, 4L, 8.0, branch -> branch.appName(prefixes)));
        assertEquals(Map.of("a.Main.main(Main.java:5);a.Worker.compute(Worker.java:10)", 4.0,
                        "a.Main.main(Main.java:5);a.Worker.compute(Worker.java:20)", 2.0,
                        "a.Main.main(Main.java:5);a.Worker.save(Worker.java:30)", 2.0),
                PowerModel.branchPower(byLine, 4L, 8.0, Branch::lineName));
    }

    @Test
    void branchPower_noPowerOrNoCpu_isEmpty() {
        assertTrue(PowerModel.branchPower(Map.of(branch("a.A.a"), 10L), 0L, 80.0, Branch::name).isEmpty());
        assertTrue(PowerModel.branchPower(Map.of(branch("a.A.a"), 10L), 10L, 0.0, Branch::name).isEmpty());
    }
}
