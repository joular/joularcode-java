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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcStatTest {

    @Test
    void parse_totalsTheCpuLineAndCountsTheCpus() throws IOException {
        // user nice system idle iowait irq softirq steal guest guest_nice
        ProcStat.Reading reading = ProcStat.parse(List.of(
                "cpu  100 10 50 800 20 5 5 10 40 0",
                "cpu0 50 5 25 400 10 3 2 5 20 0",
                "cpu1 50 5 25 400 10 2 3 5 20 0",
                "intr 12345 0 0"));

        assertEquals(1000, reading.totalJiffies(), "the guest times are already in user");
        assertEquals(830, reading.idleJiffies(), "idle, iowait and steal");
        assertEquals(2, reading.cpus());
    }

    /** In a virtual machine, the time the host gave to others is not the guest's work. */
    @Test
    void loadUntil_leavesStealOut() throws IOException {
        ProcStat.Reading start = ProcStat.parse(List.of("cpu  0 0 0 0 0 0 0 0", "cpu0 0 0 0 0 0 0 0 0"));
        ProcStat.Reading end = ProcStat.parse(List.of("cpu  50 0 0 20 0 0 0 30", "cpu0 50 0 0 20 0 0 0 30"));

        assertEquals(0.5, start.loadUntil(end), 1e-9);
    }

    /** Kernels before 2.6.11 have no steal column. */
    @Test
    void parse_withoutSteal() throws IOException {
        ProcStat.Reading reading = ProcStat.parse(List.of("cpu  100 0 50 800 50 0 0", "cpu0 100 0 50 800 50 0 0"));

        assertEquals(1000, reading.totalJiffies());
        assertEquals(850, reading.idleJiffies());
    }

    @Test
    void parse_noCpuLines_throws() {
        assertThrows(IOException.class, () -> ProcStat.parse(List.of("intr 1 2 3")));
        assertThrows(IOException.class, () -> ProcStat.parse(List.of("cpu  1 2 x 4 5")));
    }
}
