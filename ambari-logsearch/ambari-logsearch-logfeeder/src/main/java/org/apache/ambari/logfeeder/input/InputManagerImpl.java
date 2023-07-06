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
package org.apache.ambari.logfeeder.input;

import com.google.common.annotations.VisibleForTesting;
import org.apache.ambari.logfeeder.conf.LogFeederProps;
import org.apache.ambari.logfeeder.container.docker.DockerContainerRegistry;
import org.apache.ambari.logfeeder.container.docker.DockerContainerRegistryMonitor;
import org.apache.ambari.logfeeder.plugin.manager.CheckpointManager;
import org.apache.ambari.logfeeder.plugin.common.MetricData;
import org.apache.ambari.logfeeder.plugin.input.Input;
import org.apache.ambari.logfeeder.plugin.manager.InputManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class InputManagerImpl extends InputManager {

  private static final Logger logger = LogManager.getLogger(InputManagerImpl.class);

  private Map<String, List<Input>> inputs = new HashMap<>();
  private Set<Input> notReadyList = new HashSet<>();

  private boolean isDrain = false;

  private MetricData filesCountMetric = new MetricData("input.files.count", true);

  @Inject
  private DockerContainerRegistry dockerContainerRegistry;

  @Inject
  private LogFeederProps logFeederProps;

  @Inject
  private CheckpointManager checkpointHandler;

  public List<Input> getInputList(String serviceName) {
    return inputs.get(serviceName);
  }

  private final String notReadyThreadName;

  public InputManagerImpl() {
    this.notReadyThreadName = "InputIsNotReadyMonitor";
  }

  public InputManagerImpl(String notReadyThreadName) {
    this.notReadyThreadName = notReadyThreadName;
  }

  @Override
  public void add(String serviceName, Input input) {
    List<Input> inputList = inputs.computeIfAbsent(serviceName, k -> new ArrayList<>());
    inputList.add(input);
  }

  @Override
  public void removeInputsForService(String serviceName) {
    List<Input> inputList = inputs.get(serviceName);
    for (Input input : inputList) {
      input.setDrain(true);
    }
    for (Input input : inputList) {
      while (!input.isClosed()) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
        }
      }
    }
    inputList.clear();
    inputs.remove(serviceName);
  }

  @Override
  public void removeInput(Input input) {
    logger.info("Trying to remove from inputList. " + input.getShortDescription());
    for (List<Input> inputList : inputs.values()) {
      Iterator<Input> iter = inputList.iterator();
      while (iter.hasNext()) {
        Input iterInput = iter.next();
        if (iterInput.equals(input)) {
          logger.info("Removing Input from inputList. " + input.getShortDescription());
          iter.remove();
        }
      }
    }
  }

  private int getActiveFilesCount() {
    int count = 0;
    for (List<Input> inputList : inputs.values()) {
      for (Input input : inputList) {
        if (input.isReady()) {
          count++;
        }
      }
    }
    return count;
  }

  @SuppressWarnings("unchecked")
  @Override
  public void init() throws Exception {
    checkpointHandler.init(logFeederProps);
    startMonitorThread();
    startDockerMetadataThread();
  }


  private void startDockerMetadataThread() {
    if (logFeederProps.isDockerContainerRegistryEnabled()) {
      Thread obtaiinDockerMetadataThread = new Thread(new DockerContainerRegistryMonitor(dockerContainerRegistry), "obtain_docker_metadata");
      obtaiinDockerMetadataThread.start();
    }
  }

  private void startMonitorThread() {
    Thread inputIsReadyMonitor = new Thread(notReadyThreadName) {
      @Override
      public void run() {
        logger.info("Going to monitor for these missing files: " + notReadyList.toString());
        while (true) {
          if (isDrain) {
            logger.info("Exiting missing file monitor.");
            break;
          }
          try {
            Iterator<Input> iter = notReadyList.iterator();
            while (iter.hasNext()) {
              Input input = iter.next();
              try {
                if (input.isReady()) {
                  input.monitor();
                  iter.remove();
                }
              } catch (Throwable t) {
                logger.error("Error while enabling monitoring for input. " + input.getShortDescription());
              }
            }
            Thread.sleep(30 * 1000);
          } catch (Throwable t) {
            // Ignore
          }
        }
      }
    };

    inputIsReadyMonitor.start();
  }

  public void startInputs(String serviceName) {
    for (Input input : inputs.get(serviceName)) {
      try {
        if (input instanceof InputFile) {// apply docker metadata registry
          InputFile inputFile = (InputFile)  input;
          inputFile.setDockerContainerRegistry(dockerContainerRegistry);
        }
        input.init(logFeederProps);
        if (input.isReady()) {
          input.monitor();
        } else {
          logger.info("Adding input to not ready list. Note, it is possible this component is not run on this host. " +
            "So it might not be an issue. " + input.getShortDescription());
          notReadyList.add(input);
        }
      } catch (Exception e) {
        logger.error("Error initializing input. " + input.getShortDescription(), e);
      }
    }
  }

  @Override
  public void addToNotReady(Input notReadyInput) {
    notReadyList.add(notReadyInput);
  }

  @Override
  public void addMetricsContainers(List<MetricData> metricsList) {
    for (List<Input> inputList : inputs.values()) {
      for (Input input : inputList) {
        input.addMetricsContainers(metricsList);
      }
    }
    filesCountMetric.value = getActiveFilesCount();
    metricsList.add(filesCountMetric);
  }

  public void logStats() {
    for (List<Input> inputList : inputs.values()) {
      for (Input input : inputList) {
        input.logStat();
      }
    }

    filesCountMetric.value = getActiveFilesCount();
    // TODO: logStatForMetric(filesCountMetric, "Stat: Files Monitored Count", "");
  }

  public void checkInAll() {
    for (List<Input> inputList : inputs.values()) {
      for (Input input : inputList) {
        input.lastCheckIn();
      }
    }
  }

  public void close() {
    for (List<Input> inputList : inputs.values()) {
      for (Input input : inputList) {
        try {
          input.setDrain(true);
        } catch (Throwable t) {
          logger.error("Error while draining. input=" + input.getShortDescription(), t);
        }
      }
    }
    isDrain = true;

    // Need to get this value from property
    int iterations = 30;
    int waitTimeMS = 1000;
    for (int i = 0; i < iterations; i++) {
      boolean allClosed = true;
      for (List<Input> inputList : inputs.values()) {
        for (Input input : inputList) {
          if (!input.isClosed()) {
            try {
              allClosed = false;
              logger.warn("Waiting for input to close. " + input.getShortDescription() + ", " + (iterations - i) + " more seconds");
              Thread.sleep(waitTimeMS);
            } catch (Throwable t) {
              // Ignore
            }
          }
        }
      }
      if (allClosed) {
        logger.info("All inputs are closed. Iterations=" + i);
        return;
      }
    }

    logger.warn("Some inputs were not closed after " + iterations + " iterations");
    for (List<Input> inputList : inputs.values()) {
      for (Input input : inputList) {
        if (!input.isClosed()) {
          logger.warn("Input not closed. Will ignore it." + input.getShortDescription());
        }
      }
    }
  }

  @VisibleForTesting
  public void setLogFeederProps(LogFeederProps logFeederProps) {
    this.logFeederProps = logFeederProps;
  }

  public LogFeederProps getLogFeederProps() {
    return logFeederProps;
  }

  public CheckpointManager getCheckpointHandler() {
    return checkpointHandler;
  }

  public void setCheckpointHandler(CheckpointManager checkpointHandler) {
    this.checkpointHandler = checkpointHandler;
  }
}
