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

package org.apache.ambari.view.k8s.service.gitops;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a {@link GitOpsProfile} of kind {@link GitOpsProfile#KIND_FILES} into the files a deploy writes around the
 * chart, and checks them before anything reaches Git: every path stays inside the repository, no rule writes under the
 * chart path nor over a protected file, two rules never write the same path, and no secret value of the deploy
 * appears in a path or a content.
 */
public final class ProfileRenderer {

    private static final Pattern PROFILE_NAME = Pattern.compile("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?");
    /** Shorter secrets are too common as plain text ("true", "8443") to be told apart from other content. */
    static final int MIN_SECRET_LENGTH = 6;

    /**
     * What a deploy gives the templates. {@code values} are the chart values without their secret values: the
     * caller passes the public half of the deploy's split (see {@code GitOpsSecretValues}).
     */
    public record RenderContext(String release, String namespace, String service, String chartName,
                                String chartVersion, String chartRepository, Map<String, Object> values,
                                List<String> images, List<String> instances, String deployedBy, Instant timestamp,
                                String valuesSecret) {
    }

    /** A file to write: its path relative to the repository root, its content, and its write mode. */
    public record RenderedFile(String path, String content, String mode) {
    }

    private ProfileRenderer() {
    }

    /**
     * Checks a profile as it is saved: name, kind, write modes, delivery, and that every path template and the chart
     * path are relative paths inside the repository. Templates are rendered at deploy time, against a real context.
     *
     * @throws IllegalArgumentException naming what is wrong
     */
    public static void validate(GitOpsProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (profile.name == null || !PROFILE_NAME.matcher(profile.name).matches()) {
            throw new IllegalArgumentException("The profile name must be lower-case letters, digits and dashes.");
        }
        if (!GitOpsProfile.KIND_FILES.equals(profile.kind) && !GitOpsProfile.KIND_FLUX.equals(profile.kind)) {
            throw new IllegalArgumentException("Unknown profile kind '" + profile.kind + "'.");
        }
        if (GitOpsProfile.KIND_FLUX.equals(profile.kind)) {
            return;
        }
        if (profile.chartPath != null && !profile.chartPath.isBlank()) {
            repositoryPath(profile.chartPath, "the chart path");
        }
        if (profile.delivery == null || !(GitOpsProfile.DELIVERY_PULL_REQUEST.equals(profile.delivery.mode)
                || GitOpsProfile.DELIVERY_COMMIT.equals(profile.delivery.mode))) {
            throw new IllegalArgumentException("The delivery must be 'pullRequest' or 'commit'.");
        }
        if (GitOpsProfile.DELIVERY_PULL_REQUEST.equals(profile.delivery.mode)
                && (profile.delivery.branchPrefix == null || profile.delivery.branchPrefix.isBlank())) {
            throw new IllegalArgumentException("A pull-request delivery needs a branch prefix.");
        }
        for (GitOpsProfile.FileRule rule : profile.files == null ? List.<GitOpsProfile.FileRule>of() : profile.files) {
            if (rule == null || rule.path == null || rule.path.isBlank()) {
                throw new IllegalArgumentException("Every file rule needs a path.");
            }
            if (!GitOpsProfile.MODE_ALWAYS.equals(rule.mode) && !GitOpsProfile.MODE_CREATE_IF_MISSING.equals(rule.mode)) {
                throw new IllegalArgumentException("File " + rule.path + ": the mode must be 'always' or "
                        + "'createIfMissing'.");
            }
            // Placeholders are replaced by a safe segment, so that only the literal parts are judged here.
            repositoryPath(rule.path.replaceAll("\\{\\{[^}]*}}", "x"), "file " + rule.path);
        }
    }

    /**
     * Renders the files of a profile for one deploy.
     *
     * @param profile      a profile of kind {@link GitOpsProfile#KIND_FILES}
     * @param ctx          the deploy's context
     * @param secretValues the secret values of the deploy: none may appear in a rendered path or content
     * @return the files, in rule order
     * @throws FileTemplate.TemplateException when a template cannot be rendered
     * @throws IllegalArgumentException when a rendered path is outside the repository, under the chart path, protected,
     *                                  or written by two rules
     * @throws IllegalStateException when a rendered file holds a secret value (the value itself is never named)
     */
    public static List<RenderedFile> render(GitOpsProfile profile, RenderContext ctx, Collection<String> secretValues) {
        validate(profile);
        if (!GitOpsProfile.KIND_FILES.equals(profile.kind)) {
            throw new IllegalArgumentException("Profile " + profile.name + " is written by the Flux backend, not "
                    + "rendered from file rules.");
        }
        Map<String, Object> context = context(profile, ctx);
        String chartPath = profile.chartPath == null || profile.chartPath.isBlank() ? null
                : repositoryPath(profile.chartPath, "the chart path");
        List<PathMatcher> protect = new ArrayList<>();
        for (String glob : profile.protect == null ? List.<String>of() : profile.protect) {
            if (glob != null && !glob.isBlank()) {
                protect.add(FileSystems.getDefault().getPathMatcher("glob:" + glob.trim()));
            }
        }
        Set<String> secrets = new LinkedHashSet<>();
        for (String s : secretValues == null ? List.<String>of() : secretValues) {
            if (s != null && s.length() >= MIN_SECRET_LENGTH) {
                secrets.add(s);
            }
        }

        List<RenderedFile> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (GitOpsProfile.FileRule rule : profile.files) {
            String path = repositoryPath(FileTemplate.render("path of " + rule.path, rule.path, context),
                    "file " + rule.path);
            if (chartPath != null && (path.equals(chartPath) || path.startsWith(chartPath + "/"))) {
                throw new IllegalArgumentException("File " + path + " is under the chart path " + chartPath
                        + ": the chart is copied as published and no rule may write there.");
            }
            for (PathMatcher m : protect) {
                if (m.matches(Path.of(path))) {
                    throw new IllegalArgumentException("File " + path + " is protected by the profile and is never "
                            + "written.");
                }
            }
            if (!seen.add(path)) {
                throw new IllegalArgumentException("Two file rules write " + path + ".");
            }
            String content = FileTemplate.render(path, rule.content, context);
            for (String secret : secrets) {
                if (path.contains(secret) || content.contains(secret)) {
                    throw new IllegalStateException("File " + path + " would hold a password or secret of this "
                            + "deploy; nothing was written. Secrets stay in the cluster: refer to the Secret by name.");
                }
            }
            out.add(new RenderedFile(path, content, rule.mode));
        }
        return out;
    }

    /** The fields the templates see. */
    static Map<String, Object> context(GitOpsProfile profile, RenderContext ctx) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("release", ctx.release());
        c.put("namespace", ctx.namespace());
        c.put("service", ctx.service());
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("name", ctx.chartName());
        chart.put("version", ctx.chartVersion());
        chart.put("repository", ctx.chartRepository());
        c.put("chart", chart);
        c.put("values", ctx.values() == null ? Map.of() : ctx.values());
        c.put("images", ctx.images() == null ? List.of() : ctx.images());
        c.put("instances", ctx.instances() == null ? List.of() : ctx.instances());
        c.put("vars", profile.variables == null ? Map.of() : new LinkedHashMap<>(profile.variables));
        c.put("chartPath", profile.chartPath);
        c.put("deployedBy", ctx.deployedBy());
        c.put("timestamp", ctx.timestamp() == null ? null : ctx.timestamp().toString());
        // The Kubernetes Secret holding the deploy's secret values, for the tool deploying the chart to add to its values; its
        // name only, never its content.
        Map<String, Object> secretValues = new LinkedHashMap<>();
        secretValues.put("secretName", ctx.valuesSecret());
        secretValues.put("key", ctx.valuesSecret() == null ? null : "values.yaml");
        c.put("secretValues", secretValues);
        return c;
    }

    /**
     * A path relative to the repository root, normalised: no leading slash, no backslash, no empty, "." or ".."
     * segment, and nothing in Git's own directory.
     *
     * @throws IllegalArgumentException naming {@code what} otherwise
     */
    public static String repositoryPath(String path, String what) {
        String p = path == null ? "" : path.trim();
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty() || p.startsWith("/") || p.contains("\\") || p.contains(":") || p.contains("\0")) {
            throw new IllegalArgumentException(what + " must be a path relative to the repository root.");
        }
        for (String segment : p.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(what + " must stay inside the repository (no empty, '.' or '..' "
                        + "part).");
            }
            if (".git".equals(segment)) {
                throw new IllegalArgumentException(what + " cannot be inside Git's own directory.");
            }
        }
        return p;
    }
}
