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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Reads the OpenMetadata ingestion-bot's current JWT. Used by the Ranger TagSync source
 * registration ({@code OM_RANGER_TAGSYNC_REGISTER}), the Atlas federation registration
 * ({@code OM_ATLAS_FEDERATION_REGISTER}) and the base-ingestion registrations — they all call OM
 * REST as the bot, and Ranger TagSync keeps the token to poll OM.
 *
 * <p><b>Why read, and never mint?</b> OpenMetadata accepts a bot token only when it is the one
 * stored for the bot ("The given token does not match the current bot's token"), so every holder
 * must use that token. Replacing it is reserved to an OpenMetadata administrator
 * ({@code PUT /users/generateToken/{id}}); the bot cannot do it with its own token (a personal
 * access token is refused for bots, and a PATCH of its authentication mechanism is discarded).
 * Asking for a new token on every run therefore never rotated anything. The token's lifetime is
 * the one an administrator chose in OpenMetadata (Settings &gt; Bots); KDPS reports it, warns before
 * it runs out, refuses an expired one, and hands the current token to every consumer, so
 * regenerating it in OpenMetadata and re-running the step is how it is rotated.
 *
 * <p>A holder that keeps the token (Ranger TagSync stores it in its configuration) only gets one
 * that expires (see {@link #neverExpiresWarning}). OpenMetadata creates the bot with a token that never
 * expires, so an administrator regenerates it once with an expiry before Ranger TagSync can use it.
 * The bot is shared: its token is also what every OpenMetadata ingestion pipeline runs with, so
 * every message about its lifetime says that all of them stop when it expires, and how to hand the
 * regenerated token to each ({@link #EXPIRY_IMPACT}, {@link #AFTER_REGENERATE_HINT}).
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
 *   <li>Reach OM REST through the ingress URL, but OM exposes the bot's token only
 *       to an administrator.</li>
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

    /** How long before its expiry a token is reported as due for renewal. */
    public static final Duration RENEWAL_NOTICE = Duration.ofDays(30);

    /** Where an administrator regenerates the token, named in every message that asks for it. */
    public static final String REGENERATE_HINT =
            "Regenerate it in OpenMetadata (Settings > Bots > ingestion-bot), choosing an expiry";

    /**
     * What stops when the token expires. Ranger TagSync is not its only holder: OpenMetadata deploys
     * every ingestion pipeline with the bot's token, the ones KDPS registers included.
     */
    public static final String EXPIRY_IMPACT =
            "The ingestion-bot is the identity of every OpenMetadata ingestion pipeline (the Hive and Trino"
            + " ingestion and the Atlas federation KDPS sets up included) as well as of Ranger TagSync's"
            + " OpenMetadata source: when its token expires, all of them stop until it is regenerated.";

    /**
     * How the ingestion pipelines get a regenerated token: OpenMetadata puts the bot's token into a
     * pipeline when it deploys it, so the ones deployed before keep the old token until redeployed.
     */
    public static final String PIPELINES_HINT =
            "run the release's 'Re-register Hive ingestion', 'Re-register Trino ingestion' and 'Re-register"
            + " Atlas federation' actions (those the Releases page shows) so OpenMetadata redeploys its ingestion"
            + " pipelines with the new token, and redeploy any other ingestion pipeline from OpenMetadata";

    /** What to run once the token is regenerated, so that every holder gets the new one. */
    public static final String AFTER_REGENERATE_HINT =
            "then run 'Re-register Ranger TagSync source' on the release so Ranger TagSync gets the new token, and "
            + PIPELINES_HINT;

    /**
     * The bot's token and when it expires.
     *
     * @param jwt       the token, as OpenMetadata stores it for the bot
     * @param expiresAt its {@code exp} claim, or {@code null} when it never expires
     */
    public record BotToken(String jwt, Instant expiresAt) {
        @Override
        public String toString() {
            // Never let the token reach a log line through string concatenation.
            return "BotToken[expiresAt=" + (expiresAt == null ? "never" : expiresAt) + "]";
        }
    }

    /**
     * Reads the {@code ingestion-bot}'s current JWT. The flow runs entirely inside an airflow
     * scheduler pod via K8s exec: read the encrypted JWT from {@code user_entity.json} in OM
     * PostgreSQL, strip the {@code fernet:} prefix and decrypt it with the chart's Fernet key.
     *
     * @param client K8s client the view holds for the deploy
     * @param namespace release namespace
     * @param release helm release name (matches OM pod name prefixes)
     * @param fernetKey Fernet key string (32-byte URL-safe base64); falls back to
     *                  {@link #DEFAULT_FERNET_KEY} when null/blank
     * @param timeout how long to wait for the exec to complete
     * @return the current token and its expiry
     * @throws IllegalStateException on any failure (message includes captured stderr), or when the
     *                               token has expired
     */
    public static BotToken readBotToken(KubernetesClient client,
                                        String namespace,
                                        String release,
                                        String fernetKey,
                                        Duration timeout) throws Exception {
        if (client == null) throw new IllegalArgumentException("client is required");
        if (namespace == null || namespace.isBlank()) throw new IllegalArgumentException("namespace is required");
        if (release == null || release.isBlank()) throw new IllegalArgumentException("release is required");
        String key = (fernetKey == null || fernetKey.isBlank()) ? DEFAULT_FERNET_KEY : fernetKey;

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
        LOG.info("OmBotJwtClient: exec into pod '{}' to read the ingestion-bot token", podName);

        // PGPASSWORD comes from /opt/airflow/secrets/postgresql-password OR from an env var the
        // airflow container already has (the OM-deps chart mounts the password via the airflow DB
        // connection Secret).
        String script =
            "set -eu\n" +
            "python3 - <<'PY'\n" +
            "import os, sys, re, subprocess\n" +
            "from cryptography.fernet import Fernet\n" +
            "# Read the encrypted JWT from postgres. We use the OM main postgres,\n" +
            "# not airflow_db; the scheduler pod's env points at airflow_db, so\n" +
            "# override host + db here using the OM convention <release>-postgresql\n" +
            "# + openmetadata_db.\n" +
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
            "print(Fernet(os.environ['OM_FERNET_KEY'].encode()).decrypt(ct.encode()).decode())\n" +
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
                        " OM_FERNET_KEY=" + shellQuote(key) +
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
        // The JWT is the LAST line of stdout, after anything the image prints on start.
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
        BotToken token = new BotToken(jwt, expiryOf(jwt));
        requireUsable(token, Instant.now());
        LOG.info("OmBotJwtClient: read the ingestion-bot token (length={}, expires {}) in '{}'/'{}'",
                jwt.length(), token.expiresAt() == null ? "never" : token.expiresAt(), namespace, release);
        return token;
    }

    /**
     * The {@code exp} claim of a JWT, or {@code null} when it has none (OpenMetadata's
     * "Unlimited" tokens) or the token cannot be decoded.
     */
    public static Instant expiryOf(String jwt) {
        if (jwt == null) return null;
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) return null;
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(payload);
            if (!root.isJsonObject()) return null;
            JsonObject claims = root.getAsJsonObject();
            JsonElement exp = claims.get("exp");
            if (exp == null || exp.isJsonNull() || !exp.isJsonPrimitive()) return null;
            return Instant.ofEpochSecond(exp.getAsLong());
        } catch (RuntimeException e) {
            LOG.debug("OmBotJwtClient: could not read the expiry of the bot token: {}", e.toString());
            return null;
        }
    }

    /**
     * Refuses a token that has expired: every caller would fail on it with a bare HTTP 401, and
     * Ranger TagSync would silently stop receiving tags. An administrator has to regenerate it.
     */
    static void requireUsable(BotToken token, Instant now) {
        if (token.expiresAt() != null && !token.expiresAt().isAfter(now)) {
            throw new IllegalStateException("The OpenMetadata ingestion-bot token expired on " + token.expiresAt()
                    + ", so OpenMetadata refuses it. " + EXPIRY_IMPACT + " " + REGENERATE_HINT + ", "
                    + AFTER_REGENERATE_HINT + ".");
        }
    }

    /**
     * The warning for a token that never expires, or {@code null} for one that expires. OpenMetadata
     * creates the bot with such a token and does not let KDPS rotate it, so it is handed over (Ranger
     * TagSync keeps it in a credential store, not in its configuration file) and the operator is told
     * how to replace it with one that expires.
     */
    public static String neverExpiresWarning(BotToken token) {
        if (token == null || token.expiresAt() != null) return null;
        return "The OpenMetadata ingestion-bot token never expires (OpenMetadata creates the bot that way)."
                + " Ranger TagSync keeps it in a credential store; anyone who obtains a copy, including the copies"
                + " earlier KDPS versions left in clear in Ambari's configuration history, could call OpenMetadata"
                + " as the bot for good. To limit that, " + REGENERATE_HINT + " (for example 90 days), "
                + AFTER_REGENERATE_HINT + ". The old token then stops working. " + EXPIRY_IMPACT
                + " Renew it the same way before it expires (KDPS's OpenMetadata steps warn once less than "
                + RENEWAL_NOTICE.toDays() + " days are left).";
    }

    /**
     * What an operator should know about the token's lifetime, or {@code null} when nothing: a token
     * that expires within {@link #RENEWAL_NOTICE} (one that never expires is reported by
     * {@link #neverExpiresWarning}).
     */
    public static String lifetimeWarning(BotToken token, Instant now) {
        if (token == null || token.expiresAt() == null) return null;
        if (token.expiresAt().isBefore(now.plus(RENEWAL_NOTICE))) {
            return "The OpenMetadata ingestion-bot token expires on " + token.expiresAt() + ". " + EXPIRY_IMPACT
                    + " " + REGENERATE_HINT + ", " + AFTER_REGENERATE_HINT + ".";
        }
        return null;
    }

    /** Single-quote a string for safe interpolation inside {@code bash -c '...'}. */
    static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private OmBotJwtClient() { /* utility */ }
}
