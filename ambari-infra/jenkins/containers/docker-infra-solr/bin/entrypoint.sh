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
export SOLR_LOCATION="/usr/lib/ambari-infra-solr"
export SOLR_CLIENT_LOCATION="/usr/lib/ambari-infra-solr-client"
command="$1"

if [[ -z "$SOLR_HOST" ]]; then
  export SOLR_HOST=`hostname -f`
fi

function client() {
  echo "Run command: $SOLR_CLIENT_LOCATION/solrCloudCli.sh ${@}"
  $SOLR_CLIENT_LOCATION/solrCloudCli.sh ${@}
}

function data_manager() {
  echo "Run command: python $SOLR_CLIENT_LOCATION/solrDataManager.py ${@}"
  python $SOLR_CLIENT_LOCATION/solrDataManager.py ${@}
}

function index_upgrade_tool() {
  echo "Run command: $SOLR_CLIENT_LOCATION/solrIndexHelper.sh ${@}"
  $SOLR_CLIENT_LOCATION/solrIndexHelper.sh ${@}
}

function ambari_migration_helper() {
  echo "Run command: python $SOLR_CLIENT_LOCATION/migrationHelper.py ${@}"
  python $SOLR_CLIENT_LOCATION/migrationHelper.py ${@}
}

function bootstrap_znode() {
  : ${ZK_CONNECT_STRING:?"Please set the ZK_CONNECT_STRING env variable!"}
  local retry=${CLIENT_RETRY:-"60"}
  local interval=${CLIENT_INTERVAL:-"5"}
  client --create-znode -z $ZK_CONNECT_STRING -zn $SOLR_ZNODE -rt $retry -i $interval
}

function start_standalone() {
  echo "Run command: $SOLR_LOCATION/bin/solr start -foreground ${@}"
  $SOLR_LOCATION/bin/solr start -foreground ${@}
}

function start_cloud() {
  bootstrap_znode
  export ZK_HOST="$ZK_CONNECT_STRING$SOLR_ZNODE"
  echo "Run command: $SOLR_LOCATION/bin/solr start -cloud -noprompt -foreground ${@}"
  $SOLR_LOCATION/bin/solr start -cloud -noprompt -foreground ${@}
}

function server() {
  local cloud_mode=${CLOUD_MODE:-"false"}
  if [[ "$cloud_mode" == "true" ]]; then
    echo "Solr cloud mode on."
    start_cloud ${@}
  else
    echo "Solr cloud mode off."
    start_standalone ${@}
  fi
}

if [[ -f "$SOLR_INIT_FILE" ]]; then
  chown $SOLR_USER:$SOLR_GROUP $SOLR_INIT_FILE
  chmod +x $SOLR_INIT_FILE
  $SOLR_INIT_FILE
fi

if [[ ! -f "/var/lib/ambari-infra-solr/data/solr.xml" ]]; then
  cp /infra-solr/conf/solr.xml /var/lib/ambari-infra-solr/data/
fi

case $command in
  "server")
     server ${@:2}
     ;;
  "client")
     client ${@:2}
     ;;
  "data-manager")
     data_manager ${@:2}
     ;;
  "index-upgrade-tool")
     index_upgrade_tool ${@:2}
     ;;
  "ambari-migration-helper")
     ambari_migration_helper ${@:2}
     ;;
  *)
     echo "Available commands: (server|client|data-manager|index-upgrade-tool|ambari-migration-helper|bootstrap-collections)"
     ;;
esac
