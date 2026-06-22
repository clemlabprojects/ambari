/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.view.k8s.service.om;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Mints the OpenMetadata ingestion-bot's JWT with {@code JWTTokenExpiry: "Unlimited"}
 * and returns the new token. Used by both the Ranger TagSync source registration
 * ({@code OM_RANGER_TAGSYNC_REGISTER}) and the Atlas federation registration
 * ({@code OM_ATLAS_FEDERATION_REGISTER}) Ambari operations — they both need a
 * non-expiring JWT to call OM REST as the bot.
 *
 * <p><b>Why exec-in-pod and not JDBC?</b>
 * OM's bot JWT is stored Fernet-encrypted in {@code user_entity.json} in OM's
 * PostgreSQL. The Ambari server runs outside the Kubernetes cluster and has no
 * direct route to the in-cluster PostgreSQL ClusterIP. Three approaches were
 * considered:
 * <ol>
 *   <li>Port-forward + JDBC + Fernet-Java decryption in the Ambari process.
 *       Cleaner in theory but adds two new failure modes (port-forward stream
 *       interruption, JDBC reconnect handling).</li>
 *   <li>Reach OM REST through the ingress URL, but OM exposes no endpoint to
 *       read the bot's stored JWT — only to rotate it once you have a valid
 *       admin/bot JWT to authenticate the call.</li>
 *   <li>Exec into a pod that already has the postgres password + Fernet key
 *       mounted (the airflow scheduler pod ships with {@code python3},
 *       {@code psql}, and {@code cryptography} preinstalled in our bigtop image).
 *       The pod's local network already routes to the in-cluster postgres.</li>
 * </ol>
 *
 * We picked (3). The exec script is small, the dependency is on a pod that
 * always exists when {@code dependencies.enabled=true} (and the operations
 * this client supports are only relevant in that mode anyway).
 */
public final class OmBotJwtClient {

    private static final Logger LOG = LoggerFactory.getLogger(OmBotJwtClient.class);

    /** Label selector used to find a pod with python3 + cryptography + psql. */
    private static final String EXEC_POD_SELECTOR_KEY   = "component";
    private static final String EXEC_POD_SELECTOR_VALUE = "scheduler";

    /** Fallback Fernet key when {@code <release>-fernet} Secret is absent. Matches
     *  the OpenMetadata helm chart's default fernet-key value. */
    public static final String DEFAULT_FERNET_KEY = "jJ/9sz0g0OHxsfxOoSfdFdmk3ysNmPRnH3TUAbz3IHA=";

    /**
     * Resolves the new Unlimited JWT for the {@code ingestion-bot} user in OM.
     * The flow runs entirely inside an airflow scheduler pod via K8s exec:
     * <ol>
     *   <li>Read encrypted JWT from {@code user_entity.json} in OM PostgreSQL</li>
     *   <li>Strip the {@code fernet:} prefix, Fernet-decrypt with the chart's
     *       Fernet key to get the current (short-lived) JWT</li>
     *   <li>PUT {@code /api/v1/users/security/token} on OM REST with
     *       {@code JWTTokenExpiry: "Unlimited"} and the current JWT as Bearer.
     *       OM regenerates the JWT and returns the new value.</li>
     *   <li>Print the new JWT to stdout (captured by the exec stream)</li>
     * </ol>
     *
     * The script writes warnings to stderr so the caller's log gets diagnostics
     * even when the result is consumed verbatim.
     *
     * @param client K8s client the view holds for the deploy
     * @param namespace release namespace
     * @param release helm release name (matches OM pod name prefixes)
     * @param fernetKey Fernet key string (32-byte URL-safe base64); falls back to
     *                  {@link #DEFAULT_FERNET_KEY} when null/blank
     * @param timeout how long to wait for the exec to complete
     * @return the new Unlimited-TTL JWT
     * @throws IllegalStateException on any failure — message includes captured stderr
     */
    public static String mintUnlimitedJwt(KubernetesClient client,
                                          String namespace,
                                          String release,
                                          String fernetKey,
                                          java.time.Duration timeout) throws Exception {
        return mintUnlimitedJwt(client, namespace, release, fernetKey, null, null, timeout);
    }

    /**
     * Same as {@link #mintUnlimitedJwt(KubernetesClient, String, String, String, java.time.Duration)}
     * but with explicit OM service host + port — used when the chart's OM Service
     * doesn't follow the {@code <release>-openmetadata} convention. Resolved from
     * the {@code ranger-tagsync-settings.om_service_template} +
     * {@code om_port} fields.
     */
    public static String mintUnlimitedJwt(KubernetesClient client,
                                          String namespace,
                                          String release,
                                          String fernetKey,
                                          String omServiceName,
                                          Integer omPort,
                                          java.time.Duration timeout) throws Exception {
        if (client == null) throw new IllegalArgumentException("client is required");
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("namespace is required");
        if (release == null || release.isBlank()) throw new IllegalArgumentException("release is required");
        String key = (fernetKey == null || fernetKey.isBlank()) ? DEFAULT_FERNET_KEY : fernetKey;
        // OM Service name + port — settings-driven, defaulting to release name on port 8585.
        String svc = (omServiceName == null || omServiceName.isBlank()) ? release : omServiceName;
        int port = omPort == null ? 8585 : omPort;

        // Find an airflow scheduler pod (the upstream chart labels them
        // tier=airflow, component=scheduler). Restrict by release prefix so we
        // don't accidentally exec into another OM deploy in the same namespace.
        List<Pod> pods = client.pods().inNamespace(namespace)
                .withLabel(EXEC_POD_SELECTOR_KEY, EXEC_POD_SELECTOR_VALUE)
                .list().getItems();
        Pod target = pods.stream()
                .filter(p -> p.getMetadata() != null && p.getMetadata().getName() != null)
                .filter(p -> p.getMetadata().getName().startsWith(release + "-airflow-scheduler"))
                .filter(p -> "Running".equalsIgnoreCase(p.getStatus().getPhase()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Could not find a Running airflow scheduler pod for release '" + release
                                + "' in namespace '" + namespace + "'. Make sure dependencies.enabled=true and the deploy reached the scheduler-ready phase."));

        String podName = target.getMetadata().getName();
        LOG.info("OmBotJwtClient: exec into pod '{}' to mint Unlimited bot JWT", podName);

        // Build the exec script. PGPASSWORD comes from /var/run/secrets/postgresql
        // OR from an env var the airflow container already has (the OM-deps chart
        // mounts the password via the airflow DB connection Secret). The script
        // falls back to the standard env-var locations.
        String script =
            "set -eu\n" +
            "python3 - <<'PY'\n" +
            "import os, sys, re, json, subprocess, urllib.request, ssl\n" +
            "from cryptography.fernet import Fernet\n" +
            "# 1. Read the encrypted JWT from postgres. We use the OM main postgres,\n" +
            "#    not airflow_db; the scheduler pod's env points at airflow_db, so\n" +
            "#    override host + db here using the OM convention <release>-postgresql\n" +
            "#    + openmetadata_db.\n" +
            "release = os.environ['OM_RELEASE']\n" +
            "pghost = release + '-postgresql'\n" +
            "pgport = '5432'\n" +
            "pguser = 'openmetadata_user'\n" +
            "# Password is mounted by the airflow chart for its own db; same Secret\n" +
            "# (the OM postgres Secret) holds the password we want.\n" +
            "pgpass = open('/opt/airflow/secrets/postgresql-password').read().strip() if os.path.exists('/opt/airflow/secrets/postgresql-password') else os.environ.get('OM_POSTGRES_PASSWORD','openmetadata')\n" +
            "q = \"SELECT json->'authenticationMechanism'->'config'->>'JWTToken' FROM user_entity WHERE name='ingestion-bot'\"\n" +
            "raw = subprocess.check_output(['psql','-h',pghost,'-p',pgport,'-U',pguser,'-d','openmetadata_db','-tA','-c',q],\n" +
            "    env={**os.environ,'PGPASSWORD':pgpass}).decode().strip()\n" +
            "if not raw:\n" +
            "    print('ingestion-bot has no JWT yet', file=sys.stderr); sys.exit(1)\n" +
            "ct = re.sub(r'^fernet:', '', raw)\n" +
            "jwt = Fernet(os.environ['OM_FERNET_KEY'].encode()).decrypt(ct.encode()).decode()\n" +
            "# 2. Rotate to Unlimited via OM REST. The service name + port come\n" +
            "#    from ranger-tagsync-settings (chart-specific); the OM main\n" +
            "#    chart names its Service `<release>` (not `<release>-openmetadata`),\n" +
            "#    so the default templating yields the right hostname.\n" +
            "om = 'http://' + os.environ['OM_SVC_HOST'] + '.' + os.environ['OM_NAMESPACE'] + ':' + os.environ['OM_PORT']\n" +
            "req = urllib.request.Request(om + '/api/v1/users/security/token',\n" +
            "    data=json.dumps({'JWTTokenExpiry': 'Unlimited'}).encode(),\n" +
            "    headers={'Authorization': 'Bearer ' + jwt, 'Content-Type': 'application/json'},\n" +
            "    method='PUT')\n" +
            "try:\n" +
            "    resp = urllib.request.urlopen(req, timeout=20)\n" +
            "    body = json.loads(resp.read())\n" +
            "    new_jwt = body.get('JWTToken') or jwt\n" +
            "    print(new_jwt)\n" +
            "except Exception as e:\n" +
            "    print('JWT rotate via PUT failed: ' + str(e) + ' — falling back to PATCH', file=sys.stderr)\n" +
            "    # Fallback: PATCH the user entity directly with the new TTL.\n" +
            "    req = urllib.request.Request(om + '/api/v1/users/name/ingestion-bot',\n" +
            "        headers={'Authorization': 'Bearer ' + jwt}, method='GET')\n" +
            "    bot = json.loads(urllib.request.urlopen(req, timeout=10).read())\n" +
            "    bot_id = bot['id']\n" +
            "    patch = [{'op':'replace','path':'/authenticationMechanism/config/JWTTokenExpiry','value':'Unlimited'}]\n" +
            "    req = urllib.request.Request(om + '/api/v1/users/' + bot_id,\n" +
            "        data=json.dumps(patch).encode(),\n" +
            "        headers={'Authorization': 'Bearer ' + jwt,'Content-Type': 'application/json-patch+json'},\n" +
            "        method='PATCH')\n" +
            "    urllib.request.urlopen(req, timeout=15)\n" +
            "    # Re-read the regenerated JWT from postgres\n" +
            "    raw2 = subprocess.check_output(['psql','-h',pghost,'-p',pgport,'-U',pguser,'-d','openmetadata_db','-tA','-c',q],\n" +
            "        env={**os.environ,'PGPASSWORD':pgpass}).decode().strip()\n" +
            "    ct2 = re.sub(r'^fernet:', '', raw2)\n" +
            "    print(Fernet(os.environ['OM_FERNET_KEY'].encode()).decrypt(ct2.encode()).decode())\n" +
            "PY\n";

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CountDownLatch done = new CountDownLatch(1);
        long timeoutMs = timeout == null ? 90_000L : timeout.toMillis();

        try (ExecWatch watch = client.pods().inNamespace(namespace).withName(podName)
                .inContainer("scheduler")
                .writingOutput(stdout)
                .writingError(stderr)
                .usingListener(new ExecListener() {
                    @Override public void onClose(int code, String reason) { done.countDown(); }
                    @Override public void onFailure(Throwable t, Response r) { done.countDown(); }
                })
                .exec("bash", "-c",
                        "OM_RELEASE=" + shellQuote(release) +
                        " OM_NAMESPACE=" + shellQuote(namespace) +
                        " OM_FERNET_KEY=" + shellQuote(key) +
                        " OM_SVC_HOST=" + shellQuote(svc) +
                        " OM_PORT=" + shellQuote(String.valueOf(port)) +
                        " bash -c " + shellQuote(script))) {
            boolean finished = done.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                throw new IllegalStateException(
                        "OmBotJwtClient: timed out after " + timeoutMs + "ms waiting for exec to complete. "
                                + "stderr=" + stderr.toString(StandardCharsets.UTF_8));
            }
        }

        String out = stdout.toString(StandardCharsets.UTF_8).trim();
        if (out.isEmpty()) {
            throw new IllegalStateException(
                    "OmBotJwtClient: exec produced no JWT on stdout. stderr=" + stderr.toString(StandardCharsets.UTF_8));
        }
        // The new JWT is the LAST line of stdout (the script also writes
        // warnings via prints before the final result on the fallback path).
        String[] lines = out.split("\\R");
        String jwt = lines[lines.length - 1].trim();
        if (!jwt.startsWith("ey")) {
            // JWTs are base64-encoded JSON; they always start with the b64
            // representation of `{"alg"...` which begins with 'ey'. If we don't
            // see that, something other than a JWT came back.
            throw new IllegalStateException(
                    "OmBotJwtClient: last line of stdout doesn't look like a JWT: '"
                            + (jwt.length() > 40 ? jwt.substring(0, 40) + "..." : jwt) + "'. Full stderr: "
                            + stderr.toString(StandardCharsets.UTF_8));
        }
        LOG.info("OmBotJwtClient: minted Unlimited-TTL JWT (length={}) for OM bot in '{}'/'{}'",
                jwt.length(), namespace, release);
        return jwt;
    }

    /** Single-quote a string for safe interpolation inside {@code bash -c '...'}. */
    static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private OmBotJwtClient() { /* utility */ }
}
