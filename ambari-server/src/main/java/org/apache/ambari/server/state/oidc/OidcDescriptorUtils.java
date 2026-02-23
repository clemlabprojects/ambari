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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;

final class OidcDescriptorUtils {
  private OidcDescriptorUtils() {
  }

  static String getStringValue(Map<?, ?> data, String key) {
    if ((data == null) || (key == null)) {
      return null;
    }

    Object value = data.get(key);
    return (value == null) ? null : value.toString();
  }

  static Boolean getBooleanValue(Map<?, ?> data, String key) {
    String value = getStringValue(data, key);
    return StringUtils.isBlank(value) ? null : Boolean.parseBoolean(value);
  }

  static List<String> getStringList(Object value) {
    if (!(value instanceof Collection)) {
      return null;
    }

    List<String> results = new ArrayList<>();
    for (Object item : (Collection) value) {
      if (item != null) {
        results.add(item.toString());
      }
    }

    return results.isEmpty() ? null : results;
  }

  static Map<String, String> getStringMap(Object value) {
    if (!(value instanceof Map)) {
      return null;
    }

    Map<String, String> results = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
      if (entry.getKey() != null) {
        results.put(entry.getKey().toString(),
          (entry.getValue() == null) ? null : entry.getValue().toString());
      }
    }

    return results.isEmpty() ? null : results;
  }

  static List<Map<String, Map<String, String>>> getConfigurations(Object value) {
    if (!(value instanceof Collection)) {
      return null;
    }

    List<Map<String, Map<String, String>>> configurations = new ArrayList<>();
    for (Object item : (Collection) value) {
      if (item instanceof Map) {
        Map<String, Map<String, String>> configBlock = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) item).entrySet()) {
          if (entry.getKey() != null && entry.getValue() instanceof Map) {
            configBlock.put(entry.getKey().toString(), getStringMap(entry.getValue()));
          }
        }
        if (!configBlock.isEmpty()) {
          configurations.add(configBlock);
        }
      }
    }

    return configurations.isEmpty() ? null : configurations;
  }
}
