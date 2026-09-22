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

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.noureddine.joular.joularcodejava.agent.source.PowerSource;
import org.noureddine.joular.joularcodejava.agent.source.PowerSourceFactory;
import org.noureddine.joular.joularcodejava.agent.utils.AgentProperties;

/**
 * Main Joular Code for Java entry point.
 */
public class Agent {

    public static final String AGENT_THREAD_NAME = "Joular-Agent-Monitor";
    private static final Logger logger = Logger.getLogger(
            Agent.class.getName());

    /**
     * Whether the agent holds this JVM. The Attach API can be asked to load an agent as often as anyone likes, and a second monitoring thread would sample the same JVM twice and write both sets of rows into the same files.
     */
    private static final AtomicBoolean started = new AtomicBoolean(false);

    private static void printJoularCodeJavaBanner() {
        String version = Agent.class.getPackage() != null
                ? Agent.class.getPackage().getImplementationVersion()
                : null;
        if (version == null || version.isEmpty()) {
            version = "unknown";
        }
        String welcomeMessage = "Joular Code for Java: version " + version;
        boolean noColor = System.getenv("NO_COLOR") != null;

        if (noColor) {
            System.out.println(welcomeMessage);
            return;
        }

        String boldYellow = "\u001B[1;33m";
        String reset = "\u001B[0m";

        System.out.println(boldYellow + welcomeMessage + reset);
    }

    /** Entry point when the agent is on the command line, as {@code -javaagent:joularcodejava.jar}. */
    public static void premain(String args, Instrumentation inst) {
        start();
    }

    /**
     * Entry point when the agent is loaded into a JVM that is already running, through the Attach
     * API ({@code VirtualMachine.loadAgent}) or a tool built on it.
     *
     * <p>Monitoring covers the JVM from the moment it attaches, so whatever the application did
     * before that is not in the results. Note that a JVM only accepts an agent this way when it was
     * started with {@code -XX:+EnableDynamicAgentLoading}, or is old enough to still allow it by default.
     */
    public static void agentmain(String args, Instrumentation inst) {
        start();
    }

    private static void start() {
        if (!started.compareAndSet(false, true)) {
            logger.log(Level.WARNING,
                    "Joular Code for Java is already monitoring this JVM. Ignoring this attach.");
            return;
        }
        if (!startMonitoring()) {
            // Nothing was left running, so a later attach is free to try again
            started.set(false);
        }
    }

    /**
     * Brings the agent up.
     *
     * @return {@code true} when a monitoring thread was left running, {@code false} when startup could not proceed and this JVM is unchanged.
     */
    private static boolean startMonitoring() {
        configureLogging();
        printJoularCodeJavaBanner();
        logger.log(Level.INFO, "Initializing Joular Code Java...");

        AgentProperties properties = new AgentProperties();
        PowerSource powerSource = PowerSourceFactory.getPowerSource(properties);

        if (powerSource == null) {
            logger.log(Level.SEVERE, "No valid power source found. Agent will not start.");
            return false;
        }

        if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean threadBean)) {
            logger.log(Level.SEVERE,
                    "Unsupported JVM: requires com.sun.management.ThreadMXBean to read every thread's CPU time in one call. Joular Code for Java will not start.");
            return false;
        }
        if (threadBean.isThreadCpuTimeSupported()) {
            if (!threadBean.isThreadCpuTimeEnabled()) {
                threadBean.setThreadCpuTimeEnabled(true);
            }
        } else {
            logger.log(Level.SEVERE,
                    "Thread CPU time is not supported on this JVM. Joular Code for Java will not start.");
            return false;
        }

        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        if (!(osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean)) {
            logger.log(Level.SEVERE,
                    "Unsupported JVM: requires com.sun.management.OperatingSystemMXBean for CPU-load metrics. Joular Code for Java will not start.");
            return false;
        }

        // The OS bean reports its loads over the interval since the previous call, so the first reading is meaningless and comes back negative.
        // The monitoring loop's first window absorbs that: its share comes out as zero, so that window simply attributes nothing.

        MonitoringHandler monitoringHandler = new MonitoringHandler(
                properties,
                powerSource,
                threadBean,
                sunOsBean);
        Thread monitoringThread = new Thread(
                monitoringHandler,
                AGENT_THREAD_NAME);
        monitoringThread.setDaemon(true);
        monitoringThread.start();

        boolean startupSignaled = monitoringHandler.awaitStartup(3000);
        if (startupSignaled && !monitoringHandler.isStartupSuccessful()) {
            Throwable startupFailure = monitoringHandler.getStartupFailure();
            logger.log(Level.SEVERE, "Monitoring thread failed during startup.", startupFailure);
            return false;
        }
        if (!startupSignaled) {
            logger.log(Level.WARNING,
                    "Monitoring startup could not be confirmed within 3 seconds. The monitoring thread may still be initializing.");
        }

        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    logger.log(Level.INFO, "Stopping Joular Code Java...");
                    monitoringHandler.stop();
                    monitoringHandler.joinWithTimeout(2000);
                }));

        logger.log(Level.INFO, "Joular Code Java started successfully.");
        return true;
    }

    private static void configureLogging() {
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            handler.setFormatter(
                    new Formatter() {
                        @Override
                        public String format(LogRecord record) {
                            StringBuilder formatted = new StringBuilder(String.format(
                                    Locale.ROOT,
                                    "%1$td-%1$tm-%1$tY %1$tH:%1$tM:%1$tS %2$s: %3$s%n",
                                    record.getMillis(),
                                    record.getLevel().getName(),
                                    formatMessage(record)));
                            if (record.getThrown() != null) {
                                StringWriter sw = new StringWriter();
                                PrintWriter pw = new PrintWriter(sw);
                                record.getThrown().printStackTrace(pw);
                                pw.flush();
                                formatted.append(sw.toString());
                            }
                            return formatted.toString();
                        }
                    });
        }
    }
}
