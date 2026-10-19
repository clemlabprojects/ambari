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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ambari.view.k8s.store.InstanceSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The GitOps output profiles of the view instance: the built-in ones, and those an administrator saved, kept as JSON
 * in the view settings ({@value #KEY_PREFIX}{@code <name>}, with the list of names under {@value #INDEX_KEY}).
 */
public class GitOpsProfileStore {

    static final String KEY_PREFIX = "gitops.profile.";
    static final String INDEX_KEY = "gitops.profiles";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final InstanceSettings settings;

    public GitOpsProfileStore(InstanceSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** The built-in profiles, which cannot be changed nor deleted. */
    public static List<GitOpsProfile> builtIns() {
        return List.of(GitOpsProfile.flux());
    }

    /** Every profile: the built-in ones, then the saved ones by name. */
    public List<GitOpsProfile> list() {
        List<GitOpsProfile> out = new ArrayList<>(builtIns());
        for (String name : names()) {
            GitOpsProfile p = read(name);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * A profile by name; the Flux one when the name is blank, which is how releases deployed before profiles existed
     * name theirs.
     *
     * @return the profile, or {@code null} when there is none of that name
     */
    public GitOpsProfile get(String name) {
        if (name == null || name.isBlank()) {
            return GitOpsProfile.flux();
        }
        for (GitOpsProfile builtIn : builtIns()) {
            if (builtIn.name.equals(name.trim())) {
                return builtIn;
            }
        }
        return read(name.trim());
    }

    /**
     * Saves a profile: checked, its version incremented over the saved one's.
     *
     * @return the profile as saved
     * @throws IllegalArgumentException when it is not valid or takes a built-in profile's name
     */
    public synchronized GitOpsProfile save(GitOpsProfile profile) {
        ProfileRenderer.validate(profile);
        if (!GitOpsProfile.KIND_FILES.equals(profile.kind)) {
            throw new IllegalArgumentException("Only file profiles can be saved; the Flux layout is built in.");
        }
        for (GitOpsProfile builtIn : builtIns()) {
            if (builtIn.name.equals(profile.name)) {
                throw new IllegalArgumentException("'" + profile.name + "' is a built-in profile: copy it under another"
                        + " name to adapt it.");
            }
        }
        GitOpsProfile previous = read(profile.name);
        profile.builtIn = false;
        profile.version = previous == null ? 1 : previous.version + 1;
        settings.putText(KEY_PREFIX + profile.name, write(profile));
        List<String> names = names();
        if (!names.contains(profile.name)) {
            names.add(profile.name);
            settings.put(INDEX_KEY, write(names));
        }
        return profile;
    }

    /**
     * Deletes a saved profile. Releases deployed with it keep its name in their record; their next deploy or uninstall
     * stops, naming it, until it is saved again or the release is deployed with another profile.
     *
     * @throws IllegalArgumentException for a built-in profile
     */
    public synchronized void delete(String name) {
        for (GitOpsProfile builtIn : builtIns()) {
            if (builtIn.name.equals(name)) {
                throw new IllegalArgumentException("'" + name + "' is a built-in profile and cannot be deleted.");
            }
        }
        settings.putText(KEY_PREFIX + name, null);
        List<String> names = names();
        if (names.remove(name)) {
            settings.put(INDEX_KEY, write(names));
        }
    }

    private List<String> names() {
        String index = settings.get(INDEX_KEY);
        if (index == null || index.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(JSON.readValue(index, new TypeReference<List<String>>() { }));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The list of GitOps profiles cannot be read: " + e.getOriginalMessage(), e);
        }
    }

    private GitOpsProfile read(String name) {
        String json = settings.getText(KEY_PREFIX + name);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, GitOpsProfile.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The GitOps profile '" + name + "' cannot be read: "
                    + e.getOriginalMessage(), e);
        }
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write the GitOps profile: " + e.getOriginalMessage(), e);
        }
    }
}
