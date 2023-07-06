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
package org.apache.ambari.logfeeder.plugin.manager;

import org.apache.ambari.logfeeder.plugin.input.Input;
import org.apache.ambari.logfeeder.plugin.input.InputMarker;
import org.apache.ambari.logfeeder.plugin.output.Output;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Holds output objects for Log Feeder and handle write operations for them based on the inputs/filters
 */
public abstract class OutputManager implements BlockManager {

  /**
   * Write map object (based on input/output descriptions)
   * @param jsonObj json object (key/value pairs) that will be sent to an output destination
   * @param marker holds unique input details
   */
  public abstract void write(Map<String, Object> jsonObj, InputMarker marker);

  /**
   * Write text (based on input/output descriptions)
   * @param jsonBlock json string that will be sent to an output destination
   * @param marker holds unique input details
   */
  public abstract void write(String jsonBlock, InputMarker marker);

  /**
   * Copy an input file to a specific destination
   * @param file object that holds a file
   * @param marker holds unique input details
   */
  public abstract void copyFile(File file, InputMarker marker);

  /**
   * Add an output which will be hold by this class.
   * @param output output object
   */
  public abstract void add(Output output);

  /**
   * Get all outputs
   * @return output object list
   */
  public abstract List<Output> getOutputs();

  /**
   * Release an input (can be used for cleanup) - by default it won't do anything, override this if needed
   * @param input holds input object - in order to gather unique details
   */
  public void release(Input input) {
  }
}
