/*
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

package org.apache.ambari.view.k8s.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.api.model.AuthInfo;
import io.fabric8.kubernetes.api.model.Cluster;
import io.fabric8.kubernetes.api.model.Config;
import io.fabric8.kubernetes.api.model.Context;
import io.fabric8.kubernetes.api.model.NamedAuthInfo;
import io.fabric8.kubernetes.api.model.NamedCluster;
import io.fabric8.kubernetes.api.model.NamedContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a kubeconfig from making the Ambari server run a command or read one of its files. The Kubernetes client and
 * Helm both honour {@code users[].user.exec} (a credential plugin) and {@code users[].user.auth-provider} (whose
 * {@code gcp} flavour runs a {@code cmd-path}), so an uploaded file could otherwise execute anything as the account
 * running Ambari, which can read the master key. The {@code tokenFile}, {@code client-certificate},
 * {@code client-key} and {@code certificate-authority} paths make the clients read a file of the Ambari server, and
 * a token read that way is sent to the {@code server} of the same kubeconfig, which whoever wrote the file chooses.
 *
 * <p>No client is ever given the operator's text. It is read once, with the same reader the Kubernetes client uses,
 * into the typed kubeconfig model, and KDPS writes a new kubeconfig from the fields of that model it knows to be safe:
 * server addresses, inline certificates and keys, tokens, user names and passwords, impersonation, contexts and the
 * current context. That text is what the Kubernetes client, Helm and the helm binary read. Whatever the upload
 * contained that Go's YAML reader, used by Helm, might understand differently (anchors, merge keys, tags, repeated
 * keys, fields the model does not know) is either refused by the reader or ends up outside those fields, so it is
 * never passed on.
 *
 * <p>An upload is refused when it names a command, a file of the Ambari server or a field the model does not know,
 * so that what is saved is what is used. A kubeconfig saved before these checks existed is reduced the same way
 * whenever it is used.
 */
public final class KubeconfigGuard {

    private static final Logger LOG = LoggerFactory.getLogger(KubeconfigGuard.class);

    /** Writes every string in double quotes, so a Go YAML reader cannot take a value for a number or a boolean. */
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** Kubeconfigs already reported as reduced, so a client rebuilt on every call does not fill the log. */
    private static final Set<Integer> REPORTED = ConcurrentHashMap.newKeySet();

    private KubeconfigGuard() {
    }

    /**
     * Reads {@code kubeconfig} the way the Kubernetes client does.
     *
     * @param kubeconfig kubeconfig as YAML or JSON
     * @return the kubeconfig model
     * @throws IllegalArgumentException when the text is not a single kubeconfig document
     */
    public static Config parse(String kubeconfig) {
        if (kubeconfig == null || kubeconfig.isBlank()) {
            throw new IllegalArgumentException("The kubeconfig is empty.");
        }
        Object parsed;
        try {
            parsed = Serialization.unmarshal(kubeconfig, Config.class);
        } catch (RuntimeException e) {
            // The parser's message quotes the offending line, which may hold a credential: it stays in the cause.
            throw new IllegalArgumentException("The kubeconfig cannot be read: it is not a kubeconfig written in YAML or JSON.", e);
        }
        // Several documents come back as a list, whatever class was asked for.
        if (!(parsed instanceof Config model)) {
            throw new IllegalArgumentException("The kubeconfig cannot be read: it must be a single kubeconfig document.");
        }
        return model;
    }

    /**
     * Why {@code model} cannot be saved, one customer-facing sentence per user, cluster or context field that names a
     * command, a file of the Ambari server, or something KDPS does not recognise.
     *
     * @param model the uploaded kubeconfig
     * @return the reasons, empty when the kubeconfig can be saved
     */
    public static List<String> problems(Config model) {
        List<String> problems = new ArrayList<>();
        unknownFields("The kubeconfig", model.getAdditionalProperties(), problems);
        for (NamedCluster named : orEmpty(model.getClusters())) {
            String cluster = "Cluster '" + named.getName() + "'";
            unknownFields(cluster, named.getAdditionalProperties(), problems);
            Cluster fields = named.getCluster();
            if (fields != null) {
                unknownFields(cluster, fields.getAdditionalProperties(), problems);
                if (fields.getCertificateAuthority() != null) {
                    problems.add(fileMessage(cluster, "certificate-authority", "its CA certificate", "certificate-authority-data"));
                }
            }
        }
        for (NamedContext named : orEmpty(model.getContexts())) {
            String context = "Context '" + named.getName() + "'";
            unknownFields(context, named.getAdditionalProperties(), problems);
            if (named.getContext() != null) {
                unknownFields(context, named.getContext().getAdditionalProperties(), problems);
            }
        }
        for (NamedAuthInfo named : orEmpty(model.getUsers())) {
            String user = "User '" + named.getName() + "'";
            unknownFields(user, named.getAdditionalProperties(), problems);
            for (String field : leftOut(named.getUser())) {
                problems.add(userFieldMessage(user, field));
            }
        }
        return problems;
    }

    /**
     * The fields of {@code user} that KDPS does not pass on: a command, a path to a file of the Ambari server, or a
     * field the kubeconfig model does not know.
     *
     * @param user a user of a kubeconfig, may be {@code null}
     * @return the field names as written in a kubeconfig, empty when the user is passed on whole
     */
    public static List<String> leftOut(AuthInfo user) {
        List<String> fields = new ArrayList<>();
        if (user == null) {
            return fields;
        }
        if (user.getExec() != null) {
            fields.add("exec");
        }
        if (user.getAuthProvider() != null) {
            fields.add("auth-provider");
        }
        if (user.getTokenFile() != null) {
            fields.add("tokenFile");
        }
        if (user.getClientCertificate() != null) {
            fields.add("client-certificate");
        }
        if (user.getClientKey() != null) {
            fields.add("client-key");
        }
        fields.addAll(orEmpty(user.getAdditionalProperties()).keySet());
        return fields;
    }

    /**
     * The customer-facing explanation of why the field {@code field} of a user is not used.
     *
     * @param user  how the user is named in the sentence, such as {@code User 'admin'}
     * @param field a field name returned by {@link #leftOut}
     * @return the sentence, which tells what to use instead
     */
    public static String userFieldMessage(String user, String field) {
        switch (field) {
            case "exec":
            case "auth-provider":
                return user + " gets its credentials by running a command (" + field + "). KDPS does not run commands "
                        + "on the Ambari server: use a kubeconfig with a token or a client certificate.";
            case "tokenFile":
                return fileMessage(user, field, "its token", "token");
            case "client-certificate":
                return fileMessage(user, field, "its client certificate", "client-certificate-data");
            case "client-key":
                return fileMessage(user, field, "its client key", "client-key-data");
            default:
                return unknownFieldMessage(user, field);
        }
    }

    /**
     * The kubeconfig a client is given: {@code kubeconfig} read into the kubeconfig model and written again by KDPS
     * with only the fields that neither run a command nor read a file of the Ambari server. Extensions and
     * preferences, which no connection needs, are left out too. A user left without any credential fails to
     * authenticate, which is reported like any rejected login.
     *
     * @param kubeconfig kubeconfig as YAML or JSON, may be {@code null}
     * @return the kubeconfig to connect with; {@code null} or blank input is returned as is
     * @throws IllegalArgumentException when the text cannot be read as a kubeconfig, see {@link #parse}
     */
    public static String canonical(String kubeconfig) {
        if (kubeconfig == null || kubeconfig.isBlank()) {
            return kubeconfig;
        }
        Config model = parse(kubeconfig);
        List<String> reduced = new ArrayList<>();
        for (NamedAuthInfo named : orEmpty(model.getUsers())) {
            if (!leftOut(named.getUser()).isEmpty()) {
                reduced.add(named.getName());
            }
        }
        List<String> caFromFile = new ArrayList<>();
        for (NamedCluster named : orEmpty(model.getClusters())) {
            if (named.getCluster() != null && named.getCluster().getCertificateAuthority() != null) {
                caFromFile.add(named.getName());
            }
        }
        if ((!reduced.isEmpty() || !caFromFile.isEmpty()) && REPORTED.size() < 1000
                && REPORTED.add(kubeconfig.hashCode())) {
            if (!reduced.isEmpty()) {
                LOG.warn("The saved kubeconfig has user(s) {} that get credentials from a command, from a file of the "
                        + "Ambari server or from a field KDPS does not recognise. KDPS uses none of them, so those users "
                        + "may not authenticate: upload a kubeconfig with a token or a client certificate instead.", reduced);
            }
            // Without its CA certificate the cluster's server certificate is not trusted, which otherwise only shows
            // as a bare certificate path error.
            if (!caFromFile.isEmpty()) {
                LOG.warn("The saved kubeconfig has cluster(s) {} that read their CA certificate from a file of the Ambari "
                        + "server (certificate-authority). KDPS does not read it, so the cluster's certificate may not "
                        + "be trusted: upload a kubeconfig with the CA certificate itself (certificate-authority-data) "
                        + "instead.", caFromFile);
            }
        }
        try {
            return YAML.writeValueAsString(safeCopy(model));
        } catch (Exception e) {
            throw new IllegalArgumentException("The kubeconfig cannot be written again for the clients.", e);
        }
    }

    /** A new model holding only the fields of {@code model} that KDPS passes on; nothing else is copied. */
    private static Config safeCopy(Config model) {
        Config copy = new Config();
        copy.setApiVersion("v1");
        copy.setKind("Config");
        copy.setCurrentContext(model.getCurrentContext());
        List<NamedCluster> clusters = new ArrayList<>();
        for (NamedCluster named : orEmpty(model.getClusters())) {
            Cluster cluster = null;
            if (named.getCluster() != null) {
                Cluster from = named.getCluster();
                cluster = new Cluster();
                cluster.setServer(from.getServer());
                cluster.setCertificateAuthorityData(from.getCertificateAuthorityData());
                cluster.setInsecureSkipTlsVerify(from.getInsecureSkipTlsVerify());
                cluster.setTlsServerName(from.getTlsServerName());
                cluster.setProxyUrl(from.getProxyUrl());
                cluster.setDisableCompression(from.getDisableCompression());
            }
            clusters.add(new NamedCluster(cluster, named.getName()));
        }
        copy.setClusters(clusters);
        List<NamedContext> contexts = new ArrayList<>();
        for (NamedContext named : orEmpty(model.getContexts())) {
            Context context = null;
            if (named.getContext() != null) {
                Context from = named.getContext();
                context = new Context();
                context.setCluster(from.getCluster());
                context.setUser(from.getUser());
                context.setNamespace(from.getNamespace());
            }
            contexts.add(new NamedContext(context, named.getName()));
        }
        copy.setContexts(contexts);
        List<NamedAuthInfo> users = new ArrayList<>();
        for (NamedAuthInfo named : orEmpty(model.getUsers())) {
            AuthInfo user = null;
            if (named.getUser() != null) {
                AuthInfo from = named.getUser();
                user = new AuthInfo();
                user.setToken(from.getToken());
                user.setClientCertificateData(from.getClientCertificateData());
                user.setClientKeyData(from.getClientKeyData());
                user.setUsername(from.getUsername());
                user.setPassword(from.getPassword());
                user.setAs(from.getAs());
                user.setAsUid(from.getAsUid());
                user.setAsGroups(from.getAsGroups());
                user.setAsUserExtra(from.getAsUserExtra());
            }
            users.add(new NamedAuthInfo(named.getName(), user));
        }
        copy.setUsers(users);
        return copy;
    }

    private static String fileMessage(String owner, String field, String what, String inlineField) {
        return owner + " reads " + what + " from a file on the Ambari server (" + field + "). KDPS does not read files "
                + "from the Ambari server for a kubeconfig: put " + what + " itself in the kubeconfig (" + inlineField + ").";
    }

    private static String unknownFieldMessage(String owner, String field) {
        return owner + " has a field KDPS does not recognise (" + field + "): remove it.";
    }

    private static void unknownFields(String owner, Map<String, Object> additional, List<String> problems) {
        for (String field : orEmpty(additional).keySet()) {
            problems.add(unknownFieldMessage(owner, field));
        }
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static Map<String, Object> orEmpty(Map<String, Object> map) {
        return map == null ? Map.of() : map;
    }
}
