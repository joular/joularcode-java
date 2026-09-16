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

package org.noureddine.joular.joularcodejava.agent.source;

import org.junit.jupiter.api.function.Executable;

import java.util.logging.Level;
import java.util.logging.Logger;

final class TestLogging {

    private TestLogging() {}

    static void quietly(Class<?> loggerOwner, Executable body) throws Exception {
        Logger logger = Logger.getLogger(loggerOwner.getName());
        Level oldLevel = logger.getLevel();
        logger.setLevel(Level.OFF);
        try {
            body.execute();
        } catch (Exception | Error e) {
            // Failed assertions and anything else worth seeing carry on untouched.
            throw e;
        } catch (Throwable t) {
            throw new AssertionError(t);
        } finally {
            logger.setLevel(oldLevel);
        }
    }
}
