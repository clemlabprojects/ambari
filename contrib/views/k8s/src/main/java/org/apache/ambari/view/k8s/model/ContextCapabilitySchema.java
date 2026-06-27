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

import java.util.ArrayList;
import java.util.List;

import com.google.gson.annotations.SerializedName;

/**
 * A capability fragment of the platform-context schema, loaded from
 * {@code KDPS/contexts/capabilities/<capability>.json}. The full context schema is the merge
 * of every fragment present, so a downstream company adds a capability by dropping a new file.
 *
 * @see org.apache.ambari.view.k8s.service.ContextSchemaService
 */
public class ContextCapabilitySchema {

    public String capability;          // e.g. "atlas"
    public String label;               // human-readable, e.g. "Apache Atlas"
    public int order = 100;            // display order
    public List<ContextFieldDef> fields = new ArrayList<>();

    /** A single declared field a context provides for this capability. */
    public static class ContextFieldDef {
        public String name;            // field id within the capability ("<capability>.<name>")
        public String label;
        public String type = "string"; // string | password | enum | boolean
        public boolean secret = false; // encrypted in instance data, never returned by the API
        /** Key the managed resolver uses to fill this field live from Ambari (null → operator-supplied). */
        public String managedResolver;
        /** Optional scope: "EXTERNAL" | "MANAGED" — null means both. */
        public String appliesTo;
        @SerializedName("default")
        public Object defaultValue;
        public List<String> options;   // for type=enum
        public String placeholder;
        public String help;
    }
}
