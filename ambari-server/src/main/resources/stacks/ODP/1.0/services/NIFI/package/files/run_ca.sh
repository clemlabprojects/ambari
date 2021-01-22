#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

# Launches NiFi CA server
# $1 -> JAVA_HOME
# $2 -> tls-toolkit.sh path
# $3 -> config json
# $4 -> stdout log
# $5 -> stderr log
# $6 -> pid file
COMMAND=$1
JAVA_HOME=$2
TLS_TOOLKIT_SH=$3
CONFIG_JSON=$4
STDOUT_FILE=$5
STD_ERR_FILE=$6
PID_FILE=$7
CA_CHECK_URL=${8:-https://localhost:10443/v1/api}

wait_for_url() {
    PROTOCOL_OPT=" "
    HTTP_CODE=$(curl -s -k${PROTOCOL_OPT}-o /dev/null -w "%{http_code}" ${CA_CHECK_URL})
    CURL_CODE=$?
    TIMEOUT=60
    while [ ${TIMEOUT} -ne 0 ] && [ ${CURL_CODE} -ne 0 ]; do
        [ ${CURL_CODE} -eq 35 ] && PROTOCOL_OPT=" --tlsv1.2 "
        sleep 1
        (( TIMEOUT-- ))
        HTTP_CODE=$(curl -s -k${PROTOCOL_OPT}-o /dev/null -w "%{http_code}" ${CA_CHECK_URL})
        CURL_CODE=$?
    done

    if [ ${TIMEOUT} -eq 0 ] && [ ${CURL_CODE} -ne 0 ]; then
        echo "NIFI-CA api was not accessible in 60 seconds, start failed"
        return 1
    else
        echo "NIFI-CA api http-code '${HTTP_CODE}', start finished"
        return 0
    fi
}

run_nifi_ca_process() {
    JAVA_HOME="$JAVA_HOME" nohup "$TLS_TOOLKIT_SH" server -F -f "$CONFIG_JSON" >> "$STDOUT_FILE" 2>> "$STD_ERR_FILE" < /dev/null &
    NEW_PID=$!
    echo ${NEW_PID} > ${PID_FILE}
    echo "Started NIFI-CA with pid '$NEW_PID'"
}

start_ca() {
    if [ -f ${PID_FILE} ]; then
        OLD_PID=`cat ${PID_FILE}`
        if kill -0 ${OLD_PID} > /dev/null 2>&1; then
            echo "NIFI-CA process already running with pid $(cat ${PID_FILE})"
        else
            echo "Stale NIFI-CA pid '$OLD_PID' exists"
            run_nifi_ca_process
        fi
    else
        run_nifi_ca_process
    fi
    wait_for_url
    return $?
}

stop_ca() {
    if [ -f ${PID_FILE} ]; then
        PID=`cat ${PID_FILE}`

        kill -15 ${PID} > /dev/null 2>&1

        kill -0 ${PID} > /dev/null 2>&1
        KILL_CODE=$?
        TIMEOUT=15

        while [ ${TIMEOUT} -ne 0 ] && [ ${KILL_CODE} -eq 0 ]; do
            sleep 1
            (( TIMEOUT-- ))
            kill -0 ${PID} > /dev/null 2>&1
            KILL_CODE=$?
        done

        if [ ${TIMEOUT} -eq 0 ] && [ ${KILL_CODE} -eq 0 ]; then
            echo "NIFI-CA refused to stop gracefully in 15 seconds, hard-killing"
            kill -9 ${PID}
            sleep 2
            kill -0 ${PID}
            KILL_CODE=$?
            if [ ${KILL_CODE} -eq 0 ]; then
                echo "Failed to hard-kill NIFI-CA with pid '$PID'"
                return 1
            else
                echo "NIFI-CA with pid '$PID' was hard-killed"
                return 0
            fi
        else
            echo "NIFI-CA with pid '$PID' was gracefully stopped"
            return 0
        fi
    else
        echo "Pid file '$PID_FILE' does not exist, consider NIFI-CA is stopped"
        return 0
    fi
}

case ${COMMAND} in
     start)
          start_ca
          exit $?
          ;;
     stop)
          stop_ca
          exit $?
          ;;
     *)
          echo "Command must be [start | stop ]"
          exit 1
          ;;
esac
