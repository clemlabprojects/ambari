#!/usr/bin/env python3
"""
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
"""

import service_advisor


class CoreServiceAdvisor(service_advisor.ServiceAdvisor):
  def _should_recommend_core_site(self, configurations, services):
    changed_configs = services.get("changed-configurations", []) if services else []
    current_default_fs = self.getConfigProperty(configurations, services, "core-site", "fs.defaultFS", None)

    # Bootstrap cluster configs when core-site/fs.defaultFS does not exist yet.
    if not current_default_fs:
      return True

    # Keep old behavior for generic core-site edits: do not rewrite core-site unless
    # the CORE-owned filesystem selector changed.
    for changed_config in changed_configs:
      if changed_config.get("type") == "core-env" and changed_config.get("name") in (
        "core_filesystem_type",
        "core_external_default_fs",
      ):
        return True

    return False

  def getServiceComponentLayoutValidations(self, services, hosts):
    return self.getServiceComponentCardinalityValidations(services, hosts, "CORE")

  def getServiceConfigurationRecommendations(self, configurations, clusterData, services, hosts):
    self.recommendCoreConfigurations(configurations, clusterData, services, hosts)

  def getServiceConfigurationsValidationItems(self, configurations, recommendedDefaults, services, hosts):
    return self.validateCoreConfigurations(configurations.get("core-env", {}).get("properties", {}),
                                           recommendedDefaults, configurations, services, hosts)

  def recommendCoreConfigurations(self, configurations, clusterData, services, hosts):
    putCoreEnv = self.putProperty(configurations, "core-env", services)

    core_fs_type = self.getCoreFilesystemType(configurations, services)
    putCoreEnv("core_filesystem_type", core_fs_type)
    core_external_default_fs = self.getConfigProperty(configurations, services, "core-env", "core_external_default_fs", "file:///")
    putCoreEnv("core_external_default_fs", core_external_default_fs)

    if self._should_recommend_core_site(configurations, services):
      self.preserveExistingConfigTypeProperties(configurations, services, "core-site")
      putCoreSite = self.putProperty(configurations, "core-site", services)
      default_fs = self.getServiceDefaultFs(configurations, services, "core-env", "core_filesystem_type")
      putCoreSite("fs.defaultFS", default_fs)

  def validateCoreConfigurations(self, properties, recommendedDefaults, configurations, services, hosts):
    validationItems = []
    servicesList = [service["StackServices"]["service_name"] for service in services["services"]]

    core_fs_type = str(properties.get("core_filesystem_type", self.getCoreFilesystemType(configurations, services))).upper()
    core_external_default_fs = str(properties.get("core_external_default_fs", self.getConfigProperty(configurations, services, "core-env", "core_external_default_fs", "file:///"))).strip()
    if core_fs_type == "HDFS" and "HDFS" not in servicesList:
      validationItems.append({
        "config-name": "core_filesystem_type",
        "item": self.getWarnItem("HDFS is not installed. Select OZONE or EXTERNAL for core-env/core_filesystem_type.")
      })

    if core_fs_type == "OZONE" and "OZONE" not in servicesList:
      validationItems.append({
        "config-name": "core_filesystem_type",
        "item": self.getWarnItem("OZONE is not installed. Select HDFS or EXTERNAL for core-env/core_filesystem_type.")
      })

    if core_fs_type == "EXTERNAL" and not core_external_default_fs:
      validationItems.append({
        "config-name": "core_external_default_fs",
        "item": self.getWarnItem("Set core-env/core_external_default_fs when core-env/core_filesystem_type is EXTERNAL.")
      })

    if core_fs_type == "EXTERNAL" and core_external_default_fs and "://" not in core_external_default_fs:
      validationItems.append({
        "config-name": "core_external_default_fs",
        "item": self.getWarnItem("core-env/core_external_default_fs must be a URI with a scheme (for example: file:///, s3a://bucket, abfs://container@account, hdfs://namenode:8020).")
      })

    return self.toConfigurationValidationProblems(validationItems, "core-env")
