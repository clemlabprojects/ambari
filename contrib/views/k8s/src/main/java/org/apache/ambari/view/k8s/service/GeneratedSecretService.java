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

import io.fabric8.kubernetes.api.model.Secret;
import org.apache.ambari.view.k8s.model.stack.GeneratedSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates the Secrets of credentials a service definition declares ({@code generatedSecrets}) before a release is
 * deployed, and says which chart values point at them.
 *
 * <p>A chart default such as admin/admin, or a database password written in the chart's values, is the same on every
 * release and known to anyone who reads the chart. Each value here is random instead, generated the first time and
 * read back on every later deploy, so an upgrade keeps it. The Secret is not part of the Helm release: an uninstall
 * leaves it, and a reinstall on the volumes the release kept finds the passwords its databases were initialized with.
 * Only the Secret's name reaches the chart values, so no password is written to the release's values, a GitOps
 * repository or the operation log.
 *
 * <p>A release deployed before its definition declared a Secret already runs with a database password. Such a value
 * is adopted, not generated: {@link GeneratedSecret.GeneratedKey#adoptFrom} points at where the chart kept it (the
 * Secret a PostgreSQL chart creates for itself), and {@link GeneratedSecret.GeneratedKey#formerDefault} names the one
 * the chart used to set for every release, for when that Secret went with an uninstall. When a password the release
 * signs in with is nowhere to be found while the database volume exists, or while its volume claim cannot be read,
 * the deploy is refused: a new password would lock the release out of its own database.
 *
 * <p>A generated value the chart must set on an account that already exists ({@code {{generated}}}, e.g. Superset's
 * {@code init.adminUser.resetPassword}) is set only by a deploy that gets through. The Secret is marked
 * ({@link #PENDING_ANNOTATION}) when the value is generated and keeps the mark, so {@code {{generated}}} stays true,
 * until a deploy that carried it succeeds ({@link #markApplied}).
 */
public class GeneratedSecretService {

    private static final Logger LOG = LoggerFactory.getLogger(GeneratedSecretService.class);

    /** Label every generated Secret carries with the release it belongs to. */
    static final String RELEASE_LABEL = "ambari.clemlab.com/release";
    /** Label that marks a Secret of generated credentials. */
    static final String TYPE_LABEL = "ambari.clemlab.com/type";
    static final String TYPE_VALUE = "generated-credentials";
    /** Annotation saying what the Secret holds, for whoever lists the Secrets of the namespace. */
    static final String DESCRIPTION_ANNOTATION = "ambari.clemlab.com/description";
    /** Annotation on a Secret whose generated value no deploy has set on the account yet ({@code {{generated}}}). */
    static final String PENDING_ANNOTATION = "ambari.clemlab.com/pending-apply";

    private static final int DEFAULT_LENGTH = 32;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final Pattern KEY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * What was done for one declared Secret.
     *
     * @param key        the entry's key in the definition
     * @param secretName the Secret's name
     * @param applied    whether the release uses it: false when its chart is too old to read it, with a warning
     * @param generated  the keys given a new random value by this deploy
     * @param adopted    the keys whose value was taken from where the chart kept it
     * @param values     chart values to set (dotted path to value), when applied
     * @param pending    whether {@code {{generated}}} is true: once this deploy succeeds, {@link #markApplied} says so
     * @param notice     what the operator is told once the release is deployed, or null
     * @param warnings   what the operator should know (why the Secret is not used, a password anyone can know)
     */
    public record Outcome(String key, String secretName, boolean applied, List<String> generated,
                          List<String> adopted, Map<String, String> values, boolean pending, String notice,
                          List<String> warnings) {
    }

    private final KubernetesService kubernetes;
    private final SecureRandom random;

    public GeneratedSecretService(KubernetesService kubernetes) {
        this(kubernetes, new SecureRandom());
    }

    GeneratedSecretService(KubernetesService kubernetes, SecureRandom random) {
        this.kubernetes = kubernetes;
        this.random = random;
    }

    /**
     * Creates or completes each declared Secret of a release and returns what the deploy must set.
     *
     * @param specs        the definition's {@code generatedSecrets}
     * @param chartVersion the chart version deployed, or null/blank when unknown (taken as the latest)
     * @param values       the release's values, read for {@link GeneratedSecret#when}
     * @param formValues   the wizard's form, read for {@link GeneratedSecret#when} too (may be null)
     * @throws IllegalStateException when a database volume exists, or its claim cannot be read, but the password the
     *                               release signs in to it with cannot be found
     */
    public List<Outcome> ensure(List<GeneratedSecret> specs, String namespace, String releaseName,
                                String chartVersion, Map<String, Object> values, Map<String, Object> formValues) {
        if (specs == null || specs.isEmpty()) return Collections.emptyList();
        List<Outcome> outcomes = new ArrayList<>();
        for (GeneratedSecret spec : specs) {
            if (spec == null || spec.secretNameTemplate == null || spec.secretNameTemplate.isBlank()
                    || spec.keys == null || spec.keys.isEmpty()) {
                continue;
            }
            if (!isOn(spec.when, values, formValues)) continue;
            outcomes.add(ensureOne(spec, namespace, releaseName, chartVersion));
        }
        return outcomes;
    }

    private Outcome ensureOne(GeneratedSecret spec, String namespace, String releaseName, String chartVersion) {
        String secretName = render(spec.secretNameTemplate, releaseName, namespace);
        String label = spec.label == null || spec.label.isBlank() ? "the credentials of " + secretName : spec.label;
        if (chartVersion != null && !chartVersion.isBlank() && spec.chartVersion != null && !spec.chartVersion.isBlank()
                && !CommandService.isChartVersionCompatible(chartVersion, spec.chartVersion)) {
            String warning = "Chart " + chartVersion + " cannot read " + label + " from the Secret KDPS generates for it"
                    + " (it needs chart " + spec.chartVersion + "): the release keeps the credentials of its chart."
                    + " Upgrade the chart to a version in " + spec.chartVersion + " to give it generated ones.";
            return new Outcome(spec.key, secretName, false, List.of(), List.of(), Map.of(), false, null,
                    List.of(warning));
        }

        Secret existing = kubernetes.getSecret(namespace, secretName);
        Map<String, String> current = decode(existing);
        Map<String, String> data = new LinkedHashMap<>(current);
        List<String> generated = new ArrayList<>();
        List<String> adopted = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        // Keys found in no Secret and taken from the chart's former default, which the volume's database runs with.
        List<String> fellBack = new ArrayList<>();
        String volume = spec.dataVolume == null || spec.dataVolume.isBlank()
                ? null : render(spec.dataVolume, releaseName, namespace);
        // Read once per Secret: null when the claim could not be read (no permission, the API did not answer).
        Boolean volumeExists = null;
        boolean volumeChecked = false;
        for (Map.Entry<String, GeneratedSecret.GeneratedKey> entry : spec.keys.entrySet()) {
            String key = entry.getKey();
            GeneratedSecret.GeneratedKey keySpec = entry.getValue() == null
                    ? new GeneratedSecret.GeneratedKey() : entry.getValue();
            if (keySpec.template != null || nonBlank(data.get(key))) continue;
            String found = adopt(keySpec, namespace, releaseName);
            if (found != null) {
                data.put(key, found);
                adopted.add(key);
                continue;
            }
            if (volume != null) {
                if (!volumeChecked) {
                    volumeExists = kubernetes.persistentVolumeClaimExists(namespace, volume);
                    volumeChecked = true;
                }
                if (volumeExists == null) {
                    // Not knowing is not "no volume": a database there would reject a new password the release
                    // signs in with, and every later deploy would reuse it.
                    if (Boolean.TRUE.equals(keySpec.keptByVolume)) {
                        throw new IllegalStateException(uncheckedVolume(keySpec, namespace, releaseName, secretName,
                                volume, key));
                    }
                    warnings.add("KDPS could not read the volume claim " + namespace + "/" + volume + " and generated"
                            + " a new value for key '" + key + "' of the Secret " + namespace + "/" + secretName
                            + ": if the volume keeps a database initialized before, the database does not use it (it"
                            + " keeps the one it was initialized with).");
                } else if (volumeExists) {
                    if (Boolean.TRUE.equals(keySpec.keptByVolume)) {
                        if (!nonBlank(keySpec.formerDefault)) {
                            throw new IllegalStateException(lostPassword(keySpec, namespace, releaseName, secretName,
                                    volume, key));
                        }
                        data.put(key, keySpec.formerDefault);
                        adopted.add(key);
                        fellBack.add(key);
                        continue;
                    }
                    // Nothing signs in with it: a new value cannot lock the release out, but the database keeps
                    // the one it was initialized with, which whoever reads the Secret should know.
                    warnings.add("The volume " + volume + " keeps a database initialized before the Secret "
                            + namespace + "/" + secretName + " held key '" + key + "': KDPS generated a new value for"
                            + " it, which the database does not use (it keeps the one it was initialized with).");
                }
            }
            data.put(key, randomValue(keySpec.length == null || keySpec.length <= 0 ? DEFAULT_LENGTH : keySpec.length));
            generated.add(key);
        }
        for (Map.Entry<String, GeneratedSecret.GeneratedKey> entry : spec.keys.entrySet()) {
            GeneratedSecret.GeneratedKey keySpec = entry.getValue();
            if (keySpec == null) continue;
            if (keySpec.template != null) {
                data.put(entry.getKey(), fillTemplate(render(keySpec.template, releaseName, namespace), data));
            } else if (nonBlank(keySpec.formerDefault) && keySpec.formerDefault.equals(data.get(entry.getKey()))) {
                warnings.add(formerDefaultWarning(namespace, releaseName, secretName, volume, entry.getKey(),
                        fellBack.contains(entry.getKey())));
            }
        }

        // Only a value the chart must set on an existing account waits for a deploy that gets through.
        boolean setsAccount = usesGenerated(spec);
        boolean pending = setsAccount && (!generated.isEmpty() || isPending(existing));
        Map<String, String> changed = new LinkedHashMap<>();
        data.forEach((key, value) -> {
            if (!value.equals(current.get(key))) changed.put(key, value);
        });
        if (!changed.isEmpty()) {
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put(RELEASE_LABEL, releaseName);
            labels.put(TYPE_LABEL, TYPE_VALUE);
            Map<String, String> annotations = new LinkedHashMap<>();
            annotations.put("managed-by", "ambari-k8s-view");
            annotations.put(DESCRIPTION_ANNOTATION, capitalize(label));
            if (setsAccount && !generated.isEmpty()) annotations.put(PENDING_ANNOTATION, "true");
            kubernetes.writeOpaqueSecretKeys(namespace, secretName, changed, labels, annotations);
            // Key names only: the values never reach a log.
            LOG.info("Secret {}/{} ({}): generated {}, adopted {}, updated {}", namespace, secretName, label,
                    generated, adopted, changed.keySet());
        }

        Map<String, String> chartValues = new LinkedHashMap<>();
        if (spec.values != null) {
            for (Map.Entry<String, String> value : spec.values.entrySet()) {
                if (value.getKey() == null || value.getKey().isBlank() || value.getKey().startsWith("_")) continue;
                chartValues.put(value.getKey(), render(value.getValue() == null ? "" : value.getValue(), releaseName,
                        namespace).replace("{{secretName}}", secretName)
                        .replace("{{generated}}", String.valueOf(pending)));
            }
        }
        String notice = null;
        if (spec.notice != null && !spec.notice.isBlank()) {
            String firstKey = spec.keys.keySet().iterator().next();
            notice = render(spec.notice, releaseName, namespace).replace("{{secretName}}", secretName)
                    .replace("{{readCommand}}", readCommand(namespace, secretName, firstKey));
        }
        return new Outcome(spec.key, secretName, true, generated, adopted, chartValues, pending, notice, warnings);
    }

    /**
     * Records that a deploy which carried {@code {{generated}}} as true succeeded, so the chart has set the value on
     * the account: later deploys leave the account alone (e.g. a password the operator then changed in Superset).
     */
    public void markApplied(String namespace, String secretName) {
        kubernetes.removeSecretAnnotation(namespace, secretName, PENDING_ANNOTATION);
    }

    private static boolean usesGenerated(GeneratedSecret spec) {
        return spec.values != null && spec.values.values().stream().anyMatch(v -> v != null && v.contains("{{generated}}"));
    }

    private static boolean isPending(Secret secret) {
        return secret != null && secret.getMetadata() != null && secret.getMetadata().getAnnotations() != null
                && "true".equals(secret.getMetadata().getAnnotations().get(PENDING_ANNOTATION));
    }

    /** Why the deploy is refused when the password the release signs in to its database with is nowhere. */
    private static String lostPassword(GeneratedSecret.GeneratedKey keySpec, String namespace, String releaseName,
                                       String secretName, String volume, String key) {
        return "The volume " + volume + " of release " + releaseName + " keeps a database initialized with a password"
                + " KDPS cannot find: neither the Secret " + namespace + "/" + secretName + " nor "
                + adoptSources(keySpec, releaseName, namespace) + " holds key '" + key + "', and a new password would"
                + " lock the release out of its database. Create the Secret " + secretName + " in namespace "
                + namespace + " with key '" + key + "' set to the password the database was initialized with, then"
                + " deploy again. Deleting the volume also lets the deploy through, but only with an empty database:"
                + " everything the release kept in it would be lost for good.";
    }

    /**
     * Why the deploy is refused when the password the release signs in to its database with is nowhere and KDPS
     * cannot read the database's volume claim, so it cannot tell a new install from a database initialized before.
     */
    private static String uncheckedVolume(GeneratedSecret.GeneratedKey keySpec, String namespace, String releaseName,
                                          String secretName, String volume, String key) {
        return "KDPS could not read the volume claim " + volume + " of release " + releaseName + " (it needs"
                + " permission to get persistentvolumeclaims in namespace " + namespace + ", or the Kubernetes API did"
                + " not answer), so it cannot tell whether a database there was initialized with a password that"
                + " neither the Secret " + namespace + "/" + secretName + " nor "
                + adoptSources(keySpec, releaseName, namespace) + " holds (key '" + key + "'): a new password would"
                + " lock the release out of such a database. Give the account KDPS uses for this namespace permission to"
                + " get persistentvolumeclaims, then deploy again. When the volume keeps a database, you can instead"
                + " create the Secret " + secretName + " in namespace " + namespace + " with key '" + key + "' set to"
                + " the password the database was initialized with, then deploy again.";
    }

    /** The warning for a database that still runs with the password its chart used to set for every release. */
    private static String formerDefaultWarning(String namespace, String releaseName, String secretName, String volume,
                                               String key, boolean fellBack) {
        String where = "key '" + key + "' of the Secret " + namespace + "/" + secretName;
        String what = fellBack
                ? "No Secret holds the password the database on volume " + volume + " was initialized with any more"
                        + " (an uninstall deleted the one its chart kept): KDPS took the one the chart used to set for"
                        + " every release, which a release installed before runs with, into " + where + "."
                : "The database of release " + releaseName + " still uses the password its chart used to set for every"
                        + " release (" + where + ").";
        return what + " Anyone who reads the chart knows it: set a new password on the database user the release"
                + " signs in with (ALTER USER ... PASSWORD), write the same into " + where + ", then deploy again.";
    }

    /** The command that prints one key of the Secret. */
    static String readCommand(String namespace, String secretName, String key) {
        return "kubectl -n " + namespace + " get secret " + secretName + " -o jsonpath='{.data." + key
                + "}' | base64 -d";
    }

    /** The first value found where the key points, or null. */
    private String adopt(GeneratedSecret.GeneratedKey keySpec, String namespace, String releaseName) {
        if (keySpec.adoptFrom == null) return null;
        for (Map<String, String> source : keySpec.adoptFrom) {
            if (source == null || source.get("secret") == null || source.get("key") == null) continue;
            String value = decode(kubernetes.getSecret(namespace, render(source.get("secret"), releaseName, namespace)))
                    .get(source.get("key"));
            if (nonBlank(value)) return value;
        }
        return null;
    }

    private static String adoptSources(GeneratedSecret.GeneratedKey keySpec, String releaseName, String namespace) {
        List<String> sources = new ArrayList<>();
        if (keySpec.adoptFrom != null) {
            for (Map<String, String> source : keySpec.adoptFrom) {
                if (source != null && source.get("secret") != null) {
                    sources.add("the Secret " + render(source.get("secret"), releaseName, namespace));
                }
            }
        }
        return sources.isEmpty() ? "any other Secret" : String.join(" nor ", sources);
    }

    private String randomValue(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /** {@code ${key}} replaced by the URL-encoded value of that key. */
    static String fillTemplate(String template, Map<String, String> data) {
        Matcher m = KEY_REFERENCE.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = data.getOrDefault(m.group(1), "");
            m.appendReplacement(sb, Matcher.quoteReplacement(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String render(String template, String releaseName, String namespace) {
        return template.replace("{{releaseName}}", releaseName).replace("{{namespace}}", namespace);
    }

    private static Map<String, String> decode(Secret secret) {
        Map<String, String> out = new LinkedHashMap<>();
        if (secret == null) return out;
        if (secret.getData() != null) {
            secret.getData().forEach((key, value) -> {
                if (value != null) out.put(key, new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8));
            });
        }
        if (secret.getStringData() != null) {
            secret.getStringData().forEach((key, value) -> {
                if (value != null) out.put(key, value);
            });
        }
        return out;
    }

    /** Whether the setting at {@code path} is true in the values, or else in the form; true without a path. */
    static boolean isOn(String path, Map<String, Object> values, Map<String, Object> formValues) {
        if (path == null || path.isBlank()) return true;
        Object setting = values == null ? null : ConfigResolutionService.getByDottedPath(values, path);
        if (setting == null && formValues != null) setting = ConfigResolutionService.getByDottedPath(formValues, path);
        return setting != null && "true".equalsIgnoreCase(String.valueOf(setting).trim());
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
