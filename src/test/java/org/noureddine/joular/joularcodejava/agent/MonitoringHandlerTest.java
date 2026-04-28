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

import com.sun.management.OperatingSystemMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.noureddine.joular.joularcodejava.agent.source.PowerSource;
import org.noureddine.joular.joularcodejava.agent.utils.AgentProperties;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MonitoringHandler}.
 *
 * <p>These tests focus on the startup lifecycle and thread-management API rather
 * than the energy-attribution algorithm, which depends on live stack traces and
 * CPU time measurements that cannot be made deterministic in a unit-test context.
 *
 * <h2>What is tested</h2>
 * <ul>
 *   <li>Startup success path: {@code awaitStartup} returns {@code true} and
 *       {@code isStartupSuccessful} returns {@code true}.</li>
 *   <li>Startup failure paths: a throwing {@link PowerSource#initialize()} and an
 *       unwritable results directory both release the startup latch with a failure
 *       flag set.</li>
 *   <li>Thread lifecycle: {@code stop()} + {@code joinWithTimeout()} reliably
 *       terminates the monitoring thread.</li>
 *   <li>Pre-start guards: {@code stop()}, {@code joinWithTimeout()},
 *       {@code isStartupSuccessful()}, and {@code getStartupFailure()} all behave
 *       correctly when called before the thread has been started.</li>
 * </ul>
 *
 * <h2>JDK MX bean strategy</h2>
 * <p>The {@link ThreadMXBean} and {@link OperatingSystemMXBean} interfaces belong
 * to closed JDK modules. Byte Buddy (used by Mockito) cannot instrument them
 * without special JVM flags on recent JDK versions. To avoid this limitation, the
 * tests obtain real singleton beans from {@link ManagementFactory}. Only
 * {@link PowerSource} — a project-defined interface — is mocked.
 *
 * <h2>Test isolation</h2>
 * <p>{@code @AfterEach} stops and joins any running handler thread and clears the
 * {@code joularcodejava.properties} system property so tests cannot interfere
 * with each other.
 */
@ExtendWith(MockitoExtension.class)
class MonitoringHandlerTest {

    /** Mocked power source — controls initialize() and getCurrentPower() behaviour. */
    @Mock
    PowerSource mockPowerSource;

    /**
     * Real JDK thread bean. Cannot be mocked without Byte Buddy experimental mode
     * due to module encapsulation in JDK 17+.
     */
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    /**
     * Real JDK OS bean (com.sun.management flavour required by MonitoringHandler).
     * Same module-encapsulation constraint as ThreadMXBean.
     */
    private final OperatingSystemMXBean osBean =
            ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);

    @TempDir
    Path tempDir;

    private MonitoringHandler handler;
    private Thread handlerThread;

    @AfterEach
    void tearDown() {
        System.clearProperty("joularcodejava.properties");
        if (handler != null) {
            handler.stop();
            handler.joinWithTimeout(2000);
        }
    }

    /**
     * Writes a minimal properties file to the temp directory and constructs a
     * {@link MonitoringHandler} using the provided results path.
     * A 100 ms sample rate is used so the monitoring loop cycles quickly and
     * tests remain fast.
     *
     * <p>{@link Properties#store} is used instead of plain string concatenation so
     * that Windows paths with backslashes are properly double-escaped (e.g.,
     * {@code C:\tmp} → {@code C:\\tmp}). Without this, {@link Properties#load}
     * would silently consume the backslashes as escape-sequence prefixes and
     * resolve an entirely different path, causing the unwritable-path test to
     * pass startup checks on Windows when it should fail.
     */
    private MonitoringHandler makeHandler(String resultsPath) throws Exception {
        Path propsFile = tempDir.resolve("joularcodejava.properties");
        Properties props = new Properties();
        props.setProperty("results-path", resultsPath);
        props.setProperty("stack-monitoring-sample-rate", "100");
        try (var w = Files.newBufferedWriter(propsFile, StandardCharsets.UTF_8)) {
            props.store(w, null);
        }
        System.setProperty("joularcodejava.properties", propsFile.toString());
        AgentProperties agentProps = new AgentProperties();
        return new MonitoringHandler(agentProps, mockPowerSource, threadBean, osBean);
    }

    /**
     * Starts the handler on a daemon thread so that a test failure can never
     * prevent the JVM from exiting.
     */
    private void startHandler() {
        handlerThread = new Thread(handler, "test-monitoring");
        handlerThread.setDaemon(true);
        handlerThread.start();
    }

    // -------------------------------------------------------------------------
    // Startup lifecycle
    // -------------------------------------------------------------------------

    /**
     * Under normal conditions (writable results directory, no-throw
     * {@code PowerSource.initialize()}), the startup latch must be released
     * within the timeout, {@code isStartupSuccessful()} must return {@code true},
     * and no failure must be recorded.
     */
    @Test
    void awaitStartup_successfulInit_latchReleasedAndSuccessful() throws Exception {
        handler = makeHandler(tempDir.toString());
        startHandler();

        boolean settled = handler.awaitStartup(3000);
        assertTrue(settled, "awaitStartup should return true");
        assertTrue(handler.isStartupSuccessful(), "Startup should be successful");
        assertNull(handler.getStartupFailure());
    }

    /**
     * When {@code PowerSource.initialize()} throws, the handler must:
     * <ul>
     *   <li>release the startup latch (so {@code awaitStartup} does not hang),</li>
     *   <li>record {@code isStartupSuccessful() == false},</li>
     *   <li>expose the thrown exception via {@code getStartupFailure()}.</li>
     * </ul>
     * The monitoring thread exits on its own after the failure; no explicit
     * {@code stop()} call is needed (but the teardown calls it anyway as a guard).
     */
    @Test
    void awaitStartup_powerSourceThrows_latchReleasedWithFailure() throws Exception {
        doThrow(new RuntimeException("init failed")).when(mockPowerSource).initialize();

        handler = makeHandler(tempDir.toString());
        startHandler();

        boolean settled = handler.awaitStartup(3000);
        assertTrue(settled);
        assertFalse(handler.isStartupSuccessful());
        assertNotNull(handler.getStartupFailure());
        assertTrue(handler.getStartupFailure().getMessage().contains("init failed"));
    }

    /**
     * When the configured results path is an existing regular file rather than
     * a directory, {@code ResultWriter.verifyWritable()} fails with an
     * {@link java.io.IOException}. The handler must propagate this as a startup
     * failure so the agent aborts with a clear log message rather than silently
     * dropping all results.
     */
    @Test
    void awaitStartup_unwritableResultsPath_failsFast() throws Exception {
        // A regular file at the path prevents directory creation.
        Path blockingFile = tempDir.resolve("blocked");
        Files.writeString(blockingFile, "not a directory", StandardCharsets.UTF_8);

        handler = makeHandler(blockingFile.toString());
        startHandler();

        boolean settled = handler.awaitStartup(3000);
        assertTrue(settled);
        assertFalse(handler.isStartupSuccessful());
        assertNotNull(handler.getStartupFailure());
        assertInstanceOf(java.io.IOException.class, handler.getStartupFailure());
    }

    /**
     * After a successful startup, {@code stop()} must interrupt the monitoring
     * thread and {@code joinWithTimeout()} must return within the timeout,
     * confirming the thread has terminated and will not prevent JVM shutdown.
     */
    @Test
    void stop_afterSuccessfulStartup_threadTerminates() throws Exception {
        handler = makeHandler(tempDir.toString());
        startHandler();

        handler.awaitStartup(3000);
        assertTrue(handler.isStartupSuccessful());

        handler.stop();
        handler.joinWithTimeout(3000);

        assertFalse(handlerThread.isAlive(), "Monitoring thread should have terminated");
    }

    // -------------------------------------------------------------------------
    // Pre-start state
    // -------------------------------------------------------------------------

    /**
     * Before the monitoring thread is started, the startup latch has not been
     * counted down yet, so {@code isStartupSuccessful()} must return its initial
     * value of {@code false}.
     */
    @Test
    void isStartupSuccessful_beforeStart_returnsFalse() throws Exception {
        handler = makeHandler(tempDir.toString());
        assertFalse(handler.isStartupSuccessful());
    }

    /**
     * Before the monitoring thread is started, no failure has occurred, so
     * {@code getStartupFailure()} must return {@code null}.
     */
    @Test
    void getStartupFailure_beforeStart_returnsNull() throws Exception {
        handler = makeHandler(tempDir.toString());
        assertNull(handler.getStartupFailure());
    }

    /**
     * {@code stop()} guards against a null {@code monitoringThread} field
     * (set lazily when the thread starts). Calling it before the thread has
     * been started must not throw a {@code NullPointerException}.
     */
    @Test
    void stop_beforeThreadStarted_doesNotThrow() throws Exception {
        handler = makeHandler(tempDir.toString());
        assertDoesNotThrow(() -> handler.stop());
    }

    /**
     * {@code joinWithTimeout()} guards against a null {@code monitoringThread}
     * field and must return immediately (within well under 1 second) rather
     * than blocking when no thread has been started.
     */
    @Test
    void joinWithTimeout_beforeThreadStarted_returnsImmediately() throws Exception {
        handler = makeHandler(tempDir.toString());
        long start = System.currentTimeMillis();
        handler.joinWithTimeout(100);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 1000, "joinWithTimeout should return quickly when thread not started");
    }
}
