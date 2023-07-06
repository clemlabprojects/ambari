/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ambari.logfeeder.manager;

import org.apache.ambari.logfeeder.util.LogFeederUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Helper for merge global and input configurations
 */
public class BlockMerger {
  private BlockMerger() {
  }

  @SuppressWarnings("unchecked")
  public static void mergeBlocks(Map<String, Object> fromMap, Map<String, Object> toMap) {
    for (String key : fromMap.keySet()) {
      Object objValue = fromMap.get(key);
      if (objValue == null) {
        continue;
      }
      if (objValue instanceof Map) {
        Map<String, Object> globalFields = LogFeederUtil.cloneObject((Map<String, Object>) objValue);

        Map<String, Object> localFields = (Map<String, Object>) toMap.get(key);
        if (localFields == null) {
          localFields = new HashMap<>();
          toMap.put(key, localFields);
        }

        if (globalFields != null) {
          for (String fieldKey : globalFields.keySet()) {
            if (!localFields.containsKey(fieldKey)) {
              localFields.put(fieldKey, globalFields.get(fieldKey));
            }
          }
        }
      }
    }

    // Let's add the rest of the top level fields if missing
    for (String key : fromMap.keySet()) {
      if (!toMap.containsKey(key)) {
        toMap.put(key, fromMap.get(key));
      }
    }
  }
}
