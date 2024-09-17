#!/usr/bin/env python3

'''
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
'''

import logging
import time
import json
from resource_monitoring.core.instance_type_provider import HostInstanceTypeProvider

logger = logging.getLogger()

class MeteringMetricHandler:

  METERING_ALIVE_TIME_METRIC_SUFFIX = "lastKnownAliveTime"

  ## At startup,
  def __init__(self, config):
    self.appId = config.get_metering_appId()
    self.instance_type_metric_appId = config.get_metering_appId() + "_instance_type"
    self.metering_enabled = config.is_metering_enabled()
    self.hostname = config.get_hostname_config()
    self.instance_id = config.get_instanceid()
    self.metering_metric_list = config.get_metering_metrics()
    self.start_ts = int(round(time.time() * 1000))
    if self.metering_enabled:
      logger.info("Metering started with: appId = {0}, metering_metric_list = {1}, start time key = {2}"
                   .format(self.appId, self.metering_metric_list, self.start_ts))
      self.instance_type_provider = HostInstanceTypeProvider(config)
      self.instance_type = self.instance_type_provider.instance_type
      self.metering_metric_key_prefix = self.hostname + "~" + self.instance_type + "~" + str(self.start_ts)
    pass

  # Metering Metrics
  def get_metering_metrics(self, metrics):
    metering_metrics = {}
    curr_time = int(round(time.time() * 1000))
    for metric_name, value in metrics.items():
      if metric_name in self.metering_metric_list:
        end_time_metric_key = self.metering_metric_key_prefix + "~" + metric_name + "~" + str(value) + "~" + self.METERING_ALIVE_TIME_METRIC_SUFFIX
        metering_metrics[end_time_metric_key] = curr_time

    return metering_metrics

    # Instance Type Metrics
  def get_instance_type_metrics(self):
    metering_metrics = {self.instance_type: 1}
    return metering_metrics