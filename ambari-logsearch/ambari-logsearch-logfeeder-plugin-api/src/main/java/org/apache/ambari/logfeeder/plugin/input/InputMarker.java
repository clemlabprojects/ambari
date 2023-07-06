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
package org.apache.ambari.logfeeder.plugin.input;

import java.util.Map;

/**
 * This interface stores unique data about an input.
 * @param <INPUT_TYPE> Type of the input
 */
public interface InputMarker <INPUT_TYPE extends Input> {


  /**
   * Get the input for an input marker
   * @return An Log Feeder input instance
   */
  INPUT_TYPE getInput();

  /**
   * Get input marker properties
   * @return marker properties which represents an input
   */
  Map<String, Object> getAllProperties();

}
