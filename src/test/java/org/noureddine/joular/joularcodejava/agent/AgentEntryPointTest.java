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

import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The manifest names Agent for Premain-Class and Agent-Class, and the JVM looks these two methods up by signature. */
class AgentEntryPointTest {

    /** Loaded by the JVM when the agent is given on the command line with {@code -javaagent:}. */
    @Test
    void premain_hasTheSignatureTheJvmLooksUp() throws Exception {
        assertIsAgentEntryPoint("premain");
    }

    /** Loaded by the JVM when the agent is attached to an already running process. */
    @Test
    void agentmain_hasTheSignatureTheAttachApiLooksUp() throws Exception {
        assertIsAgentEntryPoint("agentmain");
    }

    private static void assertIsAgentEntryPoint(String name) throws Exception {
        Method method = Agent.class.getMethod(name, String.class, Instrumentation.class);

        int modifiers = method.getModifiers();
        assertTrue(Modifier.isPublic(modifiers), name + " must be public");
        assertTrue(Modifier.isStatic(modifiers), name + " must be static");
        assertEquals(void.class, method.getReturnType(), name + " must return void");
    }
}
