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
 * <p>Two stacks are the same branch when they go through the same methods, whatever the line numbers.
 * Comparing frames is cheap next to building text, so the stacks are counted as they are, on every sample, and only written out as text once per window.
 */
final class Branch {

    private final StackTraceElement[] frames;
    private final int hash;

    private Branch(StackTraceElement[] frames, int hash) {
        this.frames = frames;
        this.hash = hash;
    }

    static Branch of(StackTraceElement[] frames) {
        int hash = 1;
        for (StackTraceElement frame : frames) {
            hash = 31 * (31 * hash + frame.getClassName().hashCode()) + frame.getMethodName().hashCode();
        }
        return new Branch(frames, hash);
    }

    /** Every frame, oldest first, as {@code a.Main.main;a.Worker.compute}. */
    String name() {
        return join(List.of());
    }

    /** Only the frames that start with one of the prefixes, oldest first; empty when none does. */
    String appName(List<String> prefixes) {
        return join(prefixes);
    }

    private String join(List<String> prefixes) {
        StringBuilder name = new StringBuilder();
        for (int i = frames.length - 1; i >= 0; i--) {
            StackTraceElement frame = frames[i];
            if (!prefixes.isEmpty() && !matchesAny(frame, prefixes)) {
                continue;
            }
            if (!name.isEmpty()) {
                name.append(';');
            }
            name.append(frame.getClassName()).append('.').append(frame.getMethodName());
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
        if (!(other instanceof Branch that) || hash != that.hash || frames.length != that.frames.length) {
            return false;
        }
        for (int i = 0; i < frames.length; i++) {
            if (!frames[i].getClassName().equals(that.frames[i].getClassName())
                    || !frames[i].getMethodName().equals(that.frames[i].getMethodName())) {
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
