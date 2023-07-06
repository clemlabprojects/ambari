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

import java.util.List;

/**
 * Holds input objects for Log Feeder and start monitoring them if those are in "ready" state.
 */
public abstract class InputManager implements BlockManager {

  /**
   * Add a new input to not ready list (from that point, input manager will check inputs are ready or not, if an input is ready, start monitoring it)
   * @param input input type
   */
  public abstract void addToNotReady(Input input);

  /**
   * Check in all inputs. (dump details for every inputs)
   */
  public abstract void checkInAll();

  /**
   * Get all input objects (1 input can have more sub-thread inputs)
   * @param serviceName input type
   * @return list of inputs
   */
  public abstract List<Input> getInputList(String serviceName);

  /**
   * Add a new input object
   * @param serviceName input type
   * @param input input object
   */
  public abstract void add(String serviceName, Input input);

  /**
   * Remove an input
   * @param input input object
   */
  public abstract void removeInput(Input input);

  /**
   * Remove an input identified by the input type
   * @param serviceName input type
   */
  public abstract void removeInputsForService(String serviceName);

  /**
   * Check inputs are ready, if they are, start monitoring them.
   * @param serviceName input type
   */
  public abstract void startInputs(String serviceName);

  /**
   * Get checkpoint handler which can be used to check in data for inputs during processing them.
   * @return checkpoint manager
   */
  public abstract CheckpointManager getCheckpointHandler();
}
