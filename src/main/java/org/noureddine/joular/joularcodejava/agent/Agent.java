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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.Instrumentation;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Entry point of Joular Code for Java, as {@code -javaagent:joularcodejava.jar} or loaded into a running JVM through the Attach API.
 * Reads the configuration and starts the {@link Monitor}.
 */
public final class Agent {

    /** The last monitor started, or null. Set under the class lock, read by the shutdown hook. */
    private static volatile Monitor activeMonitor;

    private static boolean shutdownHookAdded = false;

    private Agent() {
    }

    /** Called when the agent is given on the command line. */
    public static void premain(String args, Instrumentation inst) {
        start(args);
    }

    /** Called when the agent is loaded into a running JVM. Only what happens after that is measured. */
    public static void agentmain(String args, Instrumentation inst) {
        start(args);
    }

    /**
     * The logger of one of the agent's classes.
     * The first one asked for sets java.util.logging up for the agent, so it is not touched before the agent has something to say.
     */
    static Logger logger(Class<?> owner) {
        return Logging.get(owner);
    }

    private static synchronized void start(String args) {
        if (activeMonitor != null && activeMonitor.isAlive()) {
            logger(Agent.class).log(Level.WARNING, "Joular Code for Java is already monitoring this JVM. Ignoring this attach.");
            return;
        }
        // Nothing may escape: an exception thrown out of premain stops the application from running at all
        try {
            printBanner();

            Config config = Config.load(args);
            Monitor monitor = new Monitor(config, () -> PowerSource.open(config));
            monitor.start();
            activeMonitor = monitor;
            if (!shutdownHookAdded) {
                // One hook however often the agent is attached: it stops whichever monitor is running by then
                Runtime.getRuntime().addShutdownHook(new Thread(() -> activeMonitor.stop(), "Joular-Agent-Shutdown"));
                shutdownHookAdded = true;
            }
        } catch (Throwable t) {
            logger(Agent.class).log(Level.SEVERE,
                    "Joular Code for Java could not start. The application is unaffected and carries on without monitoring.", t);
        }
    }

    private static void printBanner() {
        String version = Objects.requireNonNullElse(Agent.class.getPackage().getImplementationVersion(), "unknown");
        // stderr, like the logs: the application's stdout may be a data stream
        System.err.println("Joular Code for Java: version " + version);
    }

    /**
     * Sends the agent's log records to stderr, without touching how the application logs.
     * Loaded, and so set up, with the first logger asked for rather than in premain.
     */
    private static final class Logging {

        // Held in a field because java.util.logging only keeps weak references, and would lose the handler with the logger
        private static final Logger JOULAR = Logger.getLogger("org.noureddine.joular");

        static {
            ConsoleHandler handler = new ConsoleHandler();
            handler.setFormatter(new LogFormatter());
            // Let everything through: the logger's level decides what is published
            handler.setLevel(Level.ALL);
            JOULAR.addHandler(handler);
            JOULAR.setUseParentHandlers(false);
        }

        static Logger get(Class<?> owner) {
            return Logger.getLogger(owner.getName());
        }
    }

    /** {@code dd-MM-yyyy HH:mm:ss LEVEL: message}, followed by the stack trace if there is one. */
    private static final class LogFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            String line = String.format(Locale.ROOT, "%1$td-%1$tm-%1$tY %1$tH:%1$tM:%1$tS %2$s: %3$s%n",
                    record.getMillis(), record.getLevel().getName(), formatMessage(record));
            if (record.getThrown() == null) {
                return line;
            }
            StringWriter stackTrace = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(stackTrace));
            return line + stackTrace;
        }
    }
}
