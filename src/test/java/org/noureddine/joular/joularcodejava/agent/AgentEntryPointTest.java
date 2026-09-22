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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two entry points the JVM looks up by name.
 *
 * <p>The manifest names this class for both {@code Premain-Class} and {@code Agent-Class}, and the
 * JVM resolves the matching method reflectively at load time. Nothing in the Java compiler ties the
 * two together, so the manifest once advertised {@code Agent-Class} while no {@code agentmain}
 * existed: every attach to a running JVM failed, and no test noticed. These assertions are the
 * missing link, and they fail at build time rather than at someone else's attach.
 *
 * <p>They deliberately only inspect the signatures. Calling either method starts a monitoring
 * thread and writes result files, which belongs in an integration run rather than a unit test.
 */
class AgentEntryPointTest {

    /** Loaded by the JVM when the agent is given on the command line with {@code -javaagent:}. */
    @Test
    void premain_hasTheSignatureTheJvmLooksUp() {
        assertIsAgentEntryPoint("premain");
    }

    /** Loaded by the JVM when the agent is attached to an already running process. */
    @Test
    void agentmain_hasTheSignatureTheAttachApiLooksUp() {
        assertIsAgentEntryPoint("agentmain");
    }

    private static void assertIsAgentEntryPoint(String name) {
        Method method = assertDoesNotThrow(
                () -> Agent.class.getMethod(name, String.class, Instrumentation.class),
                name + "(String, Instrumentation) must exist for the JVM to find it");

        int modifiers = method.getModifiers();
        assertTrue(Modifier.isPublic(modifiers), name + " must be public");
        assertTrue(Modifier.isStatic(modifiers), name + " must be static");
        assertEquals(void.class, method.getReturnType(), name + " must return void");
    }
}
