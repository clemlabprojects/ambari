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

Ozone DataNode Storage alert: used share of the file systems holding the
DataNode data directories (hdds.datanode.dir) on this host. The DataNode JMX
does not expose the local capacity, so it is read from the file systems.
"""

import os
import re

OZONE_SITE_KEY = '{{ozone-site}}'
DATANODE_DIRS_KEY = '{{ozone-site/hdds.datanode.dir}}'
HDFS_DATANODE_DIRS_KEY = '{{hdfs-site/dfs.datanode.data.dir}}'

RESULT_STATE_OK = 'OK'
RESULT_STATE_WARNING = 'WARNING'
RESULT_STATE_CRITICAL = 'CRITICAL'
RESULT_STATE_UNKNOWN = 'UNKNOWN'

KEY_CAPACITY_USED_WARNING = 'capacity.datanode.used.warning.threshold'
KEY_CAPACITY_USED_CRITICAL = 'capacity.datanode.used.critical.threshold'
CAPACITY_USED_WARNING_DEFAULT = 70.0
CAPACITY_USED_CRITICAL_DEFAULT = 90.0


def get_tokens():
  """
  Returns a tuple of tokens in the format {{site/property}} that will be used
  to build the dictionary passed into execute
  """
  return (OZONE_SITE_KEY, DATANODE_DIRS_KEY, HDFS_DATANODE_DIRS_KEY)


def execute(configurations={}, parameters={}, host_name=None):
  """
  Returns a tuple containing the result code and a pre-formatted result label

  Keyword arguments:
  configurations (dictionary): a mapping of configuration key to value
  parameters (dictionary): a mapping of script parameter key to value
  host_name (string): the name of this host where the alert is running
  """
  if configurations is None:
    return (RESULT_STATE_UNKNOWN, ['There were no configurations supplied to the script.'])

  # like Ozone, fall back to the HDFS DataNode directories when hdds.datanode.dir is not set
  dirs_value = configurations.get(DATANODE_DIRS_KEY) or configurations.get(HDFS_DATANODE_DIRS_KEY)
  if not dirs_value:
    return (RESULT_STATE_UNKNOWN, ['{0} is not set'.format(DATANODE_DIRS_KEY)])

  # "[DISK]file:///data/1,/data/2" -> ["/data/1", "/data/2"]
  data_dirs = []
  for entry in str(dirs_value).split(','):
    path = re.sub(r'^\s*\[[^\]]*\]', '', entry).strip()
    path = re.sub(r'^file://', '', path)
    if path:
      data_dirs.append(path)

  # one entry per file system, so that directories sharing a disk count once
  file_systems = {}
  errors = []
  for path in data_dirs:
    try:
      stat = os.statvfs(path)
      device = os.stat(path).st_dev
    except OSError as e:
      errors.append('{0}: {1}'.format(path, e.strerror))
      continue
    capacity = stat.f_blocks * stat.f_frsize
    if capacity > 0:
      file_systems[device] = (capacity, (stat.f_blocks - stat.f_bfree) * stat.f_frsize, stat.f_bavail * stat.f_frsize)

  if not file_systems:
    return (RESULT_STATE_UNKNOWN, ['No readable DataNode data directory: {0}'.format('; '.join(errors) or dirs_value)])

  capacity = sum(fs[0] for fs in file_systems.values())
  used = sum(fs[1] for fs in file_systems.values())
  remaining = sum(fs[2] for fs in file_systems.values())
  percent_used = used * 100.0 / capacity

  warning = float(parameters.get(KEY_CAPACITY_USED_WARNING, CAPACITY_USED_WARNING_DEFAULT))
  critical = float(parameters.get(KEY_CAPACITY_USED_CRITICAL, CAPACITY_USED_CRITICAL_DEFAULT))
  message = 'Capacity Used:[{0:.0f}%, {1}], Capacity Remaining:[{2}]'.format(
    percent_used, format_bytes(used), format_bytes(remaining))
  if errors:
    message += ' (not readable: {0})'.format('; '.join(errors))

  if percent_used >= critical:
    return (RESULT_STATE_CRITICAL, [message])
  if percent_used >= warning:
    return (RESULT_STATE_WARNING, [message])
  return (RESULT_STATE_OK, [message])


def format_bytes(value):
  value = float(value)
  for unit in ('B', 'KB', 'MB', 'GB', 'TB'):
    if abs(value) < 1024.0:
      return '{0:.1f} {1}'.format(value, unit)
    value /= 1024.0
  return '{0:.1f} PB'.format(value)
