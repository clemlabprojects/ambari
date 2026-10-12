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

package org.apache.ambari.view.k8s.service.deployment;

import org.apache.ambari.view.k8s.model.DeploymentMode;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.service.DependencyRules;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Checks the names a deploy gives before anything is prepared for it: the release, its namespace, its chart
 * repository, chart and chart version, and those of each of its dependencies. The names of the releases and their
 * namespaces follow the rules of Helm and Kubernetes in every mode, so a deploy refused for them would have failed in
 * either mode.
 *
 * <p>A GitOps deploy writes every name into the manifests it commits and into their paths in Git, where Flux applies
 * them with its own rights and nothing on the way refuses a malformed one: there the chart must be a chart name of the
 * chart repository, the repository a Kubernetes name and the version made of version-range characters only. A direct
 * deploy hands the chart and its version to Helm, which resolves and checks them itself: an {@code oci://} reference,
 * the URL of a chart archive or an OCI tag is accepted there, and only a line break, another control character or a
 * space within the chart is refused.
 */
public final class DeployRequestValidator {

    /** Helm's rule for a release name: a DNS-1123 subdomain. */
    static final Pattern RELEASE_NAME =
            Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*");

    /** Helm refuses longer release names: the names a chart derives from them would not fit Kubernetes' limits. */
    static final int RELEASE_NAME_MAX_LENGTH = 53;

    /** A DNS-1123 label: a namespace, the release name of a dependency, a chart repository id. */
    static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");

    static final int DNS_LABEL_MAX_LENGTH = 63;

    /** A chart name as a chart repository indexes it, and the repository alias that may precede it. */
    static final Pattern CHART_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** A chart version or SemVer range: without the line break, '#' or ':' that would end a YAML value. */
    static final Pattern CHART_VERSION = Pattern.compile("[0-9A-Za-z.+^~*<>=|, -]{1,64}");

    private static final String RELEASE_NAME_RULE = "Helm accepts at most " + RELEASE_NAME_MAX_LENGTH
            + " lower-case letters, digits, '-' and '.', starting and ending with a letter or digit, such as"
            + " trino-prod.";
    private static final String DNS_LABEL_RULE = "use at most " + DNS_LABEL_MAX_LENGTH
            + " lower-case letters, digits and '-', starting and ending with a letter or digit, such as analytics.";
    private static final String CHART_RULE = "a GitOps deploy names the chart in its HelmRelease, from the chart"
            + " repository selected for it: give the chart name (letters, digits, '.', '_' and '-'), optionally after"
            + " the name of its repository and a '/', such as trinodb/trino. The chart version goes in the version"
            + " field; a chart URL or an oci:// reference can only be deployed with Direct Helm.";
    private static final String CHART_REFERENCE_RULE = "give a chart name such as trinodb/trino, an OCI reference such"
            + " as oci://registry.example.com/charts/trino, or the URL of a chart archive, without spaces or line"
            + " breaks.";
    private static final String CHART_VERSION_RULE = "give a chart version or version range, such as 1.43.15 or"
            + " ~1.43.0, using letters, digits, spaces and . + ^ ~ * < > = | , - only.";
    private static final String CHART_VERSION_LINE_RULE = "give a chart version or version range, such as 1.43.15 or"
            + " ~1.43.0, without line breaks.";

    private DeployRequestValidator() {
    }

    /**
     * Checks a deploy and the chart repository and version given with it as query parameters (the wizard sends them
     * that way), by the rules of the mode it names: those of a GitOps deploy for {@code FLUX_GITOPS}, those of a
     * direct deploy otherwise.
     *
     * @param request the deploy
     * @param repoId  the chart repository query parameter, may be blank
     * @param version the chart version query parameter, may be blank
     * @throws IllegalArgumentException naming the field to correct and the rule it must follow
     */
    public static void validate(HelmDeployRequest request, String repoId, String version) {
        check(request, repoId, version, DeploymentMode.FLUX_GITOPS.name().equalsIgnoreCase(request.getDeploymentMode()));
    }

    /**
     * Checks a deploy by the rules of a GitOps deploy, whatever mode it names: its names are about to be written to
     * Git.
     *
     * @param request the deploy
     * @param repoId  the chart repository it is deployed from, may be blank
     * @param version the chart version it is deployed at, may be blank
     * @throws IllegalArgumentException naming the field to correct and the rule it must follow
     */
    public static void validateForGitOps(HelmDeployRequest request, String repoId, String version) {
        check(request, repoId, version, true);
    }

    private static void check(HelmDeployRequest request, String repoId, String version, boolean gitOps) {
        checkReleaseName(request.getReleaseName());
        checkDnsLabel("Namespace", request.getNamespace());
        if (gitOps) {
            checkChart("Chart", request.getChart());
            checkRepositoryIfGiven("Chart repository", request.getRepoId());
            checkRepositoryIfGiven("Chart repository", repoId);
            checkChartVersionIfGiven("Chart version", request.getVersion());
            checkChartVersionIfGiven("Chart version", version);
        } else {
            // The repository is only looked up by its id: an unknown one is refused there.
            checkChartReference("Chart", request.getChart());
            checkVersionLineIfGiven("Chart version", request.getVersion());
            checkVersionLineIfGiven("Chart version", version);
        }
        checkDependencies(request.getDependencies(), request.getFormValues(), gitOps);
        HelmDeployRequest.GitOptions git = request.getGit();
        if (gitOps && git != null && git.getPathPrefix() != null && !git.getPathPrefix().isBlank()) {
            FluxGitOpsBackend.validateRelative(Path.of(git.getPathPrefix()));
        }
    }

    /**
     * Checks a release name against Helm's rule.
     *
     * @throws IllegalArgumentException when it is missing or Helm would refuse it
     */
    public static void checkReleaseName(String releaseName) {
        if (releaseName == null || releaseName.isEmpty()) {
            throw new IllegalArgumentException("Enter a release name: " + RELEASE_NAME_RULE);
        }
        // matches() takes the whole value: an anchored find() would let a trailing line break through.
        if (releaseName.length() > RELEASE_NAME_MAX_LENGTH || !RELEASE_NAME.matcher(releaseName).matches()) {
            throw invalid("Release name", releaseName, RELEASE_NAME_RULE);
        }
    }

    /**
     * Checks a DNS-1123 label: a namespace, the release name of a dependency, a chart repository id.
     *
     * @param what what the value names, starting the message
     * @throws IllegalArgumentException when it is missing or is not a DNS-1123 label
     */
    static void checkDnsLabel(String what, String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(what + " is missing: " + DNS_LABEL_RULE);
        }
        if (value.length() > DNS_LABEL_MAX_LENGTH || !DNS_LABEL.matcher(value).matches()) {
            throw invalid(what, value, DNS_LABEL_RULE);
        }
    }

    /**
     * Checks a chart repository id, when one is given: a DNS-1123 label, the name the Flux HelmRepository of the
     * repository gets.
     *
     * @throws IllegalArgumentException when it is not a DNS-1123 label
     */
    static void checkRepositoryIfGiven(String what, String repoId) {
        if (repoId != null && !repoId.isBlank()) {
            checkDnsLabel(what, repoId);
        }
    }

    /**
     * Checks the chart of a GitOps deploy: a chart name, optionally after the name of its repository and a '/' (the
     * {@code <repo>/<chart>} form of the service definitions, such as {@code trinodb/trino}). The HelmRelease names the
     * chart within its chart repository, so a URL, an OCI reference or tag, or a deeper path cannot be written there.
     *
     * @throws IllegalArgumentException when it is missing or names anything else
     */
    static void checkChart(String what, String chart) {
        if (chart == null || chart.isBlank()) {
            throw new IllegalArgumentException(what + " is missing: " + CHART_RULE);
        }
        // Surrounding spaces are dropped where the chart is used; a line break or another control character is not.
        boolean controlCharacters = chart.chars().anyMatch(Character::isISOControl);
        String reference = chart.trim();
        int slash = reference.indexOf('/');
        String name = slash < 0 ? reference : reference.substring(slash + 1);
        boolean repositoryValid = slash < 0 || CHART_NAME.matcher(reference.substring(0, slash)).matches();
        if (controlCharacters || !repositoryValid || !CHART_NAME.matcher(name).matches()) {
            throw invalid(what, chart, CHART_RULE);
        }
    }

    /**
     * Checks the chart of a direct deploy: any reference Helm resolves, such as {@code trinodb/trino}, an
     * {@code oci://} reference with or without a tag, or the URL of a chart archive. Helm checks it further.
     *
     * @throws IllegalArgumentException when it is missing, or holds a space, a line break or another control character
     */
    static void checkChartReference(String what, String chart) {
        if (chart == null || chart.isBlank()) {
            throw new IllegalArgumentException(what + " is missing: " + CHART_REFERENCE_RULE);
        }
        // Surrounding spaces are tolerated, as for the chart of a GitOps deploy; none within the reference.
        if (chart.chars().anyMatch(Character::isISOControl) || chart.trim().chars().anyMatch(Character::isWhitespace)) {
            throw invalid(what, chart, CHART_REFERENCE_RULE);
        }
    }

    /**
     * Checks the chart version of a GitOps deploy, when one is given.
     *
     * @throws IllegalArgumentException when it holds anything but the characters of a version or version range
     */
    static void checkChartVersionIfGiven(String what, String version) {
        if (version != null && !version.isBlank() && !CHART_VERSION.matcher(version).matches()) {
            throw invalid(what, version, CHART_VERSION_RULE);
        }
    }

    /**
     * Checks the chart version of a direct deploy, when one is given: Helm reads it as a version constraint and checks
     * it itself.
     *
     * @throws IllegalArgumentException when it holds a line break or another control character
     */
    static void checkVersionLineIfGiven(String what, String version) {
        if (version != null && version.chars().anyMatch(Character::isISOControl)) {
            throw invalid(what, version, CHART_VERSION_LINE_RULE);
        }
    }

    /**
     * Checks the dependencies of a deploy: the name of each, and the release name, namespace (also the one its
     * {@code namespaceFromForm} field picks), chart repository, chart and chart version it gives, the chart, its
     * repository and version by the rules of the deploy's mode.
     *
     * @param dependencies the dependencies by name, may be {@code null}
     * @param formValues   the deploy form, may be {@code null}
     * @param gitOps       whether the dependencies are written to Git
     * @throws IllegalArgumentException naming the dependency and the field to correct
     */
    static void checkDependencies(Map<String, Object> dependencies, Map<String, Object> formValues, boolean gitOps) {
        if (dependencies == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : dependencies.entrySet()) {
            checkDnsLabel("Dependency name", entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> given)) {
                continue;
            }
            Map<String, Object> spec = new LinkedHashMap<>();
            given.forEach((key, value) -> spec.put(String.valueOf(key), value));
            DependencyRules.applyNamespaceFromForm(spec, formValues);
            String what = "Dependency '" + entry.getKey() + "': ";
            String releaseName = text(spec.get("releaseName"));
            if (releaseName != null && !releaseName.isBlank()) {
                checkDnsLabel(what + "release name", releaseName);
            }
            String namespace = text(spec.get("namespace"));
            if (namespace != null && !namespace.isBlank()) {
                checkDnsLabel(what + "namespace", namespace);
            }
            String chart = text(spec.get("chart"));
            if (gitOps) {
                checkRepositoryIfGiven(what + "chart repository", text(spec.get("repoId")));
                if (chart != null && !chart.isBlank()) {
                    checkChart(what + "chart", chart);
                }
                checkChartVersionIfGiven(what + "chart version", text(spec.get("chartVersion")));
                checkChartVersionIfGiven(what + "chart version", text(spec.get("version")));
            } else {
                if (chart != null && !chart.isBlank()) {
                    checkChartReference(what + "chart", chart);
                }
                checkVersionLineIfGiven(what + "chart version", text(spec.get("chartVersion")));
                checkVersionLineIfGiven(what + "chart version", text(spec.get("version")));
            }
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static IllegalArgumentException invalid(String what, String value, String rule) {
        return new IllegalArgumentException(what + " '" + shown(value) + "' is not valid: " + rule);
    }

    /**
     * The value as the message shows it: control characters, line breaks among them, as '?', so the message stays
     * one line in the log, and a long value cut short.
     */
    static String shown(String value) {
        String visible = value.codePoints()
                .map(c -> Character.isISOControl(c) ? '?' : c)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        return visible.length() > 80 ? visible.substring(0, 80) + "..." : visible;
    }
}
