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
FROM openjdk:11-jre
MAINTAINER dev@ambari.apache.org

ADD bin/entrypoint.sh /entrypoint.sh
ADD bin/init.sh /init.sh
ADD logsearch-portal /usr/lib/ambari-logsearch-portal

ADD conf/log4j2.yml /usr/lib/ambari-logsearch-portal/conf/log4j2.yml
ADD conf/logsearch.properties /usr/lib/ambari-logsearch-portal/conf/logsearch.properties
ADD conf/logsearch-env.sh /usr/lib/ambari-logsearch-portal/conf/logsearch-env.sh

ENV JAVA_HOME="/usr/java/default"
ENV LOGSEARCH_USER="logsearch"
ENV LOGSEARCH_GROUP="logsearch"
ENV LOGSEARCH_UID="61888"
ENV LOGSEARCH_GID="61888"
ENV LOGSEARCH_INIT_FILE /init.sh
ENV LOGSEARCH_PORT="61888"
ENV LOGSEARCH_PATH="/usr/lib/ambari-logsearch-portal"
ENV LOGSEARCH_CONF_DIR="/usr/lib/ambari-logsearch-portal/conf"
ENV LOG_PATH="/var/log/ambari-logsearch-portal"
ENV LOGFILE="/var/log/ambari-logsearch-portal/logsearch.log"
ENV LOGSEARCH_PID_FILE="/var/run/ambari-logsearch-portal/logsearch.pid"

ENV LOGSEARCH_DEBUG=false
ENV LOGSEARCH_DEBUG_PORT=5005
ENV LOGSEARCH_SSL="false"

RUN groupadd -r --gid $LOGSEARCH_GID $LOGSEARCH_GROUP && useradd -r --uid $LOGSEARCH_UID --gid $LOGSEARCH_GID $LOGSEARCH_USER
RUN chown -R $LOGSEARCH_USER:$LOGSEARCH_GROUP /usr/lib/ambari-logsearch-portal
RUN mkdir -p /var/log/ambari-logsearch-portal && chown -R $LOGSEARCH_USER:$LOGSEARCH_GROUP /var/log/ambari-logsearch-portal
RUN mkdir -p /var/run/ambari-logsearch-portal && chown -R $LOGSEARCH_USER:$LOGSEARCH_GROUP /var/run/ambari-logsearch-portal
RUN find /usr/lib/ambari-logsearch-portal -type d -exec chmod 755 {} +
RUN find /usr/lib/ambari-logsearch-portal -type f -exec chmod 644 {} +
RUN chmod 755 $LOGSEARCH_PATH/bin/logsearch.sh
RUN chmod 755 $LOGSEARCH_PATH/conf/logsearch-env.sh
RUN chmod 755 /entrypoint.sh
RUN chmod 755 /init.sh

USER $LOGSEARCH_USER
WORKDIR /usr/lib/ambari-logsearch-portal
CMD ["/entrypoint.sh"]