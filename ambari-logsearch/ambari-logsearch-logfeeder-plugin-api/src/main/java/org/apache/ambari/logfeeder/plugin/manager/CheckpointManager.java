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

import org.apache.ambari.logfeeder.plugin.common.LogFeederProperties;
import org.apache.ambari.logfeeder.plugin.input.Input;
import org.apache.ambari.logfeeder.plugin.input.InputMarker;

import java.io.IOException;

/**
 * Handle checkpoints for inputs, that can be used to store data about the inputs which can be used to process them proerly even if Log Feeder was restarted.
 * @param <I> type of the input
 * @param <IFM> input marker type - can store unique input data
 * @param <P> object that holds global Log Feeder configurations
 */
public interface CheckpointManager<I extends Input, IFM extends InputMarker, P extends LogFeederProperties> {

  /**
   * Init checkpoint manager.
   * @param properties key/value pairs that can be used to configure checkpoint manager
   */
  void init(P properties);

  /**
   * Save an input pointer (e.g.: save line numbers in a file with some input identifiers)
   * @param input input to be checked in by the checkpoint manager
   * @param inputMarker input marker, can store unique input details
   */
  void checkIn(I input, IFM inputMarker);

  /**
   * Resume input by checkpoints - get the line number
   * @param input that should be resumed (processing)
   * @return line number
   */
  int resumeLineNumber(I input);

  /**
   * Delete checkpoints by the checkpoint manager (e.g.: deleted dumped input details with line number data etc.)
   */
  void cleanupCheckpoints();

  /**
   * Print checkpoint informations.
   * @param checkpointLocation location of the checkpoint file
   * @param logTypeFilter type of the input (input groups, like hdfs_namenode can be an input type)
   * @param fileKeyFilter file key which can identify the input and checkpoint file
   * @throws IOException error during printing a checkpoint
   */
  void printCheckpoints(String checkpointLocation, String logTypeFilter,
                        String fileKeyFilter) throws IOException;

  /**
   * Clean a checkpoint by checkpoint manager.
   * @param checkpointLocation location of the checkpoint file
   * @param logTypeFilter type of the input (input groups, like hdfs_namenode can be an input type)
   * @param fileKeyFilter file key which can identify the input and checkpoint file
   * @param all flag to cleanup all checkpoints for a specific log type
   * @throws IOException error during cleaning up a checkpoint
   */
  void cleanCheckpoint(String checkpointLocation, String logTypeFilter,
                       String fileKeyFilter, boolean all) throws IOException;

}
