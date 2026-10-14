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

import org.apache.ambari.view.ViewContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The projects (namespaces) where KDPS keeps what operators register once for every release: the truststores of the
 * Truststores tab ({@link TruststoreRegistryService}) and the Company CAs of the Certificate Authorities page
 * ({@link CaRegistryService}).
 *
 * <p>Both are set on the Cluster Settings page, in the view settings ({@value #TRUSTSTORE_NAMESPACE} and
 * {@value #PKI_NAMESPACE}). An account limited to its own projects cannot create {@value #DEFAULT_NAMESPACE}, so the
 * platform team creates a project for them and grants the account the right to write Secrets there; the settings name
 * it. View instance properties of the same names, which earlier versions read, are still honoured when the settings
 * leave them empty.</p>
 *
 * <p>The Company CAs hold their private keys: their project never defaults to a project releases are deployed to,
 * where every editor of the project could read them. Truststores hold public certificates only, so they may share the
 * Company CA project.</p>
 */
public class PkiNamespaces {

    private static final Logger LOG = LoggerFactory.getLogger(PkiNamespaces.class);

    /** The project used when nothing is configured. */
    public static final String DEFAULT_NAMESPACE = "ambari-pki";

    /** Setting (and legacy view property) naming the project of the Company CAs. */
    public static final String PKI_NAMESPACE = "pki.namespace";

    /** Setting (and legacy view property) naming the project of the truststores created in the Truststores tab. */
    public static final String TRUSTSTORE_NAMESPACE = "truststore.namespace";

    /** A Kubernetes namespace name: a DNS-1123 label. */
    private static final Pattern NAMESPACE_NAME = Pattern.compile("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?");

    private final ViewContext viewContext;
    private final Supplier<Map<String, Object>> settings;

    /**
     * @param viewContext the view instance, whose settings and properties name the projects
     */
    public PkiNamespaces(ViewContext viewContext) {
        this(viewContext, () -> new ViewSettingsService(viewContext).readMasked());
    }

    /**
     * @param viewContext the view instance, whose properties name the projects when the settings do not
     * @param settings    the view settings document
     */
    PkiNamespaces(ViewContext viewContext, Supplier<Map<String, Object>> settings) {
        this.viewContext = Objects.requireNonNull(viewContext, "viewContext");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * The project of the truststores created in the Truststores tab: the truststore setting, else the Company CA one,
     * else the view properties of the same names, else {@value #DEFAULT_NAMESPACE}.
     *
     * @return the namespace name
     */
    public String truststoreNamespace() {
        Map<String, Object> doc = settingsOrEmpty();
        return firstSet(setting(doc, TRUSTSTORE_NAMESPACE), setting(doc, PKI_NAMESPACE),
                property(TRUSTSTORE_NAMESPACE), property(PKI_NAMESPACE), DEFAULT_NAMESPACE);
    }

    /**
     * The project of the Company CAs: the Company CA setting, else the view property of the same name, else
     * {@value #DEFAULT_NAMESPACE}. Never the truststore project, which may be a project releases are deployed to.
     *
     * @return the namespace name
     */
    public String caNamespace() {
        return firstSet(setting(settingsOrEmpty(), PKI_NAMESPACE), property(PKI_NAMESPACE), DEFAULT_NAMESPACE);
    }

    /**
     * What an operator reads when the Kubernetes account of KDPS may not write Secrets in one of these projects.
     *
     * @param namespace the project
     * @return the sentence
     */
    public static String forbiddenMessage(String namespace) {
        return "KDPS cannot write Secrets in project " + namespace + "; ask the platform team to grant the KDPS account "
                + "edit in that project, or set the namespace in Cluster Settings.";
    }

    /**
     * Checks a namespace a setting names.
     *
     * @param key   the setting, for the message
     * @param value the value; {@code null} or blank leaves the setting unset
     * @throws IllegalArgumentException when the value is not a valid namespace name
     */
    public static void checkNamespaceSetting(String key, Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return;
        }
        if (!(value instanceof String text) || !NAMESPACE_NAME.matcher(text.trim()).matches()) {
            throw new IllegalArgumentException("The setting " + key + " must be a project (namespace) name: lowercase "
                    + "letters, digits and dashes, at most 63 characters, starting and ending with a letter or a digit.");
        }
    }

    private Map<String, Object> settingsOrEmpty() {
        try {
            Map<String, Object> doc = settings.get();
            return doc == null ? Map.of() : doc;
        } catch (RuntimeException e) {
            LOG.warn("The view settings could not be read; the truststore and Company CA projects fall back to the "
                    + "view properties: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String setting(Map<String, Object> doc, String dottedPath) {
        Object value = ConfigResolutionService.getByDottedPath(doc, dottedPath);
        return value instanceof String text ? text : null;
    }

    private String property(String key) {
        Map<String, String> props = viewContext.getProperties();
        return props == null ? null : props.get(key);
    }

    private static String firstSet(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return DEFAULT_NAMESPACE;
    }
}
