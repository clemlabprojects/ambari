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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A GitOps output profile: which files a GitOps deploy writes into its Git repository, and how it delivers them.
 *
 * <p>Two kinds. {@link #KIND_FLUX} is the view's Flux layout (a HelmRelease and a HelmRepository per release, written
 * by the Flux backend's own code). {@link #KIND_FILES} writes the complete chart, as published, under
 * {@link #chartPath}, and the files its {@link #files} rules describe around it, rendered by {@link FileTemplate}
 * from a context that never holds a secret value.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitOpsProfile {

    public static final String KIND_FLUX = "flux";
    public static final String KIND_FILES = "files";

    public static final String MODE_ALWAYS = "always";
    public static final String MODE_CREATE_IF_MISSING = "createIfMissing";

    public static final String DELIVERY_PULL_REQUEST = "pullRequest";
    public static final String DELIVERY_COMMIT = "commit";

    /** Unique; also how a deploy names the profile it used. */
    public String name;
    public String description;
    public String kind = KIND_FILES;
    /** Shipped with the view: read-only, copied to be adapted. */
    public boolean builtIn;
    /** Incremented on each save; recorded on every deploy that used the profile. */
    public int version = 1;
    /** Where the complete chart is copied, relative to the repository root; null for no chart in the repository. */
    public String chartPath;
    /** Constants of the profile, read by templates as {@code vars.<name>}. */
    public Map<String, String> variables = new LinkedHashMap<>();
    public Delivery delivery = new Delivery();
    /** The files around the chart. */
    public List<FileRule> files = new ArrayList<>();
    /** Globs (relative to the repository root) of files the profile never writes nor deletes. */
    public List<String> protect = new ArrayList<>();

    /** How rendered files reach the repository. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Delivery {
        public String mode = DELIVERY_PULL_REQUEST;
        /** Start of the name of the branch a pull request is opened from. */
        public String branchPrefix = "kdps/";
        /** Template of a tag created on the merged commit; null for none. */
        public String tag;
    }

    /** One file around the chart. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileRule {
        /** Template of the path, relative to the repository root. */
        public String path;
        public String mode = MODE_ALWAYS;
        /** Template of the content. */
        public String content;
    }

    /** The view's Flux layout, as GitOps deploys have always written it. */
    public static GitOpsProfile flux() {
        GitOpsProfile p = new GitOpsProfile();
        p.name = "flux";
        p.description = "Flux: a HelmRelease and a HelmRepository per release; secret values in a Secret the "
                + "HelmRelease reads.";
        p.kind = KIND_FLUX;
        p.builtIn = true;
        return p;
    }
}
