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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

public class JoularCoreHttpSource implements PowerSource {

    private static final Logger logger = Logger.getLogger(JoularCoreHttpSource.class.getName());
    private final String url;
    private final HttpClient httpClient;

    public JoularCoreHttpSource(String url) {
        this.url = url;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @Override
    public void initialize() throws Exception {
        logger.log(Level.INFO, () -> "Initializing HTTP source: " + url);
    }

    @Override
    public double getCurrentPower() {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(1)).GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return extractCpuPower(response.body());
            } else {
                logger.log(Level.FINE, () -> "HTTP data fetch failed with status code: " + response.statusCode());
            }
        } catch (Exception e) {
            logger.log(Level.FINE, "Error fetching HTTP power data", e);
        }
        return 0;
    }

    private static double extractCpuPower(String json) {
        int keyPos = json.indexOf("\"cpu_power\"");
        if (keyPos == -1) {
            return 0.0;
        }

        int colonPos = json.indexOf(':', keyPos);
        if (colonPos == -1) {
            return 0.0;
        }

        int i = colonPos + 1;

        // skip whitespace
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }

        int start = i;

        while (i < json.length()) {
            char c = json.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == '-' || c == 'e' || c == 'E') {
                i++;
            } else {
                break;
            }
        }

        try {
            return Double.parseDouble(json.substring(start, i));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    @Override
    public void close() {
    }
}