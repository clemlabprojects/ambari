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

package org.apache.ambari.view.k8s.model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Registry for Ranger config defaults providers, keyed by service type.
 */
public final class RangerConfigDefaultsRegistry {

    private static final Map<String, RangerConfigDefaultsProvider> PROVIDERS = new LinkedHashMap<>();

    static {
        register(new TrinoRangerConfigDefaultsProvider());
    }

    private RangerConfigDefaultsRegistry() {}

    /**
     * Resolve a provider for the given service type.
     *
     * @param serviceType service type (case-insensitive)
     * @return provider or null when none registered
     */
    public static RangerConfigDefaultsProvider resolve(String serviceType) {
        if (serviceType == null || serviceType.isBlank()) {
            return null;
        }
        return PROVIDERS.get(serviceType.toLowerCase(Locale.ROOT));
    }

    /**
     * Register a provider for its service type.
     *
     * @param provider provider to register
     */
    public static void register(RangerConfigDefaultsProvider provider) {
        if (provider == null || provider.serviceType() == null || provider.serviceType().isBlank()) {
            return;
        }
        PROVIDERS.put(provider.serviceType().toLowerCase(Locale.ROOT), provider);
    }
}
