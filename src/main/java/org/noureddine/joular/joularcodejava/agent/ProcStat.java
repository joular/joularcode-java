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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The machine's CPU time as Linux counts it in {@code /proc/stat}: the whole machine, even from inside a container
 * whose cgroup limits the JVM, and over the agent's own windows, where the JVM's {@code getCpuLoad()} covers the time
 * since whoever in the JVM called it last.
 */
final class ProcStat {

    private static final Path FILE = Path.of("/proc/stat");

    /** The counters at one instant: the jiffies of every CPU, the idle ones among them, and how many CPUs there are. */
    record Reading(long totalJiffies, long idleJiffies, int cpus) {

        /** The machine's load from this reading to a later one, from 0 to 1; NaN when nothing elapsed. */
        double loadUntil(Reading later) {
            long elapsed = later.totalJiffies - totalJiffies;
            return elapsed > 0 ? 1.0 - (double) (later.idleJiffies - idleJiffies) / elapsed : Double.NaN;
        }
    }

    private ProcStat() {
    }

    /** Linux only, and not in every sandbox. */
    static boolean isAvailable() {
        return Files.isReadable(FILE);
    }

    static Reading read() throws IOException {
        return parse(Files.readAllLines(FILE));
    }

    /**
     * The {@code cpu} line holds user, nice, system, idle, iowait, irq, softirq and steal time, then guest times that
     * are already counted in user; each {@code cpuN} line after it is one CPU.
     * Steal is time the host gave to others, so in a virtual machine it is counted as idle, not as the guest's work.
     */
    static Reading parse(List<String> lines) throws IOException {
        long total = -1;
        long idle = 0;
        int cpus = 0;
        try {
            for (String line : lines) {
                if (!line.startsWith("cpu")) {
                    // The cpu lines come first
                    break;
                }
                String[] fields = line.trim().split("\\s+");
                if (!fields[0].equals("cpu")) {
                    cpus++;
                    continue;
                }
                total = 0;
                for (int i = 1; i < fields.length && i <= 8; i++) {
                    total += Long.parseLong(fields[i]);
                }
                idle = Long.parseLong(fields[4]) + Long.parseLong(fields[5])
                        + (fields.length > 8 ? Long.parseLong(fields[8]) : 0);
            }
        } catch (RuntimeException e) {
            throw new IOException("Unexpected cpu line in " + FILE + ".", e);
        }
        if (total < 0 || cpus == 0) {
            throw new IOException("No cpu lines in " + FILE + ".");
        }
        return new Reading(total, idle, cpus);
    }
}
