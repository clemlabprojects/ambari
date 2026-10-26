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

package org.apache.ambari.view.k8s.service.cluster;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * A Kubernetes or OpenShift cluster KDPS connects to, by its id. The default cluster is the connection a view instance
 * had before it knew several clusters: its settings and files keep their names. Every other cluster keeps the same
 * settings under its own prefix, and its files in its own folder.
 *
 * @param id lowercase letters, digits and dashes, starting and ending with a letter or a digit, at most 32 characters
 */
public record ClusterRef(String id) {

    // Before DEFAULT, which the constructor checks against it.
    private static final Pattern ID = Pattern.compile("[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?");

    /** The id of the default cluster. */
    public static final String DEFAULT_ID = "default";

    /** The default cluster: the connection configured before KDPS knew several clusters. */
    public static final ClusterRef DEFAULT = new ClusterRef(DEFAULT_ID);

    /** Prefix of the settings of a cluster other than the default one, followed by its id and a dot. */
    static final String SETTINGS_PREFIX = "cluster.";

    /** Folder, under the view's working directory, of the files of the clusters other than the default one. */
    static final String FOLDER = "clusters";

    /**
     * @throws IllegalArgumentException when the id is not one a cluster may have
     */
    public ClusterRef {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("A cluster id is made of lowercase letters, digits and dashes, starts and "
                    + "ends with a letter or a digit, and has at most 32 characters.");
        }
    }

    /**
     * The cluster of an id.
     *
     * @throws IllegalArgumentException when the id is not one a cluster may have
     */
    public static ClusterRef of(String id) {
        return DEFAULT_ID.equals(id) ? DEFAULT : new ClusterRef(id);
    }

    public boolean isDefault() {
        return DEFAULT_ID.equals(id);
    }

    /** The key a setting of this cluster is kept under: the key itself for the default cluster. */
    public String settingKey(String key) {
        return isDefault() ? key : SETTINGS_PREFIX + id + "." + key;
    }

    /** The folder of this cluster's files under the view's working directory: that directory for the default one. */
    public Path folder(Path workingDirectory) {
        return isDefault() ? workingDirectory : workingDirectory.resolve(FOLDER).resolve(id);
    }

    @Override
    public String toString() {
        return id;
    }
}
