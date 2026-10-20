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

import java.util.List;

/**
 * A call branch: the stack of a thread, as the thread dump gives it (newest frame first).
 *
 * <p>Two stacks are the same branch when they go through the same methods, whatever the line numbers, unless lines are monitored: then they must also be on the same lines.
 * Comparing frames is cheap next to building text, so the stacks are counted as they are, on every sample, and only written out as text once per window.
 */
final class Branch {

    private final StackTraceElement[] frames;
    private final boolean lines;
    private final int hash;

    private Branch(StackTraceElement[] frames, boolean lines, int hash) {
        this.frames = frames;
        this.lines = lines;
        this.hash = hash;
    }

    static Branch of(StackTraceElement[] frames, boolean lines) {
        int hash = 1;
        for (StackTraceElement frame : frames) {
            hash = 31 * (31 * hash + frame.getClassName().hashCode()) + frame.getMethodName().hashCode();
            if (lines) {
                hash = 31 * hash + frame.getLineNumber();
            }
        }
        return new Branch(frames, lines, hash);
    }

    /** Every frame, oldest first, as {@code a.Main.main;a.Worker.compute}. */
    String name() {
        return join(List.of(), false);
    }

    /** Only the frames that start with one of the prefixes, oldest first; empty when none does. */
    String appName(List<String> prefixes) {
        return join(prefixes, false);
    }

    /**
     * Every branch, oldest first, with its line as a stack trace writes it:
     * {@code a.Main.main(Main.java:5);a.Worker.compute(Worker.java:12)}.
     */
    String lineName() {
        return join(List.of(), true);
    }

    /** As {@link #appName(List)}, with each frame's line. */
    String appLineName(List<String> prefixes) {
        return join(prefixes, true);
    }

    private String join(List<String> prefixes, boolean withLines) {
        StringBuilder name = new StringBuilder();
        for (int i = frames.length - 1; i >= 0; i--) {
            StackTraceElement frame = frames[i];
            if (!prefixes.isEmpty() && !matchesAny(frame, prefixes)) {
                continue;
            }
            if (!name.isEmpty()) {
                name.append(';');
            }
            if (withLines) {
                // Without its module and class loader, the frame writes itself as a stack trace does
                name.append(new StackTraceElement(frame.getClassName(), frame.getMethodName(), frame.getFileName(),
                        frame.getLineNumber()));
            } else {
                name.append(frame.getClassName()).append('.').append(frame.getMethodName());
            }
        }
        return name.toString();
    }

    private static boolean matchesAny(StackTraceElement frame, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (startsWith(frame.getClassName(), frame.getMethodName(), prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code className + "." + methodName} starts with {@code prefix}, without building that string.
     * The prefix is plain text, not a package boundary: {@code com.example} also matches {@code com.examples}.
     */
    static boolean startsWith(String className, String methodName, String prefix) {
        int classLength = className.length();
        if (prefix.length() <= classLength) {
            return className.startsWith(prefix);
        }
        return prefix.startsWith(className)
                && prefix.charAt(classLength) == '.'
                && methodName.regionMatches(0, prefix, classLength + 1, prefix.length() - classLength - 1);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Branch that) || hash != that.hash || lines != that.lines
                || frames.length != that.frames.length) {
            return false;
        }
        for (int i = 0; i < frames.length; i++) {
            if (!frames[i].getClassName().equals(that.frames[i].getClassName())
                    || !frames[i].getMethodName().equals(that.frames[i].getMethodName())
                    || lines && frames[i].getLineNumber() != that.frames[i].getLineNumber()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return name();
    }
}
