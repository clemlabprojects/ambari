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

import os

RESULT_CODE_OK = 'OK'
RESULT_CODE_WARNING = 'WARNING'

AMORO_DATA_DIR = '{{amoro-env/amoro_data_dir}}'
# Written by the PAUSE_OPTIMIZING action, removed by RESUME_OPTIMIZING (package/scripts/amoro.py).
PAUSED_FLAG = 'optimizing.paused'


def get_tokens():
  """
  Returns a tuple of tokens in the format {{site/property}} that will be used
  to build the dictionary passed into execute
  """
  return (AMORO_DATA_DIR,)


def execute(configurations={}, parameters={}, host_name=None):
  """
  Returns a tuple containing the result code and a pre-formatted result label

  Keyword arguments:
  configurations (dictionary): a mapping of configuration key to value
  parameters (dictionary): a mapping of script parameter key to value
  host_name (string): the name of this host where the alert is running
  """
  data_dir = configurations.get(AMORO_DATA_DIR) or '/var/lib/amoro'
  if os.path.exists(os.path.join(data_dir, PAUSED_FLAG)):
    return (RESULT_CODE_WARNING, ['Optimizing is paused: Amoro does not maintain the tables. '
                                  'Run the Amoro service action Resume Optimizing to resume it.'])
  return (RESULT_CODE_OK, ['Optimizing is active'])
