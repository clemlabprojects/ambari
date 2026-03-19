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

import java.util.Map;

/**
 * Ranger defaults provider for Trino.
 */
public final class TrinoRangerConfigDefaultsProvider implements RangerConfigDefaultsProvider {

    @Override
    public String serviceType() {
        return "trino";
    }

    @Override
    public Map<String, String> pluginProperties(String serviceName) {
        return RangerTrinoConfigDefaults.pluginProperties(serviceName);
    }

    @Override
    public Map<String, String> security(String serviceName) {
        return RangerTrinoConfigDefaults.security(serviceName);
    }

    @Override
    public Map<String, String> audit() {
        return RangerTrinoConfigDefaults.audit();
    }

    @Override
    public Map<String, String> policymgrSsl() {
        return RangerTrinoConfigDefaults.policymgrSsl();
    }
}
