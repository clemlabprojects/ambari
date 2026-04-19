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

import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Thin HTTP proxy that forwards REST calls from the Ambari view backend to
 * the Python FastAPI semantic service.  Keeps the Java layer stateless – all
 * business logic lives in Python.
 *
 * <p>Uses a shared OkHttpClient with connection pooling and configurable
 * timeouts so concurrent UI requests are handled efficiently.
 */
public class SemanticServiceProxy {

    private static final Logger LOG = LoggerFactory.getLogger(SemanticServiceProxy.class);

    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.parse("application/json; charset=utf-8");

    private final String serviceBaseUrl;
    private final OkHttpClient httpClient;

    public SemanticServiceProxy(String serviceBaseUrl, int connectTimeoutSeconds,
                                int readTimeoutSeconds) {
        this.serviceBaseUrl = serviceBaseUrl.endsWith("/")
                ? serviceBaseUrl.substring(0, serviceBaseUrl.length() - 1)
                : serviceBaseUrl;

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(10, 5, TimeUnit.MINUTES))
                .build();
    }

    // ── GET ──────────────────────────────────────────────────────────────────

    public String get(String path, Map<String, String> queryParams, String requestId) {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(serviceBaseUrl + path).newBuilder();
        if (queryParams != null) {
            queryParams.forEach(urlBuilder::addQueryParameter);
        }
        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .header("X-Request-ID", requestId != null ? requestId : "")
                .get()
                .build();
        return execute(request);
    }

    // ── POST ─────────────────────────────────────────────────────────────────

    public String post(String path, String jsonBody, String requestId) {
        RequestBody body = RequestBody.create(
                jsonBody != null ? jsonBody : "{}", JSON_MEDIA_TYPE
        );
        Request request = new Request.Builder()
                .url(serviceBaseUrl + path)
                .header("X-Request-ID", requestId != null ? requestId : "")
                .post(body)
                .build();
        return execute(request);
    }

    // ── DELETE ───────────────────────────────────────────────────────────────

    public String delete(String path, String requestId) {
        Request request = new Request.Builder()
                .url(serviceBaseUrl + path)
                .header("X-Request-ID", requestId != null ? requestId : "")
                .delete()
                .build();
        return execute(request);
    }

    // ── internal ─────────────────────────────────────────────────────────────

    private String execute(Request request) {
        try (okhttp3.Response resp = httpClient.newCall(request).execute()) {
            String body = resp.body() != null ? resp.body().string() : "{}";
            if (!resp.isSuccessful()) {
                LOG.warn("Semantic service returned HTTP {} for {}: {}",
                        resp.code(), request.url(), body);
                throw new WebApplicationException(
                        Response.status(resp.code()).entity(body).build()
                );
            }
            return body;
        } catch (IOException e) {
            LOG.error("Semantic service unreachable at {}: {}", serviceBaseUrl, e.getMessage());
            throw new WebApplicationException(
                    Response.status(502)
                            .entity("{\"error\":\"Semantic service unavailable: " + e.getMessage() + "\"}")
                            .build()
            );
        }
    }
}
