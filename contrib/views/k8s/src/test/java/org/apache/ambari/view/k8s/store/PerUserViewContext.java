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

package org.apache.ambari.view.k8s.store;

import org.apache.ambari.view.ViewContext;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A mocked view instance that behaves like Ambari's: one DataStore for the instance, and instance data scoped to
 * the user of the current request ({@link #as}), as {@code ViewInstanceEntity} does. Background work runs with
 * no user ({@code null}).
 */
public final class PerUserViewContext {

    public final ViewContext context = mock(ViewContext.class);
    public final InMemoryDataStore dataStore = new InMemoryDataStore();
    /** Instance data by user, as Ambari keeps it. */
    public final Map<String, Map<String, String>> instanceDataByUser = new ConcurrentHashMap<>();
    private volatile String user;

    /**
     * @param instanceName the view instance name
     * @param workDir      the view working directory (kubeconfig file, caches)
     */
    public PerUserViewContext(String instanceName, Path workDir) {
        when(context.getInstanceName()).thenReturn(instanceName);
        when(context.getProperties()).thenReturn(Map.of("k8s.view.working.dir", workDir.toString()));
        when(context.getDataStore()).thenReturn(dataStore);
        when(context.getUsername()).thenAnswer(inv -> user);
        when(context.getInstanceData()).thenAnswer(inv -> Map.copyOf(own()));
        when(context.getInstanceData(anyString())).thenAnswer(inv -> own().get(inv.getArgument(0, String.class)));
        doAnswer(inv -> own().put(inv.getArgument(0), inv.getArgument(1))).when(context).putInstanceData(anyString(), anyString());
        doAnswer(inv -> own().remove(inv.getArgument(0, String.class))).when(context).removeInstanceData(anyString());
    }

    /** Makes the following calls those of {@code userName}'s requests; {@code null} for background work. */
    public PerUserViewContext as(String userName) {
        this.user = userName;
        return this;
    }

    /** The instance data of one user, which is all that user's requests see. */
    public Map<String, String> instanceDataOf(String userName) {
        return instanceDataByUser.computeIfAbsent(String.valueOf(userName), k -> new ConcurrentHashMap<>());
    }

    private Map<String, String> own() {
        // Ambari answers a request without a user with no instance data at all.
        return user == null ? new HashMap<>() : instanceDataOf(user);
    }
}
