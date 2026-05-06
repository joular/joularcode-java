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

import java.io.ByteArrayInputStream;
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
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;

public class JoularCoreHttpSource implements PowerSource {

    private static final Logger logger = Logger.getLogger(JoularCoreHttpSource.class.getName());
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();

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

            Double parsed = extractCpuPower(bytes);
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
        if (json == null) {
            return null;
        }
        return extractCpuPower(json.getBytes(StandardCharsets.UTF_8));
    }

    static Double extractCpuPower(byte[] json) {
        if (json == null || json.length == 0) {
            return null;
        }

        try (JsonParser parser = JSON_FACTORY.createParser(
                ObjectReadContext.empty(),
                new ByteArrayInputStream(json))) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }

            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String propertyName = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if ("cpu_power".equals(propertyName)) {
                    if (valueToken != JsonToken.VALUE_NUMBER_INT && valueToken != JsonToken.VALUE_NUMBER_FLOAT) {
                        return null;
                    }
                    double value = parser.getDoubleValue();
                    if (!Double.isFinite(value) || value < 0) {
                        return null;
                    }
                    return value;
                }
                parser.skipChildren();
            }
        } catch (Exception e) {
            logger.log(Level.FINE, "Could not parse HTTP power JSON", e);
        }
        return null;
    }

    @Override
    public void close() {
        httpExecutor.shutdownNow();
    }
}
