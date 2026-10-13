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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.ambari.view.k8s.store.InstanceSettings;

/**
 * The client secret Keycloak gives the OIDC client a deploy registers (step OIDC_REGISTER_CLIENT), from then until the
 * next step has written it into the release's Kubernetes Secret (OIDC_CREATE_SECRET). Meanwhile it is kept encrypted
 * with Ambari's master key in the view settings, under {@code oidc.client.secret.<registration step id>}, and deleted
 * once written.
 *
 * <p>It is never kept in the step's result: that is a plain column of the view's database, kept with the operation
 * history. Earlier versions kept it there; {@link #withoutClientSecret} clears it.</p>
 */
public final class OidcClientSecrets {

    /** Prefix of the view settings holding the secrets. */
    static final String KEY_PREFIX = "oidc.client.secret.";
    /** The key earlier versions kept the secret under in the result of a registration step. */
    static final String RESULT_KEY = "clientSecret";

    private static final Gson GSON = new Gson();

    private final InstanceSettings settings;

    /**
     * @param settings the instance settings the secrets are kept in
     */
    public OidcClientSecrets(InstanceSettings settings) {
        this.settings = settings;
    }

    /** The view setting holding the secret of a registration step. */
    static String keyOf(String registerStepId) {
        return KEY_PREFIX + registerStepId;
    }

    /**
     * Keeps the secret a registration step got, until {@link #forget}. A public client has none: nothing is kept.
     *
     * @param registerStepId the registration step
     * @param clientSecret   the client secret, may be {@code null} or blank
     */
    public void keep(String registerStepId, String clientSecret) {
        if (clientSecret == null || clientSecret.isBlank()) {
            settings.delete(keyOf(registerStepId));
            return;
        }
        settings.putSecret(keyOf(registerStepId), clientSecret);
    }

    /**
     * The secret a registration step got.
     *
     * @param registerStepId the registration step
     * @return the client secret, or {@code null} when none is kept: the step was run by an earlier version, or the
     *         secret was already written and forgotten
     */
    public String read(String registerStepId) {
        return settings.getSecret(keyOf(registerStepId));
    }

    /**
     * Deletes the secret of a registration step, once it is written where the release reads it, or once its
     * operation can no longer be resumed.
     *
     * @param registerStepId the registration step
     */
    public void forget(String registerStepId) {
        settings.delete(keyOf(registerStepId));
    }

    /**
     * A step result without the client secret earlier versions kept in it.
     *
     * @param resultJson the result of a step, as stored
     * @return the result without it, or {@code null} when it holds none (or is not a JSON object)
     */
    public static String withoutClientSecret(String resultJson) {
        if (resultJson == null || !resultJson.contains("\"" + RESULT_KEY + "\"")) {
            return null;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(resultJson);
        } catch (RuntimeException notJson) {
            return null;
        }
        if (!parsed.isJsonObject()) {
            return null;
        }
        JsonObject result = parsed.getAsJsonObject();
        if (result.remove(RESULT_KEY) == null) {
            return null;
        }
        return GSON.toJson(result);
    }
}
