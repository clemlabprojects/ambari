# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License

# WARNING: This script is performed not only on uninstall, but also
# during package update. See http://www.ibm.com/developerworks/library/l-rpm2/
# for details


LEGACY_RESOURCE_MONITORING_DIR=/usr/lib/python3.6/site-packages/resource_monitoring
# If target doesn't exist, and python3.9 is present, create symlink from legacy to target

# python3.9
TARGET_RESOURCE_MONITORING_DIR=/usr/lib/python3.9/site-packages/resource_monitoring
if [ ! -e "$TARGET_RESOURCE_MONITORING_DIR" ]; then
  if [ -d /usr/lib/python3.9 ]; then
    if [ -e "$LEGACY_RESOURCE_MONITORING_DIR" ]; then
      mkdir -p "$(dirname "$TARGET_RESOURCE_MONITORING_DIR")"
      ln -s "$LEGACY_RESOURCE_MONITORING_DIR" "$TARGET_RESOURCE_MONITORING_DIR"
    fi
  fi
fi

# python3.10
TARGET_RESOURCE_MONITORING_DIR=/usr/lib/python3.10/site-packages/resource_monitoring
if [ ! -e "$TARGET_RESOURCE_MONITORING_DIR" ]; then
  if [ -d /usr/lib/python3.10 ]; then
    if [ -e "$LEGACY_RESOURCE_MONITORING_DIR" ]; then
      mkdir -p "$(dirname "$TARGET_RESOURCE_MONITORING_DIR")"
      ln -s "$LEGACY_RESOURCE_MONITORING_DIR" "$TARGET_RESOURCE_MONITORING_DIR"
    fi
  fi
fi

# python3.11
TARGET_RESOURCE_MONITORING_DIR=/usr/lib/python3.11/site-packages/resource_monitoring
if [ ! -e "$TARGET_RESOURCE_MONITORING_DIR" ]; then
  if [ -d /usr/lib/python3.11 ]; then
    if [ -e "$LEGACY_RESOURCE_MONITORING_DIR" ]; then
      mkdir -p "$(dirname "$TARGET_RESOURCE_MONITORING_DIR")"
      ln -s "$LEGACY_RESOURCE_MONITORING_DIR" "$TARGET_RESOURCE_MONITORING_DIR"
    fi
  fi
fi

# python3.12
TARGET_RESOURCE_MONITORING_DIR=/usr/lib/python3.12/site-packages/resource_monitoring
if [ ! -e "$TARGET_RESOURCE_MONITORING_DIR" ]; then
  if [ -d /usr/lib/python3.12 ]; then
    if [ -e "$LEGACY_RESOURCE_MONITORING_DIR" ]; then
      mkdir -p "$(dirname "$TARGET_RESOURCE_MONITORING_DIR")"
      ln -s "$LEGACY_RESOURCE_MONITORING_DIR" "$TARGET_RESOURCE_MONITORING_DIR"
    fi
  fi
fi