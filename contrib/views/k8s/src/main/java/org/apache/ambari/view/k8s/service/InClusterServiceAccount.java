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

package org.apache.ambari.view.k8s.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.fabric8.kubernetes.client.OAuthTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * The ServiceAccount of the pod Ambari runs in, as Kubernetes hands it to the pod: a token, the cluster's CA and the
 * pod's namespace mounted under {@link #DEFAULT_MOUNT}, and the API server's address in the environment. KDPS connects
 * with it when Ambari runs in the cluster it manages (the ambari chart) and an administrator chose this connection:
 * there is then no kubeconfig to upload and no OpenShift password to keep, which an OpenShift whose console only
 * signs in through SSO cannot provide anyway.
 *
 * <p>These are the files and variables fabric8's in-cluster auto-configuration reads. KDPS reads them itself because
 * Helm needs a kubeconfig, and because the auto-configuration would also take a {@code ~/.kube/config} or the
 * {@code kubernetes.*} system properties the view sets from its own properties, and connect with something other
 * than the pod's ServiceAccount.</p>
 *
 * <p>The token is a projected one: the kubelet writes a new one before the current one expires. It is therefore read
 * from the file on every use, by the client as its {@link OAuthTokenProvider} and for every kubeconfig handed to
 * Helm, and never kept from the time KDPS connected.</p>
 */
public class InClusterServiceAccount implements OAuthTokenProvider {

    private static final Logger LOG = LoggerFactory.getLogger(InClusterServiceAccount.class);

    /** Where Kubernetes mounts the ServiceAccount token, CA certificate and namespace of a pod. */
    public static final Path DEFAULT_MOUNT = Paths.get("/var/run/secrets/kubernetes.io/serviceaccount");
    static final String SERVICE_HOST_ENV = "KUBERNETES_SERVICE_HOST";
    static final String SERVICE_PORT_ENV = "KUBERNETES_SERVICE_PORT";
    /** The user name Kubernetes gives a ServiceAccount: {@code system:serviceaccount:<namespace>:<name>}. */
    static final String SERVICE_ACCOUNT_USER_PREFIX = "system:serviceaccount:";
    /** Name of the cluster, user and context of the kubeconfig {@link #kubeconfig()} writes. */
    static final String KUBECONFIG_NAME = "in-cluster";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .disable(YAMLGenerator.Feature.SPLIT_LINES)
            .build());

    private final Path mount;
    private final UnaryOperator<String> environment;
    /** The last token read, answered while the file cannot be read for a moment. */
    private volatile String lastToken;

    /**
     * The ServiceAccount of the pod this JVM runs in, when it runs in one.
     *
     * @return the pod's ServiceAccount, read from the standard mount and environment
     */
    public static InClusterServiceAccount ofThisPod() {
        return new InClusterServiceAccount(DEFAULT_MOUNT, System::getenv);
    }

    /**
     * @param mount       the directory holding {@code token}, {@code ca.crt} and {@code namespace}
     * @param environment reads an environment variable ({@link #SERVICE_HOST_ENV}, {@link #SERVICE_PORT_ENV})
     */
    public InClusterServiceAccount(Path mount, UnaryOperator<String> environment) {
        this.mount = Objects.requireNonNull(mount, "mount");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /** Who KDPS acts as with this ServiceAccount; a field is {@code null} when the token does not tell. */
    public record Identity(String username, String namespace, String name) { }

    /**
     * Why KDPS cannot connect with this ServiceAccount, for the administrator who asked to.
     *
     * @return the reason, or {@code null} when the API server address, a token and the CA certificate are all there
     */
    public String unavailableReason() {
        if (isBlank(environment.apply(SERVICE_HOST_ENV)) || isBlank(environment.apply(SERVICE_PORT_ENV))) {
            return "Ambari is not running in a Kubernetes or OpenShift pod: the cluster's API server address "
                    + "(" + SERVICE_HOST_ENV + " and " + SERVICE_PORT_ENV + ") is not set in its environment.";
        }
        if (!Files.isReadable(tokenFile())) {
            return "The Ambari pod has no ServiceAccount token at " + tokenFile() + ". With the ambari chart, set "
                    + "inClusterServiceAccount.enabled=true so that Kubernetes mounts it.";
        }
        if (isBlank(getToken())) {
            return "The ServiceAccount token at " + tokenFile() + " cannot be read or is empty.";
        }
        if (!Files.isReadable(caFile())) {
            return "The Ambari pod has no cluster CA certificate at " + caFile() + ".";
        }
        return null;
    }

    /** @return whether KDPS can connect with this ServiceAccount ({@link #unavailableReason()} is {@code null}) */
    public boolean isAvailable() {
        return unavailableReason() == null;
    }

    /**
     * The token as the kubelet keeps it now. Implements {@link OAuthTokenProvider}: the client asks for it before
     * every request, and once more after a refusal, so a renewed token is used as soon as the kubelet writes it.
     *
     * @return the token, the last one read when the file cannot be read now, or {@code null} when none was ever read
     */
    @Override
    public String getToken() {
        try {
            String token = Files.readString(tokenFile(), StandardCharsets.UTF_8).trim();
            if (!token.isEmpty()) {
                lastToken = token;
                return token;
            }
            LOG.warn("The ServiceAccount token file {} is empty", tokenFile());
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read the ServiceAccount token {}: {}", tokenFile(), e.toString());
        }
        return lastToken;
    }

    /**
     * The address of the cluster's API server, as the kubelet gives it to every pod.
     *
     * @return {@code https://<host>:<port>}, an IPv6 host in brackets
     * @throws IllegalStateException when the environment does not give it
     */
    public String apiServerUrl() {
        String host = environment.apply(SERVICE_HOST_ENV);
        String port = environment.apply(SERVICE_PORT_ENV);
        if (isBlank(host) || isBlank(port)) {
            throw new IllegalStateException(unavailableReason());
        }
        host = host.trim();
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return "https://" + host + ":" + port.trim();
    }

    /**
     * The namespace of the pod, which the kubeconfig {@link #kubeconfig()} writes uses by default.
     *
     * @return the namespace, or {@code null} when the pod has none mounted
     */
    public String namespace() {
        try {
            String namespace = Files.readString(mount.resolve("namespace"), StandardCharsets.UTF_8).trim();
            return namespace.isEmpty() ? null : namespace;
        } catch (IOException | RuntimeException e) {
            LOG.debug("No pod namespace at {}: {}", mount.resolve("namespace"), e.toString());
            return null;
        }
    }

    /**
     * Who the API server takes requests made with the token for, read from the token's claims: Kubernetes signs
     * ServiceAccount tokens as JWTs whose subject is the ServiceAccount's user name. Nothing is verified here; the API
     * server does that on every request.
     *
     * @return the identity; its fields are {@code null} when the token is missing or does not tell
     */
    public Identity identity() {
        Map<String, Object> claims = claims(getToken());
        String namespace = null;
        String name = null;
        String subject = text(claims.get("sub"));
        if (subject != null && subject.startsWith(SERVICE_ACCOUNT_USER_PREFIX)) {
            String[] parts = subject.substring(SERVICE_ACCOUNT_USER_PREFIX.length()).split(":", 2);
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                namespace = parts[0];
                name = parts[1];
            }
        }
        // Bound (projected) tokens also carry them under "kubernetes.io"; the legacy Secret-based ones flat.
        if (name == null && claims.get("kubernetes.io") instanceof Map<?, ?> kubernetes) {
            namespace = text(kubernetes.get("namespace"));
            if (kubernetes.get("serviceaccount") instanceof Map<?, ?> serviceAccount) {
                name = text(serviceAccount.get("name"));
            }
        }
        if (name == null) {
            namespace = text(claims.get("kubernetes.io/serviceaccount/namespace"));
            name = text(claims.get("kubernetes.io/serviceaccount/service-account.name"));
        }
        if (namespace == null) {
            namespace = namespace();
        }
        String username = name != null && namespace != null ? SERVICE_ACCOUNT_USER_PREFIX + namespace + ":" + name : null;
        return new Identity(username, namespace, name);
    }

    /**
     * A kubeconfig that connects with this ServiceAccount, for Helm and for everything else that takes one. It holds
     * the token of the moment, so it is written again for each use rather than kept.
     *
     * @return the kubeconfig as YAML, with a single context named {@value #KUBECONFIG_NAME}
     * @throws IllegalStateException when KDPS cannot connect with this ServiceAccount ({@link #unavailableReason()})
     */
    public String kubeconfig() {
        String unavailable = unavailableReason();
        if (unavailable != null) {
            throw new IllegalStateException(unavailable);
        }
        String caData;
        try {
            caData = Base64.getEncoder().encodeToString(Files.readAllBytes(caFile()));
        } catch (IOException e) {
            throw new IllegalStateException("The cluster CA certificate at " + caFile() + " cannot be read: " + e.getMessage(), e);
        }
        Map<String, Object> cluster = new LinkedHashMap<>();
        cluster.put("server", apiServerUrl());
        cluster.put("certificate-authority-data", caData);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("cluster", KUBECONFIG_NAME);
        context.put("user", KUBECONFIG_NAME);
        String namespace = namespace();
        if (namespace != null) {
            context.put("namespace", namespace);
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("apiVersion", "v1");
        config.put("kind", "Config");
        config.put("clusters", List.of(Map.of("name", KUBECONFIG_NAME, "cluster", cluster)));
        config.put("users", List.of(Map.of("name", KUBECONFIG_NAME, "user", Map.of("token", getToken()))));
        config.put("contexts", List.of(Map.of("name", KUBECONFIG_NAME, "context", context)));
        config.put("current-context", KUBECONFIG_NAME);
        try {
            return YAML.writeValueAsString(config);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the in-cluster kubeconfig: " + e.getMessage(), e);
        }
    }

    private Path tokenFile() {
        return mount.resolve("token");
    }

    private Path caFile() {
        return mount.resolve("ca.crt");
    }

    /** The claims of a JWT, or an empty map when it is not one. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> claims(String jwt) {
        if (jwt == null) {
            return Map.of();
        }
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return Map.of();
        }
        try {
            Object claims = JSON.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
            return claims instanceof Map<?, ?> ? (Map<String, Object>) claims : Map.of();
        } catch (IOException | IllegalArgumentException e) {
            // Never log the token itself.
            LOG.debug("The ServiceAccount token is not a JWT whose claims can be read: {}", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
