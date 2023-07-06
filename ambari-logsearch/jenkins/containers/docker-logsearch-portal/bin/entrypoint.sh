#!/usr/bin/env bash
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.
ZK_CONNECT_STRING=${ZK_CONNECT_STRING:-"localhost:9983"}

function set_custom_zookeeper_address() {
  local file_to_update=${1:?"usage: <filename_to_update>"}
  local zk_connect_string="$ZK_CONNECT_STRING"
  if [ "$zk_connect_string" != "localhost:9983" ] ; then
    sed -i "s|localhost:9983|$zk_connect_string|g" $file_to_update
  fi
}

function start() {
  set_custom_zookeeper_address /usr/lib/ambari-logsearch-portal/conf/logsearch.properties
  /usr/lib/ambari-logsearch-portal/bin/logsearch.sh start -f
}

if [[ -f "$LOGSEARCH_INIT_FILE" ]]; then
  $LOGSEARCH_INIT_FILE
fi

start ${@}
