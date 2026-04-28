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
import java.lang.management.ThreadMXBean;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.noureddine.joular.joularcodejava.agent.source.PowerSource;
import org.noureddine.joular.joularcodejava.agent.source.PowerSourceFactory;
import org.noureddine.joular.joularcodejava.agent.utils.AgentProperties;

/**
 * Main Joular Code - Java entry point.
 */
public class Agent {

    public static final String AGENT_THREAD_NAME = "Joular-Agent-Monitor";
    private static final Logger logger = Logger.getLogger(
            Agent.class.getName());

    private static void printJoularCodeJavaBanner() {
        String version = Agent.class.getPackage() != null
                ? Agent.class.getPackage().getImplementationVersion()
                : null;
        if (version == null || version.isEmpty()) {
            version = "unknown";
        }
        String welcomeMessage = "Joular Code - Java: version " + version;
        boolean noColor = System.getenv("NO_COLOR") != null;

        if (noColor) {
            System.out.println(welcomeMessage);
            return;
        }

        String boldYellow = "\u001B[1;33m";
        String reset = "\u001B[0m";

        System.out.println(boldYellow + welcomeMessage + reset);
    }

    public static void premain(String args, Instrumentation inst) {
        configureLogging();
        printJoularCodeJavaBanner();
        logger.log(Level.INFO, "Initializing Joular Code Java...");

        AgentProperties properties = new AgentProperties();
        PowerSource powerSource = PowerSourceFactory.getPowerSource(properties);

        if (powerSource == null) {
            logger.log(Level.SEVERE, "No valid power source found. Agent will not start.");
            return;
        }

        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        if (threadBean.isThreadCpuTimeSupported()) {
            if (!threadBean.isThreadCpuTimeEnabled()) {
                threadBean.setThreadCpuTimeEnabled(true);
            }
        } else {
            logger.log(Level.WARNING,
                    "Thread CPU time is not supported on this JVM. Method-level energy attribution may be inaccurate.");
        }

        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        if (!(osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean)) {
            logger.log(Level.SEVERE,
                    "Unsupported JVM: requires com.sun.management.OperatingSystemMXBean for CPU-load metrics. Joular Code - Java will not start.");
            return;
        }

        // Warm-up OS Bean to avoid initial negative readings
        logger.log(Level.INFO, "Warming up OS bean...");
        for (int i = 0; i < 2; i++) {
            sunOsBean.getCpuLoad();
            sunOsBean.getProcessCpuLoad();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

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
            return;
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
