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

package org.apache.ambari.server.state.oidc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class OidcServiceDescriptor {
  static final String KEY_NAME = "name";
  static final String KEY_ENABLED = "enabled";
  static final String KEY_CONFIGURATIONS = "configurations";
  static final String KEY_CLIENTS = "clients";

  private String name;
  private Boolean enabled;
  private List<Map<String, Map<String, String>>> configurations;
  private List<OidcClientDescriptor> clients;

  OidcServiceDescriptor(Map<?, ?> data) {
    if (data != null) {
      name = OidcDescriptorUtils.getStringValue(data, KEY_NAME);
      enabled = OidcDescriptorUtils.getBooleanValue(data, KEY_ENABLED);
      configurations = OidcDescriptorUtils.getConfigurations(data.get(KEY_CONFIGURATIONS));
      Object clientsValue = data.get(KEY_CLIENTS);
      if (clientsValue instanceof List) {
        for (Object item : (List) clientsValue) {
          if (item instanceof Map) {
            addClient(new OidcClientDescriptor((Map<?, ?>) item));
          }
        }
      }
    }
  }

  public String getName() {
    return name;
  }

  public Boolean isEnabled() {
    return enabled;
  }

  public List<Map<String, Map<String, String>>> getConfigurations() {
    return configurations;
  }

  public List<OidcClientDescriptor> getClients() {
    return clients;
  }

  void addClient(OidcClientDescriptor client) {
    if (client == null) {
      return;
    }

    if (clients == null) {
      clients = new ArrayList<>();
    }

    clients.add(client);
  }

  void update(OidcServiceDescriptor updates) {
    if (updates == null) {
      return;
    }

    if (updates.enabled != null) {
      enabled = updates.enabled;
    }

    if (updates.configurations != null) {
      if (configurations == null) {
        configurations = new ArrayList<>();
      }
      configurations.addAll(updates.configurations);
    }

    if (updates.clients != null) {
      if (clients == null) {
        clients = new ArrayList<>();
      }
      for (OidcClientDescriptor client : updates.clients) {
        int existingIndex = indexOfClient(client.getName());
        if (existingIndex >= 0) {
          clients.set(existingIndex, client);
        } else {
          clients.add(client);
        }
      }
    }
  }

  Map<String, Object> toMap() {
    Map<String, Object> data = new TreeMap<>();
    if (name != null) {
      data.put(KEY_NAME, name);
    }
    if (enabled != null) {
      data.put(KEY_ENABLED, enabled);
    }
    if (configurations != null) {
      data.put(KEY_CONFIGURATIONS, configurations);
    }
    if (clients != null) {
      List<Map<String, Object>> clientMaps = new ArrayList<>();
      for (OidcClientDescriptor client : clients) {
        clientMaps.add(client.toMap());
      }
      data.put(KEY_CLIENTS, clientMaps);
    }
    return data;
  }

  private int indexOfClient(String name) {
    if (name == null || clients == null) {
      return -1;
    }
    for (int i = 0; i < clients.size(); i++) {
      OidcClientDescriptor candidate = clients.get(i);
      if (name.equals(candidate.getName())) {
        return i;
      }
    }
    return -1;
  }
}
