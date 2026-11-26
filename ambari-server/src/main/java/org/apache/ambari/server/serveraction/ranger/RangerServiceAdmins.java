/*
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

package org.apache.ambari.server.serveraction.ranger;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The administrators of a Ranger service: users and groups holding, with delegated administration, every access
 * of the service's service-wide policies, the ones Ranger creates with a service for each kind of resource
 * ({@code all - catalog}, {@code all - queryid}...), whose resources are all {@code *}. Ranger gives its lookup user
 * the same rights in those policies when it creates the service.
 *
 * <p>The KDPS view keeps the same rules for a Ranger it reaches directly ({@code OmAtlasProvisioning}); both must
 * change together.
 */
final class RangerServiceAdmins {

    private RangerServiceAdmins() {
    }

    /** Users and groups. */
    static final class Principals {
        final Set<String> users = new LinkedHashSet<>();
        final Set<String> groups = new LinkedHashSet<>();

        boolean isEmpty() {
            return users.isEmpty() && groups.isEmpty();
        }

        void addAll(Principals other) {
            users.addAll(other.users);
            groups.addAll(other.groups);
        }
    }

    /**
     * Whether the policy is service-wide: an access policy whose every resource is {@code *} alone, without
     * exclusion.
     */
    static boolean isServiceWide(JsonObject policy) {
        if (!EnsureRangerPolicyServerAction.isAccessPolicy(policy)
                || !policy.has("resources") || !policy.get("resources").isJsonObject()) {
            return false;
        }
        JsonObject resources = policy.getAsJsonObject("resources");
        if (resources.size() == 0) {
            return false;
        }
        for (String name : resources.keySet()) {
            JsonElement resource = resources.get(name);
            if (!resource.isJsonObject()) {
                return false;
            }
            JsonObject r = resource.getAsJsonObject();
            if (r.has("isExcludes") && r.get("isExcludes").getAsBoolean()) {
                return false;
            }
            Set<String> values = strings(r.get("values"));
            if (values.size() != 1 || !values.contains("*")) {
                return false;
            }
        }
        return true;
    }

    /** The access types the policy's allow items grant. */
    static Set<String> grantedAccesses(JsonObject policy) {
        Set<String> out = new TreeSet<>();
        for (JsonElement e : items(policy)) {
            out.addAll(allowedTypes(e.getAsJsonObject()));
        }
        return out;
    }

    /**
     * Adds to a service-wide policy, in an item of their own, the users and groups that do not already hold every
     * access it grants with delegated administration.
     *
     * @return those added; empty when the policy was left unchanged
     */
    static Principals grant(JsonObject policy, Set<String> users, Set<String> groups) {
        Principals added = new Principals();
        Set<String> accesses = grantedAccesses(policy);
        if (accesses.isEmpty()) {
            return added;
        }
        Set<String> coveredUsers = new LinkedHashSet<>();
        Set<String> coveredGroups = new LinkedHashSet<>();
        for (JsonElement e : items(policy)) {
            JsonObject item = e.getAsJsonObject();
            boolean delegateAdmin = item.has("delegateAdmin") && !item.get("delegateAdmin").isJsonNull()
                    && item.get("delegateAdmin").getAsBoolean();
            if (delegateAdmin && allowedTypes(item).containsAll(accesses)) {
                coveredUsers.addAll(strings(item.get("users")));
                coveredGroups.addAll(strings(item.get("groups")));
            }
        }
        for (String u : users) {
            if (!coveredUsers.contains(u)) {
                added.users.add(u);
            }
        }
        for (String g : groups) {
            if (!coveredGroups.contains(g)) {
                added.groups.add(g);
            }
        }
        if (added.isEmpty()) {
            return added;
        }
        JsonObject item = new JsonObject();
        item.add("users", array(added.users));
        item.add("groups", array(added.groups));
        item.add("roles", new JsonArray());
        JsonArray accessArray = new JsonArray();
        for (String type : accesses) {
            JsonObject access = new JsonObject();
            access.addProperty("type", type);
            access.addProperty("isAllowed", true);
            accessArray.add(access);
        }
        item.add("accesses", accessArray);
        item.add("conditions", new JsonArray());
        item.addProperty("delegateAdmin", true);
        JsonArray policyItems = items(policy);
        policyItems.add(item);
        policy.add("policyItems", policyItems);
        return added;
    }

    /**
     * Takes the users and groups out of every allow item of the policy; an item left naming nobody is dropped.
     *
     * @return whether the policy changed
     */
    static boolean revoke(JsonObject policy, Set<String> users, Set<String> groups) {
        JsonArray kept = new JsonArray();
        boolean changed = false;
        for (JsonElement e : items(policy)) {
            JsonObject item = e.getAsJsonObject();
            Set<String> itemUsers = strings(item.get("users"));
            Set<String> itemGroups = strings(item.get("groups"));
            boolean hit = itemUsers.removeAll(users) | itemGroups.removeAll(groups);
            if (!hit) {
                kept.add(item);
                continue;
            }
            changed = true;
            if (itemUsers.isEmpty() && itemGroups.isEmpty() && strings(item.get("roles")).isEmpty()) {
                continue;
            }
            JsonObject rest = item.deepCopy();
            rest.add("users", array(itemUsers));
            rest.add("groups", array(itemGroups));
            kept.add(rest);
        }
        if (changed) {
            policy.add("policyItems", kept);
        }
        return changed;
    }

    private static JsonArray items(JsonObject policy) {
        return policy.has("policyItems") && policy.get("policyItems").isJsonArray()
                ? policy.getAsJsonArray("policyItems") : new JsonArray();
    }

    private static Set<String> allowedTypes(JsonObject item) {
        Set<String> out = new TreeSet<>();
        if (item.has("accesses") && item.get("accesses").isJsonArray()) {
            for (JsonElement a : item.getAsJsonArray("accesses")) {
                JsonObject access = a.getAsJsonObject();
                boolean allowed = !access.has("isAllowed") || access.get("isAllowed").isJsonNull()
                        || access.get("isAllowed").getAsBoolean();
                if (allowed && access.has("type")) {
                    out.add(access.get("type").getAsString());
                }
            }
        }
        return out;
    }

    private static Set<String> strings(JsonElement array) {
        Set<String> out = new LinkedHashSet<>();
        if (array != null && array.isJsonArray()) {
            for (JsonElement v : array.getAsJsonArray()) {
                if (v != null && !v.isJsonNull()) {
                    out.add(v.getAsString());
                }
            }
        }
        return out;
    }

    private static JsonArray array(Set<String> values) {
        JsonArray out = new JsonArray();
        values.forEach(out::add);
        return out;
    }
}
