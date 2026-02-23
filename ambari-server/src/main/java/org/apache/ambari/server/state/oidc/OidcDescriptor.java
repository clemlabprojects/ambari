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
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public class OidcDescriptor {
  private static final String KEY_SERVICES = "services";

  private Map<String, OidcServiceDescriptor> services;

  public OidcDescriptor() {
  }

  OidcDescriptor(Map<?, ?> data) {
    if (data != null) {
      Object list = data.get(KEY_SERVICES);
      if (list instanceof Collection) {
        for (Object item : (Collection) list) {
          if (item instanceof Map) {
            putService(new OidcServiceDescriptor((Map<?, ?>) item));
          }
        }
      }
    }
  }

  public Map<String, OidcServiceDescriptor> getServices() {
    return services;
  }

  public OidcServiceDescriptor getService(String name) {
    return ((name == null) || (services == null)) ? null : services.get(name);
  }

  public void putService(OidcServiceDescriptor service) {
    if (service == null) {
      return;
    }

    String name = service.getName();
    if (name == null) {
      throw new IllegalArgumentException("The service name must not be null");
    }

    if (services == null) {
      services = new TreeMap<>();
    }

    OidcServiceDescriptor existing = services.get(name);
    if (existing == null) {
      services.put(name, service);
    } else {
      existing.update(service);
    }
  }

  public void update(OidcDescriptor updates) {
    if (updates == null || updates.services == null) {
      return;
    }

    for (OidcServiceDescriptor service : updates.services.values()) {
      putService(service);
    }
  }

  public Map<String, Object> toMap() {
    Map<String, Object> data = new TreeMap<>();
    if (services != null && !services.isEmpty()) {
      ArrayList<Map<String, Object>> serviceMaps = new ArrayList<>();
      for (OidcServiceDescriptor service : services.values()) {
        serviceMaps.add(service.toMap());
      }
      data.put(KEY_SERVICES, serviceMaps);
    } else {
      data.put(KEY_SERVICES, Collections.emptyList());
    }
    return data;
  }
}
