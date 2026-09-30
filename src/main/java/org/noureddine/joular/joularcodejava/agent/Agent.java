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

    private static final Logger logger = Logger.getLogger(Agent.class.getName());

    // Held in a field because java.util.logging only keeps weak references, and would lose the handler with the logger
    private static final Logger joularLogger = Logger.getLogger("org.noureddine.joular");

    private static boolean loggingConfigured = false;

    /** The last monitor started, or null. Guarded by the class lock, since the Attach API can load the agent again. */
    private static Monitor activeMonitor;

    private Agent() {
    }

    /** Called when the agent is given on the command line. */
    public static void premain(String args, Instrumentation inst) {
        start();
    }

    /** Called when the agent is loaded into a running JVM. Only what happens after that is measured. */
    public static void agentmain(String args, Instrumentation inst) {
        start();
    }

    private static synchronized void start() {
        if (activeMonitor != null && activeMonitor.isAlive()) {
            logger.log(Level.WARNING, "Joular Code for Java is already monitoring this JVM. Ignoring this attach.");
            return;
        }
        // Nothing may escape: an exception thrown out of premain stops the application from running at all
        try {
            configureLogging();
            printBanner();

            Config config = Config.load();
            Monitor monitor = new Monitor(config, () -> PowerSource.open(config));
            monitor.start();
            Runtime.getRuntime().addShutdownHook(new Thread(monitor::stop, "Joular-Agent-Shutdown"));
            activeMonitor = monitor;
            logger.log(Level.INFO, "Joular Code for Java started. Results are written to " + config.resultsDir().toAbsolutePath());
        } catch (Throwable t) {
            logger.log(Level.SEVERE,
                    "Joular Code for Java could not start. The application is unaffected and carries on without monitoring.", t);
        }
    }

    /** Sends the agent's own log records to stderr, without touching how the application logs. */
    private static void configureLogging() {
        if (loggingConfigured) {
            return;
        }
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new LogFormatter());
        // Let everything through: the logger's level decides what is published
        handler.setLevel(Level.ALL);
        joularLogger.addHandler(handler);
        joularLogger.setUseParentHandlers(false);
        loggingConfigured = true;
    }

    private static void printBanner() {
        String version = Objects.requireNonNullElse(Agent.class.getPackage().getImplementationVersion(), "unknown");
        // stderr, like the logs: the application's stdout may be a data stream
        System.err.println("Joular Code for Java: version " + version);
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
