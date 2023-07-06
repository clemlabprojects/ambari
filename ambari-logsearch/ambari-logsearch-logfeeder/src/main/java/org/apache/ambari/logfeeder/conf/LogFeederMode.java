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
package org.apache.ambari.logfeeder.conf;

/**
 * Global Log Feeder modes:
 * <pre>
 * - default: process logs based on input / filter / output JSON configurations
 * - cloud: process logs based on input JSON configurations and send them directly into cloud storage (without filters)
 * - hybrid: use both 2 above (together)
 * </pre>
 */
public enum LogFeederMode {
  DEFAULT("default"), CLOUD("cloud"), HYBRID("hybrid");

  private String text;

  LogFeederMode(String text) {
    this.text = text;
  }

  public String getText() {
    return this.text;
  }

  public static LogFeederMode fromString(String text) {
    for (LogFeederMode mode : LogFeederMode.values()) {
      if (mode.text.equalsIgnoreCase(text)) {
        return mode;
      }
    }
    throw new IllegalArgumentException(String.format("String '%s' cannot be converted to LogFeederMode enum", text));
  }

  public static boolean isCloudStorage(LogFeederMode mode) {
    return LogFeederMode.HYBRID.equals(mode) || LogFeederMode.CLOUD.equals(mode);
  }

  public static boolean isNonCloudStorage(LogFeederMode mode) {
    return LogFeederMode.HYBRID.equals(mode) || LogFeederMode.DEFAULT.equals(mode);
  }
}
