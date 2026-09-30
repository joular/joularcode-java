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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The monitor thread against a fake power source, with a busy thread to measure. The attribution itself is covered
 * by {@link PowerModelTest}, since live stacks and CPU times cannot be made deterministic.
 */
class MonitorTest {

    @TempDir
    Path tempDir;

    private final List<LogRecord> logs = new CopyOnWriteArrayList<>();
    private final Handler logCapture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logs.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private final FakeSource source = new FakeSource();
    private Monitor monitor;
    private volatile boolean busy = true;

    /** A source with 100 ms windows, so tests do not wait a whole second each. */
    private static final class FakeSource implements PowerSource {
        volatile double watts = 50.0;
        volatile boolean failing = false;
        volatile int reads = 0;
        volatile String openedOn;
        volatile String closedOn;

        @Override
        public double watts() throws IOException {
            reads++;
            if (failing) {
                throw new IOException("the fake source is failing.");
            }
            return watts;
        }

        @Override
        public boolean windowOver(long elapsedNs) {
            return elapsedNs >= 100_000_000L;
        }

        @Override
        public void close() {
            closedOn = Thread.currentThread().getName();
        }
    }

    @BeforeEach
    void captureLogs() {
        Logger.getLogger(Monitor.class.getName()).addHandler(logCapture);
    }

    @AfterEach
    void tearDown() {
        busy = false;
        if (monitor != null) {
            monitor.stop();
        }
        Logger.getLogger(Monitor.class.getName()).removeHandler(logCapture);
    }

    private Monitor monitor(List<String> appPrefixes) throws IOException {
        Config config = new Config("auto", tempDir.resolve("ring"), null, "powerjoular", 10, tempDir, appPrefixes);
        monitor = new Monitor(config, () -> {
            source.openedOn = Thread.currentThread().getName();
            return source;
        });
        return monitor;
    }

    private void startBusyThread() {
        Thread.ofPlatform().daemon().start(() -> {
            double x = 0;
            while (busy) {
                x += Math.sqrt(x + 1);
            }
        });
    }

    private List<String> rows(String file) {
        try {
            return Files.readAllLines(tempDir.resolve(file)).stream().skip(1).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(20);
        }
    }

    /** The source is opened and closed by the thread that reads it, so a source never has to be thread safe. */
    @Test
    void source_isOpenedAndClosedOnTheMonitorThread() throws Exception {
        monitor(List.of()).start();
        await(() -> source.reads > 0);

        monitor.stop();

        assertFalse(monitor.isAlive());
        assertEquals(Monitor.THREAD_NAME, source.openedOn);
        assertEquals(Monitor.THREAD_NAME, source.closedOn);
    }

    @Test
    void busyThread_getsRowsInBothFiles() throws Exception {
        startBusyThread();
        monitor(List.of(MonitorTest.class.getName())).start();

        await(() -> !rows(ResultWriter.APP_METHODS_FILE).isEmpty());

        assertTrue(rows(ResultWriter.APP_METHODS_FILE).stream().anyMatch(row -> row.contains("MonitorTest.lambda$")));
        assertFalse(rows(ResultWriter.ALL_METHODS_FILE).isEmpty());
    }

    /** Without a reading there is no energy to share: nothing is written, and the loss is logged once. */
    @Test
    void failingSource_writesNothingAndWarnsOnce() throws Exception {
        source.failing = true;
        startBusyThread();
        monitor(List.of()).start();

        await(() -> source.reads >= 3);
        monitor.stop();

        assertTrue(rows(ResultWriter.ALL_METHODS_FILE).isEmpty());
        assertEquals(1, logs.stream().filter(record -> record.getLevel() == Level.WARNING
                && record.getMessage().contains("the fake source is failing")).count());
    }

    /** A source that cannot be opened ends monitoring with its reason, and nothing else. */
    @Test
    void openFailure_isLoggedAndMonitoringEnds() throws Exception {
        Config config = new Config("auto", tempDir.resolve("ring"), null, "powerjoular", 10, tempDir, List.of());
        monitor = new Monitor(config, () -> {
            throw new IOException("No power here.");
        });
        monitor.start();

        await(() -> !monitor.isAlive());

        assertTrue(logs.stream().anyMatch(record -> record.getLevel() == Level.SEVERE
                && record.getMessage().contains("No power here.")));
    }
}
