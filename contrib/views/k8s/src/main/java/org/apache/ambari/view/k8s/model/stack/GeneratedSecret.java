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

package org.apache.ambari.view.k8s.model.stack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * A Secret of credentials KDPS creates for a release before Helm installs it ({@code generatedSecrets} in
 * {@code service.json}), e.g. the Superset administrator password or the password of a bundled database. Its keys get
 * random values the first time and keep them on every later deploy, and the Secret carries no Helm ownership, so an
 * uninstall leaves it and a reinstall finds the passwords its volumes were initialized with. The chart only gets the
 * Secret's name ({@link #values}). See {@code GeneratedSecretService}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeneratedSecret {

    /** Identifies the entry in logs, e.g. {@code admin}. */
    public String key;

    /** Name of the Secret, {@code {{releaseName}}} and {@code {{namespace}}} expanded, e.g. {@code {{releaseName}}-superset-admin}. */
    public String secretNameTemplate;

    /** What the Secret holds, in the operator's words, e.g. "the Superset administrator password". */
    public String label;

    /** Chart versions that read the Secret, e.g. {@code >=0.12.50}: an older chart is left as it is, with a warning. */
    public String chartVersion;

    /** Values path that must be true (in the release's values or form) for the Secret to be created, e.g. {@code dependencies.enabled}. */
    public String when;

    /** The keys of the Secret, in order. */
    public Map<String, GeneratedKey> keys;

    /**
     * Chart values the deploy sets, dotted path to template: {@code {{secretName}}}, {@code {{releaseName}}},
     * {@code {{namespace}}}, and {@code {{generated}}}, "true" from the deploy that generated a value of the Secret
     * until a deploy that carried it has succeeded, else "false". The chart then sets the value on an account that
     * already exists (e.g. Superset's {@code init.adminUser.resetPassword}): a deploy that failed, was refused or
     * waits for its GitOps merge has not done it yet, so the next one asks again. A list index ({@code env[0].name})
     * writes into a list the chart's defaults do not set.
     */
    public Map<String, String> values;

    /**
     * The persistent volume claim of the database these passwords protect, e.g.
     * {@code data-{{releaseName}}-postgresql-0}. A database keeps the passwords it was initialized with: when the
     * claim exists, a key the release signs in with ({@link GeneratedKey#keptByVolume}) that can be found neither in
     * this Secret nor where {@link GeneratedKey#adoptFrom} points is taken from {@link GeneratedKey#formerDefault}, or
     * else the deploy is refused rather than given a password the database would reject. A claim KDPS cannot read
     * refuses the deploy too: it cannot tell a new install from a database initialized before.
     */
    public String dataVolume;

    /**
     * What the operator is told once the release is deployed, e.g. where to read the administrator password:
     * {@code {{secretName}}}, {@code {{releaseName}}}, {@code {{namespace}}} and {@code {{readCommand}}} (the command
     * that prints the first key) expanded.
     */
    public String notice;

    /** One key of the Secret. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GeneratedKey {

        /** Length of the random value (letters and digits); 32 when unset. */
        public Integer length;

        /**
         * Where the value is taken from before one is generated, in order: the key of another Secret, e.g. the one a
         * chart created for its database before it read this one ({@code {"secret": "{{releaseName}}-postgresql",
         * "key": "password"}}). That is how a release installed before keeps the password its database runs with.
         */
        public List<Map<String, String>> adoptFrom;

        /**
         * Whether the release signs in to the database on {@link GeneratedSecret#dataVolume} with this value, which
         * the database keeps from its first start (the application user's password). Only such a key holds the deploy
         * back when the volume exists and the value cannot be found. Any other key (e.g. the password of the database
         * administrator, which the release never uses) is generated then, with a warning that the database keeps its
         * own.
         */
        public Boolean keptByVolume;

        /**
         * The value the chart used to set for every release before it read this Secret (e.g. {@code superset}), for a
         * key {@link #keptByVolume}. When the volume exists and no Secret holds the key any more (a release installed
         * before, uninstalled, which deleted the chart's own Secret, then installed again), the database still runs
         * with it, so it is taken rather than the deploy refused. Whenever the Secret holds it, the operation log warns
         * that anyone who reads the chart knows the password.
         */
        public String formerDefault;

        /**
         * A value made of the other keys rather than generated, set again whenever they change: {@code ${name}} is the
         * URL-encoded value of key {@code name}, e.g. a database connection URL.
         */
        public String template;
    }
}
