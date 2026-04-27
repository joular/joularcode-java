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

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public class JoularCoreHttpSource implements PowerSource {

    private static final Logger logger = Logger.getLogger(JoularCoreHttpSource.class.getName());
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final URI uri;
    private final HttpClient httpClient;
    private final ExecutorService httpExecutor;
    private double lastKnownPower = 0.0;

    public JoularCoreHttpSource(String url) {
        this.uri = parseAndValidate(url);
        this.httpExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Joular-Http-Source");
            t.setDaemon(true);
            return t;
        });
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .executor(httpExecutor)
                .build();
    }

    private static URI parseAndValidate(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("joular-core-http-url is empty");
        }
        URI parsed;
        try {
            parsed = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid joular-core-http-url: " + url, e);
        }
        String scheme = parsed.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException(
                    "joular-core-http-url must use http or https scheme: " + url);
        }
        String lower = scheme.toLowerCase(Locale.ROOT);
        if (!lower.equals("http") && !lower.equals("https")) {
            throw new IllegalArgumentException(
                    "joular-core-http-url must use http or https scheme, got '" + scheme + "': " + url);
        }
        if (parsed.getHost() == null || parsed.getHost().isEmpty()) {
            throw new IllegalArgumentException("joular-core-http-url is missing host: " + url);
        }
        return parsed;
    }

    @Override
    public void initialize() throws Exception {
        logger.log(Level.INFO, () -> "Initializing HTTP source: " + uri);
    }

    @Override
    public double getCurrentPower() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(1))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                logger.log(Level.FINE, () -> "HTTP data fetch failed with status code: " + response.statusCode());
                return lastKnownPower;
            }

            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > MAX_BODY_BYTES) {
                logger.log(Level.WARNING,
                        () -> "HTTP response Content-Length " + contentLength
                                + " exceeds " + MAX_BODY_BYTES + " bytes; ignoring.");
                return lastKnownPower;
            }

            byte[] bytes;
            try (InputStream body = response.body()) {
                bytes = body.readNBytes(MAX_BODY_BYTES + 1);
            }
            if (bytes.length > MAX_BODY_BYTES) {
                logger.log(Level.WARNING,
                        () -> "HTTP response exceeded " + MAX_BODY_BYTES + " bytes; ignoring.");
                return lastKnownPower;
            }

            Double parsed = extractCpuPower(new String(bytes, StandardCharsets.UTF_8));
            if (parsed != null) {
                lastKnownPower = parsed;
            }
            return lastKnownPower;
        } catch (Exception e) {
            logger.log(Level.FINE, "Error fetching HTTP power data", e);
            return lastKnownPower;
        }
    }

    static Double extractCpuPower(String json) {
        int keyPos = findKey(json, "cpu_power");
        if (keyPos == -1) {
            return null;
        }

        int colonPos = json.indexOf(':', keyPos);
        if (colonPos == -1) {
            return null;
        }

        int i = colonPos + 1;

        // skip whitespace
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }

        int start = i;

        while (i < json.length()) {
            char c = json.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E') {
                i++;
            } else {
                break;
            }
        }

        if (start == i) {
            return null;
        }

        try {
            double value = Double.parseDouble(json.substring(start, i));
            if (!Double.isFinite(value) || value < 0) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Find a JSON key occurrence where the character preceding the opening quote
    // is '{' or ',' (possibly with whitespace in between), so that keys like
    // "last_cpu_power" do not match when searching for "cpu_power".
    static int findKey(String json, String key) {
        String quoted = "\"" + key + "\"";
        int from = 0;
        while (from < json.length()) {
            int pos = json.indexOf(quoted, from);
            if (pos == -1) {
                return -1;
            }
            int j = pos - 1;
            while (j >= 0 && Character.isWhitespace(json.charAt(j))) {
                j--;
            }
            if (j < 0 || json.charAt(j) == '{' || json.charAt(j) == ',') {
                return pos;
            }
            from = pos + quoted.length();
        }
        return -1;
    }

    @Override
    public void close() {
        httpExecutor.shutdownNow();
    }
}
