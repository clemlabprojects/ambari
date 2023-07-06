#!/bin/bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

sdir="`dirname \"$0\"`"
: ${1:?"argument is missing: (install|delete)"}
command="$1"
shift

function install_charts() {
  helm install --name test-zk $sdir/zookeeper/
  helm install --name test-solr $sdir/infra-solr/ --set zkRelease=test-zk
  helm install --name test-logsearch $sdir/logsearch/ --set zkRelease=test-zk
}

function purge_charts() {
  helm del --purge test-logsearch
  helm del --purge test-solr
  helm del --purge test-zk
}

case $command in
  "install")
     install_charts
     ;;
  "delete")
     purge_charts
     ;;
   *)
   echo "Available commands: (install|delete)"
   ;;
esac