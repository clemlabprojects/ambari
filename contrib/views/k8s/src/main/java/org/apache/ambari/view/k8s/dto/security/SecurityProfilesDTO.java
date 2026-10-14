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

package org.apache.ambari.view.k8s.dto.security;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Container for multiple named security profiles.
 */
public class SecurityProfilesDTO {
    public String defaultProfile;
    public Map<String, SecurityConfigDTO> profiles = new HashMap<>();
    /**
     * Set when some stored profiles could not be read and are missing from {@link #profiles}. Such a set is only
     * good for reading the profiles it holds: nothing writes it back nor removes a profile because it is absent.
     */
    public boolean incomplete;
    /**
     * On a save, the names of the profiles the sender read before editing them (the Global Security page sends the
     * ones it shows). Only those it leaves out of {@link #profiles} are removed: a profile created after it read
     * them, or one it never showed because its read failed, is not removed for being absent. When null, every
     * stored profile left out is removed. Never set on a read.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<String> loadedProfiles;
}
