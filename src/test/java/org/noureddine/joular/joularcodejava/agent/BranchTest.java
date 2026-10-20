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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Branch identity, which decides what adds up, and its names, which feed the result files. */
class BranchTest {

    /**
     * A branch the way the JVM hands a stack over: newest frame at index 0.
     *
     * @param methods {@code package.Class.method} names, oldest first, which is the order they come back out in
     */
    static Branch branch(String... methods) {
        StackTraceElement[] frames = new StackTraceElement[methods.length];
        for (int i = 0; i < methods.length; i++) {
            String qualified = methods[methods.length - 1 - i];
            int lastDot = qualified.lastIndexOf('.');
            frames[i] = new StackTraceElement(qualified.substring(0, lastDot), qualified.substring(lastDot + 1), null, i);
        }
        return Branch.of(frames, false);
    }

    @Test
    void name_walksTheStackOldestFrameFirst() {
        assertEquals("com.example.Main.main;com.example.Worker.compute",
                branch("com.example.Main.main", "com.example.Worker.compute").name());
    }

    @Test
    void name_singleFrame_hasNoSeparator() {
        assertEquals("com.example.Main.main", branch("com.example.Main.main").name());
    }

    /** Samples on different lines of the same methods are one branch, so their counts add up. */
    @Test
    void equals_ignoresLineNumbers() {
        StackTraceElement[] onLine10 = {new StackTraceElement("a.Worker", "compute", "Worker.java", 10)};
        StackTraceElement[] onLine20 = {new StackTraceElement("a.Worker", "compute", "Worker.java", 20)};

        assertEquals(Branch.of(onLine10, false), Branch.of(onLine20, false));
        assertEquals(Branch.of(onLine10, false).hashCode(), Branch.of(onLine20, false).hashCode());
    }

    @Test
    void equals_linesMonitored_differentLinesAreDifferentBranches() {
        StackTraceElement[] onLine10 = {new StackTraceElement("a.Worker", "compute", "Worker.java", 10)};
        StackTraceElement[] alsoOnLine10 = {new StackTraceElement("a.Worker", "compute", "Worker.java", 10)};
        StackTraceElement[] onLine20 = {new StackTraceElement("a.Worker", "compute", "Worker.java", 20)};

        assertNotEquals(Branch.of(onLine10, true), Branch.of(onLine20, true));
        assertEquals(Branch.of(onLine10, true), Branch.of(alsoOnLine10, true));
        assertEquals(Branch.of(onLine10, true).hashCode(), Branch.of(alsoOnLine10, true).hashCode());
    }

    /** Each frame as a stack trace writes it, newest last, filtered as the app name is; the method names stay as they were. */
    @Test
    void lineName_writesEachFrameAsAStackTrace() {
        Branch branch = Branch.of(new StackTraceElement[] {
                new StackTraceElement("java.lang.Thread", "sleep0", "Thread.java", -2),
                new StackTraceElement("a.Generated", "run", null, 7),
                new StackTraceElement("a.Service", "process", "Service.java", -1),
                new StackTraceElement("a.Main", "main", "Main.java", 12)}, true);

        assertEquals("a.Main.main(Main.java:12);a.Service.process(Service.java);a.Generated.run(Unknown Source);"
                + "java.lang.Thread.sleep0(Native Method)", branch.lineName());
        assertEquals("a.Main.main(Main.java:12);a.Service.process(Service.java);a.Generated.run(Unknown Source)",
                branch.appLineName(List.of("a.")));
        assertEquals("", branch.appLineName(List.of("com.example")));
        assertEquals("a.Main.main;a.Service.process;a.Generated.run;java.lang.Thread.sleep0", branch.name());
        assertEquals("a.Main.main;a.Service.process;a.Generated.run", branch.appName(List.of("a.")));
    }

    @Test
    void equals_differentMethodsOrDepth_areDifferentBranches() {
        assertNotEquals(branch("a.Main.main", "a.Worker.compute"), branch("a.Main.main", "a.Worker.save"));
        assertNotEquals(branch("a.Main.main", "a.Worker.compute"), branch("a.Main.main"));
        assertNotEquals(branch("a.Main.main", "a.Worker.compute"), branch("a.Worker.compute", "a.Main.main"));
    }

    @Test
    void appName_keepsOnlyMatchingFrames() {
        Branch branch = branch("java.lang.Thread.run", "com.example.Worker.compute", "java.util.HashMap.put");

        assertEquals("java.lang.Thread.run;com.example.Worker.compute;java.util.HashMap.put", branch.name());
        assertEquals("com.example.Worker.compute", branch.appName(List.of("com.example")));
    }

    @Test
    void appName_nothingMatches_isEmpty() {
        assertEquals("", branch("java.lang.Thread.run", "java.util.HashMap.put").appName(List.of("com.example")),
                "an unmatched stack contributes nothing to the app file");
    }

    /** Unmatched frames called by a matched method collapse onto it, so stacks that differ only below it add up. */
    @Test
    void appName_unmatchedFramesBelowAMatch_collapseOntoTheMatchedMethod() {
        List<String> prefixes = List.of("com.example");

        assertEquals("com.example.Service.save",
                branch("com.example.Service.save", "java.util.HashMap.put").appName(prefixes));
        assertEquals("com.example.Service.save",
                branch("com.example.Service.save", "java.io.FileOutputStream.write").appName(prefixes));
    }

    @Test
    void appName_multiplePrefixes_eachOneMatches() {
        assertEquals("org.myapp.Boot.start;com.example.Worker.compute",
                branch("org.myapp.Boot.start", "java.util.HashMap.put", "com.example.Worker.compute")
                        .appName(List.of("com.example", "org.myapp")));
    }

    /** The prefix is plain text, not a package boundary, as documented for the property. */
    @Test
    void appName_prefixMatchesTextNotPackageBoundaries() {
        assertEquals("com.examples.Other.run;com.example.Worker.compute",
                branch("com.examples.Other.run", "com.example.Worker.compute").appName(List.of("com.example")));
    }

    @Test
    void startsWith_prefixReachingIntoTheMethodName() {
        assertTrue(Branch.startsWith("com.example.Worker", "compute", "com.example.Worker.com"));
        assertTrue(Branch.startsWith("com.example.Worker", "compute", "com.example.Worker.compute"));
        assertTrue(Branch.startsWith("com.example.Worker", "compute", "com.example.Worker."));

        assertFalse(Branch.startsWith("com.example.Worker", "compute", "com.example.Worker.x"));
        assertFalse(Branch.startsWith("com.example.Worker", "compute", "com.example.Worker.computed"),
                "a prefix longer than the whole name cannot match");
        assertFalse(Branch.startsWith("com.example.Worker", "compute", "com.example.Workers"));
    }

    @Test
    void startsWith_prefixInsideTheClassName() {
        assertTrue(Branch.startsWith("com.example.Worker", "compute", "com.example"));
        assertTrue(Branch.startsWith("com.example.Worker", "compute", "com.example.Worker"));
        assertFalse(Branch.startsWith("com.example.Worker", "compute", "org.other"));
    }
}
