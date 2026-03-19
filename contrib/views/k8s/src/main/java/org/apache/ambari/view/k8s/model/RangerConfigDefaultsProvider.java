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
 * Provides default Ranger configuration maps for a specific service type.
 */
public interface RangerConfigDefaultsProvider {

    /**
     * @return the service type this provider supports (lower-case, e.g. "trino")
     */
    String serviceType();

    /**
     * @param serviceName Ranger service name used in property prefixes
     * @return default plugin properties map
     */
    Map<String, String> pluginProperties(String serviceName);

    /**
     * @param serviceName Ranger service name used in property prefixes
     * @return default security properties map
     */
    Map<String, String> security(String serviceName);

    /**
     * @return default audit properties map
     */
    Map<String, String> audit();

    /**
     * @return default policymgr SSL properties map
     */
    Map<String, String> policymgrSsl();
}
