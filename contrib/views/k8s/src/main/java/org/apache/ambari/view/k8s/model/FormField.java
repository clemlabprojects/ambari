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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Minimal form field descriptor used to deserialize service.json (KDPS) forms.
 * Mirrors the structure already consumed by the frontend dynamic form logic.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FormField {
    public String name;
    public String label;
    public String type; // string, boolean, number, select, group, k8s-discovery, etc.
    public boolean required;
    public Object defaultValue;
    public String help;
    public boolean excludeFromValues;
    public String serviceType; // for service-select / discovery backed fields
    public String discoveryType; // e.g. monitoring-discovery

    // For discovery/selectors
    public String lookupLabel;
    public String placeholder;
    public String tooltip;

    // For select fields
    public List<Option> options;

    // For grouped fields
    public List<FormField> fields;

    // Simple condition support: { field, value }
    public Map<String, Object> condition;

    public static class Option {
        public String label;
        public String value;
    }
}
