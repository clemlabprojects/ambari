/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.view.sqlassistant.service;

import org.apache.ambari.view.ViewContext;

/**
 * Reads view instance parameters from Ambari's ViewContext.
 * Centralises all configuration key access so other classes reference
 * constants rather than raw strings.
 */
public class ViewConfigurationService {

    // ── View parameter keys (must match view.xml <parameter> names) ──────────

    public static final String PARAM_SEMANTIC_SERVICE_URL  = "sql.assistant.semantic.service.url";
    public static final String PARAM_CONNECT_TIMEOUT       = "sql.assistant.connect.timeout.seconds";
    public static final String PARAM_READ_TIMEOUT          = "sql.assistant.read.timeout.seconds";
    public static final String PARAM_WORKING_DIR           = "sql.assistant.working.dir";
    public static final String PARAM_JWT_COOKIE_NAME       = "sql.assistant.jwt.cookie.name";

    // ── Defaults ─────────────────────────────────────────────────────────────

    private static final String DEFAULT_SERVICE_URL        = "http://localhost:8090";
    private static final int    DEFAULT_CONNECT_TIMEOUT    = 10;
    private static final int    DEFAULT_READ_TIMEOUT       = 180;
    private static final String DEFAULT_JWT_COOKIE_NAME    = "hadoop-jwt";

    private final ViewContext viewContext;

    /**
     * Constructs a new configuration service backed by the given Ambari view context.
     *
     * @param viewContext the Ambari {@link ViewContext} for the current view instance,
     *                    used to read view-level parameters
     */
    public ViewConfigurationService(ViewContext viewContext) {
        this.viewContext = viewContext;
    }

    public String getSemanticServiceUrl() {
        return getParam(PARAM_SEMANTIC_SERVICE_URL, DEFAULT_SERVICE_URL);
    }

    public int getConnectTimeout() {
        return getIntParam(PARAM_CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT);
    }

    public int getReadTimeout() {
        return getIntParam(PARAM_READ_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    /**
     * Returns the name of the HTTP cookie that carries the Ambari JWT token.
     * Defaults to {@value #DEFAULT_JWT_COOKIE_NAME}, matching the standard Ambari SSO cookie.
     * Override via the {@value #PARAM_JWT_COOKIE_NAME} view parameter when a custom cookie
     * name has been configured in {@code ambari.sso.jwt.cookieName}.
     *
     * @return the JWT cookie name; never {@code null}
     */
    public String getJwtCookieName() {
        return getParam(PARAM_JWT_COOKIE_NAME, DEFAULT_JWT_COOKIE_NAME);
    }

    public String getWorkingDir() {
        return getParam(PARAM_WORKING_DIR,
                "/var/lib/ambari-server/resources/views/work/SQL-ASSISTANT-VIEW{1.0.0.0}");
    }

    /**
     * Returns {@code true} if the view instance has been configured with a
     * non-default semantic service URL, indicating that the view is ready to use.
     * Returns {@code false} when the URL is absent or still set to the
     * {@value #DEFAULT_SERVICE_URL} placeholder.
     *
     * @return {@code true} if a custom semantic service URL has been configured
     */
    public boolean isConfigured() {
        String url = getSemanticServiceUrl();
        return url != null && !url.isEmpty() && !url.equals(DEFAULT_SERVICE_URL);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String getParam(String key, String defaultValue) {
        try {
            String val = viewContext.getProperties().get(key);
            return (val != null && !val.isBlank()) ? val : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private int getIntParam(String key, int defaultValue) {
        try {
            String val = viewContext.getProperties().get(key);
            return (val != null && !val.isBlank()) ? Integer.parseInt(val.trim()) : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
