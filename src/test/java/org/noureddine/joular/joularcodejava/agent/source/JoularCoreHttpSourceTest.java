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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JoularCoreHttpSource}.
 *
 * <p>No live HTTP server is needed: the tests cover two independently testable
 * areas without making network calls:
 * <ul>
 *   <li><b>Constructor validation</b> — the constructor parses and validates the
 *       configured URL at construction time so that misconfiguration is caught at
 *       agent startup rather than silently at runtime.</li>
 *   <li><b>Static JSON helpers</b> — {@code findKey} and {@code extractCpuPower}
 *       are package-private static methods that do all the JSON parsing. Testing
 *       them in isolation covers the full parsing logic including edge cases
 *       (substring key matches, scientific notation, non-numeric values) without
 *       requiring a mock HTTP client.</li>
 * </ul>
 */
class JoularCoreHttpSourceTest {

    // -------------------------------------------------------------------------
    // Constructor validation
    // -------------------------------------------------------------------------

    /**
     * A {@code null} URL must be rejected immediately at construction time
     * with an {@link IllegalArgumentException} rather than producing a
     * NullPointerException later during the first HTTP request.
     */
    @Test
    void constructor_nullUrl_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> new JoularCoreHttpSource(null));
    }

    /**
     * A whitespace-only URL (after trim it becomes empty) must be rejected
     * the same way as a {@code null} URL.
     */
    @Test
    void constructor_emptyUrl_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> new JoularCoreHttpSource("   "));
    }

    /**
     * Only {@code http} and {@code https} schemes are supported. An
     * {@code ftp://} URL must be rejected and the exception message must
     * mention the accepted schemes so the misconfiguration is easy to diagnose.
     */
    @Test
    void constructor_ftpScheme_throwsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new JoularCoreHttpSource("ftp://example.com/data"));
        assertTrue(ex.getMessage().contains("http") || ex.getMessage().contains("https"),
                "Message should mention http/https");
    }

    /**
     * A URL that is syntactically valid as a URI but has an empty host must
     * be rejected. The input {@code "http://:8080"} parses successfully
     * (scheme=http, authority=":8080") yet {@code getHost()} returns an empty
     * string, triggering the missing-host guard.
     */
    @Test
    void constructor_missingHost_throwsIllegalArgumentException() {
        // "http://:8080" parses as a valid URI (scheme=http, authority=":8080"),
        // but getHost() returns "" → triggers the "missing host" guard.
        assertThrows(IllegalArgumentException.class,
                () -> new JoularCoreHttpSource("http://:8080"));
    }

    /**
     * A well-formed {@code http://} URL must be accepted without throwing.
     * The source is closed immediately to release the internal executor thread.
     */
    @Test
    void constructor_validHttpUrl_doesNotThrow() {
        assertDoesNotThrow(() -> {
            JoularCoreHttpSource source = new JoularCoreHttpSource("http://localhost:8080/data");
            source.close();
        });
    }

    /**
     * A well-formed {@code https://} URL must also be accepted, confirming
     * that TLS endpoints are supported in addition to plain HTTP.
     */
    @Test
    void constructor_validHttpsUrl_doesNotThrow() {
        assertDoesNotThrow(() -> {
            JoularCoreHttpSource source = new JoularCoreHttpSource("https://example.com/power");
            source.close();
        });
    }

    // -------------------------------------------------------------------------
    // findKey
    // -------------------------------------------------------------------------

    /**
     * A key that appears at the very start of a JSON object (preceded only by
     * the opening brace {@code {}) must be found and its position returned.
     */
    @Test
    void findKey_atStartOfObject_returnsPosition() {
        int pos = JoularCoreHttpSource.findKey("{\"cpu_power\":5}", "cpu_power");
        assertTrue(pos >= 0);
    }

    /**
     * A key that appears after another key-value pair (preceded by a comma)
     * must also be located correctly.
     */
    @Test
    void findKey_afterComma_returnsPosition() {
        int pos = JoularCoreHttpSource.findKey("{\"other\":1,\"cpu_power\":5}", "cpu_power");
        assertTrue(pos >= 0);
    }

    /**
     * Whitespace between the comma separator and the opening quote of the key
     * is allowed by JSON and must not prevent the key from being found.
     */
    @Test
    void findKey_afterCommaWithWhitespace_returnsPosition() {
        int pos = JoularCoreHttpSource.findKey("{\"other\":1, \"cpu_power\":5}", "cpu_power");
        assertTrue(pos >= 0);
    }

    /**
     * The key {@code "cpu_power"} is a suffix of {@code "last_cpu_power"}.
     * Without the structural guard (checking that the character before the
     * opening quote is {@code {}, {@code ,}, or whitespace following one of
     * those), a naive {@code indexOf} would falsely match inside the longer
     * key name. This test verifies the guard prevents that false positive.
     */
    @Test
    void findKey_substringOfOtherKey_returnsMinusOne() {
        // "last_cpu_power" contains "cpu_power" as a suffix; must not produce a match.
        int pos = JoularCoreHttpSource.findKey("{\"last_cpu_power\":5}", "cpu_power");
        assertEquals(-1, pos);
    }

    /**
     * When the target key is entirely absent from the JSON, {@code -1} must
     * be returned so the caller knows no value is available.
     */
    @Test
    void findKey_missingKey_returnsMinusOne() {
        assertEquals(-1, JoularCoreHttpSource.findKey("{\"other\":1}", "cpu_power"));
    }

    /**
     * An empty JSON object contains no keys at all; {@code -1} must be returned.
     */
    @Test
    void findKey_emptyJson_returnsMinusOne() {
        assertEquals(-1, JoularCoreHttpSource.findKey("{}", "cpu_power"));
    }

    // -------------------------------------------------------------------------
    // extractCpuPower
    // -------------------------------------------------------------------------

    /**
     * The minimal valid JSON payload — a single-key object — must yield the
     * numeric value associated with {@code "cpu_power"}.
     */
    @Test
    void extractCpuPower_validJson_returnsValue() {
        assertEquals(42.5, JoularCoreHttpSource.extractCpuPower("{\"cpu_power\":42.5}"), 1e-9);
    }

    /**
     * Extra whitespace around the colon and the value is allowed by JSON and
     * must be handled by the parser without returning {@code null}.
     */
    @Test
    void extractCpuPower_withWhitespace_returnsValue() {
        assertEquals(10.0, JoularCoreHttpSource.extractCpuPower("{ \"cpu_power\" : 10.0 }"), 1e-9);
    }

    /**
     * A negative power reading is physically invalid and must be rejected
     * by returning {@code null}. The guard is {@code value < 0}.
     */
    @Test
    void extractCpuPower_negativeValue_returnsNull() {
        assertNull(JoularCoreHttpSource.extractCpuPower("{\"cpu_power\":-1.0}"));
    }

    /**
     * Zero watts is a valid (non-negative, finite) power value and must be
     * returned. The guard is strictly {@code < 0}, so exactly zero passes.
     */
    @Test
    void extractCpuPower_zeroValue_returnsZero() {
        assertEquals(0.0, JoularCoreHttpSource.extractCpuPower("{\"cpu_power\":0.0}"), 1e-9);
    }

    /**
     * When the {@code "cpu_power"} key is absent, {@code findKey} returns
     * {@code -1} and {@code extractCpuPower} must propagate that as
     * {@code null}.
     */
    @Test
    void extractCpuPower_missingKey_returnsNull() {
        assertNull(JoularCoreHttpSource.extractCpuPower("{\"other\":1}"));
    }

    /**
     * Reinforces the substring-guard requirement at the higher level: if the
     * JSON only contains {@code "last_cpu_power"} and not a top-level
     * {@code "cpu_power"}, the method must return {@code null}.
     */
    @Test
    void extractCpuPower_substringKeyOnly_returnsNull() {
        assertNull(JoularCoreHttpSource.extractCpuPower("{\"last_cpu_power\":5.0}"));
    }

    /**
     * Scientific notation (lowercase {@code e}) is a standard JSON number
     * format and must be parsed correctly by {@code Double.parseDouble}.
     * {@code 1.5e1} equals {@code 15.0}.
     */
    @Test
    void extractCpuPower_scientificNotation_parsed() {
        assertEquals(15.0, JoularCoreHttpSource.extractCpuPower("{\"cpu_power\":1.5e1}"), 1e-9);
    }

    /**
     * Scientific notation with an uppercase {@code E} must also be accepted.
     * {@code 2E2} equals {@code 200.0}.
     */
    @Test
    void extractCpuPower_scientificNotationUppercase_parsed() {
        assertEquals(200.0, JoularCoreHttpSource.extractCpuPower("{\"cpu_power\":2E2}"), 1e-9);
    }

    /**
     * When the value associated with {@code "cpu_power"} is a quoted string
     * rather than a number, the first character after the colon is {@code "}
     * which does not match any character in the numeric scanning loop. The
     * loop terminates immediately with {@code start == i}, and the method
     * must return {@code null}.
     */
    @Test
    void extractCpuPower_nonNumericValue_returnsNull() {
        // The character after ':' is '"' — not a digit, sign, dot, or exponent marker.
        assertNull(JoularCoreHttpSource.extractCpuPower("{\"cpu_power\":\"text\"}"));
    }

    /**
     * When the JSON object contains multiple power fields, only the value
     * associated with the structurally correct {@code "cpu_power"} key must
     * be returned; other fields must be ignored.
     */
    @Test
    void extractCpuPower_multipleKeys_correctKeyReturned() {
        assertEquals(7.0, JoularCoreHttpSource.extractCpuPower(
                "{\"gpu_power\":3.0,\"cpu_power\":7.0,\"total\":10.0}"), 1e-9);
    }
}
