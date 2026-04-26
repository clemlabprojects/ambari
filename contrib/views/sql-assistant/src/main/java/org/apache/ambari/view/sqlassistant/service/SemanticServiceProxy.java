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
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * Shared OkHttpClient instances keyed by "connectTimeout:readTimeout".
     * OkHttpClient and its ConnectionPool are expensive to construct and are
     * designed to be shared across requests.  Auth headers are injected per-request
     * via addUserContext(), so sharing the base client is safe.
     */
    private static final ConcurrentHashMap<String, OkHttpClient> HTTP_CLIENT_CACHE =
            new ConcurrentHashMap<>();

    private final String serviceBaseUrl;
    private final OkHttpClient httpClient;
    private final String bearerToken;
    private final String ambariUser;

    /**
     * Constructs a proxy configured to communicate with the semantic service at the
     * given base URL using the specified HTTP timeouts.
     *
     * <p>If {@code bearerToken} is non-null it is forwarded as an
     * {@code Authorization: Bearer} header on every outbound request, allowing the
     * Python service (and downstream systems such as Polaris and Trino) to honour
     * the authenticated user's identity instead of falling back to a shared service
     * account.  {@code ambariUser} is forwarded as {@code X-Ambari-User} for audit
     * logging in the Python layer.
     *
     * @param serviceBaseUrl        the base URL of the semantic service
     * @param connectTimeoutSeconds maximum time in seconds to establish a TCP connection
     * @param readTimeoutSeconds    maximum time in seconds to wait for a response body
     * @param bearerToken           user JWT to forward; {@code null} disables forwarding
     * @param ambariUser            Ambari-authenticated username; {@code null} if unknown
     */
    public SemanticServiceProxy(String serviceBaseUrl, int connectTimeoutSeconds,
                                int readTimeoutSeconds, String bearerToken, String ambariUser) {
        this.serviceBaseUrl = serviceBaseUrl.endsWith("/")
                ? serviceBaseUrl.substring(0, serviceBaseUrl.length() - 1)
                : serviceBaseUrl;
        this.bearerToken = bearerToken;
        this.ambariUser  = ambariUser;

        String cacheKey = connectTimeoutSeconds + ":" + readTimeoutSeconds;
        this.httpClient = HTTP_CLIENT_CACHE.computeIfAbsent(cacheKey, k ->
                new OkHttpClient.Builder()
                        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .connectionPool(new ConnectionPool(10, 5, TimeUnit.MINUTES))
                        .build());
    }

    // ── GET ──────────────────────────────────────────────────────────────────

    /**
     * Sends an HTTP GET request to the given path on the semantic service.
     * Optional query parameters are appended to the URL before the request is sent.
     *
     * @param path        the API path to request (e.g. {@code /api/v1/schema/catalogs});
     *                    will be appended to the base URL
     * @param queryParams optional map of query-string key/value pairs; may be {@code null}
     * @param requestId   a correlation ID forwarded as the {@code X-Request-ID} header;
     *                    an empty string is sent when {@code null}
     * @return the response body as a JSON string
     * @throws javax.ws.rs.WebApplicationException if the service returns a non-2xx status
     *         or is unreachable
     */
    public String get(String path, Map<String, String> queryParams, String requestId) {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(serviceBaseUrl + path).newBuilder();
        if (queryParams != null) {
            queryParams.forEach(urlBuilder::addQueryParameter);
        }
        Request request = addUserContext(new Request.Builder()
                .url(urlBuilder.build())
                .header("X-Request-ID", requestId != null ? requestId : ""))
                .get()
                .build();
        return execute(request);
    }

    // ── POST ─────────────────────────────────────────────────────────────────

    /**
     * Sends an HTTP POST request with a JSON body to the given path on the semantic service.
     *
     * @param path      the API path to post to (e.g. {@code /api/v1/nl-to-sql});
     *                  will be appended to the base URL
     * @param jsonBody  the JSON payload to include in the request body;
     *                  an empty JSON object ({@code {}}) is used when {@code null}
     * @param requestId a correlation ID forwarded as the {@code X-Request-ID} header;
     *                  an empty string is sent when {@code null}
     * @return the response body as a JSON string
     * @throws javax.ws.rs.WebApplicationException if the service returns a non-2xx status
     *         or is unreachable
     */
    public String post(String path, String jsonBody, String requestId) {
        RequestBody body = RequestBody.create(
                jsonBody != null ? jsonBody : "{}", JSON_MEDIA_TYPE
        );
        Request request = addUserContext(new Request.Builder()
                .url(serviceBaseUrl + path)
                .header("X-Request-ID", requestId != null ? requestId : ""))
                .post(body)
                .build();
        return execute(request);
    }

    // ── DELETE ───────────────────────────────────────────────────────────────

    /**
     * Sends an HTTP DELETE request to the given path on the semantic service.
     *
     * @param path      the API path to delete (e.g. {@code /api/v1/history/123});
     *                  will be appended to the base URL
     * @param requestId a correlation ID forwarded as the {@code X-Request-ID} header;
     *                  an empty string is sent when {@code null}
     * @return the response body as a JSON string
     * @throws javax.ws.rs.WebApplicationException if the service returns a non-2xx status
     *         or is unreachable
     */
    public String delete(String path, String requestId) {
        Request request = addUserContext(new Request.Builder()
                .url(serviceBaseUrl + path)
                .header("X-Request-ID", requestId != null ? requestId : ""))
                .delete()
                .build();
        return execute(request);
    }

    // ── internal ─────────────────────────────────────────────────────────────

    /** Adds user-identity headers to a request builder when credentials are available. */
    private Request.Builder addUserContext(Request.Builder builder) {
        if (bearerToken != null && !bearerToken.isEmpty()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        if (ambariUser != null && !ambariUser.isEmpty()) {
            builder.header("X-Ambari-User", ambariUser);
        }
        return builder;
    }

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
