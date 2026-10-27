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

import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The default cluster may be protected as well; a cluster the view does not know is not protected by it. */
class ProtectedClustersTest {

    @TempDir
    Path workDir;

    @Test
    void theDefaultClusterCanBeProtectedToo() {
        PerUserViewContext view = new PerUserViewContext("protected", workDir).as("lead");
        assertDoesNotThrow(() -> ProtectedClusters.require(view.context, ClusterRef.DEFAULT, null, "the uninstall"));

        new ClusterRegistry(new InstanceSettings(view.context)).save(new KubernetesCluster("default", "Main", "", true));

        assertThrows(ProtectedClusters.NotConfirmedException.class,
                () -> ProtectedClusters.require(view.context, ClusterRef.DEFAULT, null, "the uninstall"));
        assertDoesNotThrow(() -> ProtectedClusters.require(view.context, ClusterRef.DEFAULT, "Main", "the uninstall"));
        assertDoesNotThrow(() -> ProtectedClusters.require(view.context, ClusterRef.of("gone"), null, "the uninstall"));
    }
}
