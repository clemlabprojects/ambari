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
import subprocess
import sys

logger = logging.getLogger()

class HostInstanceTypeProvider:

  DEFAULT_INSTANCE_TYPE = "custom"
  KNOWN_PROVIDER_SCRIPT_PREFIX = "instance_type_provider_"
  KNOWN_PROVIDER_SCRIPTS = dict()

  def __init__(self, config):

    self.KNOWN_PROVIDER_SCRIPTS['google'] = config.get_config_dir() + self.KNOWN_PROVIDER_SCRIPT_PREFIX + "gce"
    self.KNOWN_PROVIDER_SCRIPTS['microsoft'] = config.get_config_dir() + self.KNOWN_PROVIDER_SCRIPT_PREFIX + "azure"
    self.KNOWN_PROVIDER_SCRIPTS['xen'] = config.get_config_dir() + self.KNOWN_PROVIDER_SCRIPT_PREFIX + "ec2"

    self.provider_type = config.get_provider_type()
    logger.info("Provider type {0}".format(self.provider_type))

    script = self.get_script_for_provider(self.provider_type)
    logger.info("Script for provider {0}".format(script))

    self.instance_type_script = config.get_instance_type_script()
    logger.info("Custom Instance Type Script {0}".format(self.instance_type_script))

    if script:
      self.instance_type = self.get_instance_type_from_script(script)
    elif self.instance_type_script:
      self.instance_type = self.get_instance_type_from_script(self.instance_type_script)
    else:
      self.instance_type = self.DEFAULT_INSTANCE_TYPE
    logger.info("Instance type {0}".format(self.instance_type))

  def get_instance_type(self):
    return self.instance_type

  def get_script_for_provider(self, provider_type):
    p_type = str(provider_type).lower()
    if provider_type and p_type in self.KNOWN_PROVIDER_SCRIPTS:
      return self.KNOWN_PROVIDER_SCRIPTS[p_type]
    return None

  def get_instance_type_from_script(self, script):
    instance_type = self.DEFAULT_INSTANCE_TYPE
    if script:
      try:
        osStat = subprocess.Popen([script], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        out, err = osStat.communicate()
        if 0 == osStat.returncode and 0 != len(out.strip()):
          instance_type = out.strip()
          logger.info("Read instance_type '{0}' using script '{1}'".format(instance_type, script))
      except:
        logger.warn("Unexpected error while retrieving instance_type: '{0}'".format(sys.exc_info()))
    return instance_type