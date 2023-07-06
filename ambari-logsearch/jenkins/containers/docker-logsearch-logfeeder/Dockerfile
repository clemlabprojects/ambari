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
ADD logfeeder /usr/lib/ambari-logsearch-logfeeder

ADD conf/log4j2.yml /usr/lib/ambari-logsearch-logfeeder/conf/log4j2.yml
ADD conf/logfeeder.properties /usr/lib/ambari-logsearch-logfeeder/conf/logfeeder.properties
ADD conf/logfeeder-env.sh /usr/lib/ambari-logsearch-logfeeder/conf/logfeeder-env.sh

ADD shipper-conf /shipper-conf

ENV JAVA_HOME="/usr/java/default"
ENV LOGFEEDER_USER="logfeeder"
ENV LOGFEEDER_GROUP="logfeeder"
ENV LOGFEEDER_UID="5555"
ENV LOGFEEDER_GID="5555"
ENV LOGSEARCH_INIT_FILE /init.sh
ENV LOGFEEDER_PATH="/usr/lib/ambari-logsearch-logfeeder"
ENV LOGFEEDER_CONF_DIR="/usr/lib/ambari-logsearch-logfeeder/conf"
ENV LOG_PATH="/var/log/ambari-logsearch-logfeeder"
ENV LOGFILE="/var/log/ambari-logsearch-logfeeder/logfeeder.log"
ENV LOGFEEDER_PID_FILE="/var/run/ambari-logsearch-logfeeder/logfeeder.pid"

ENV LOGFEEDER_DEBUG=false
ENV LOGFEEDER_DEBUG_PORT=5005
ENV LOGFEEDER_SSL="false"

RUN groupadd -r --gid $LOGFEEDER_UID $LOGFEEDER_GROUP && useradd -r --uid $LOGFEEDER_UID --gid $LOGFEEDER_GID $LOGFEEDER_USER
RUN chown -R $LOGSEARCH_USER:$LOGSEARCH_GROUP /usr/lib/ambari-logsearch-logfeeder
RUN chown -R $LOGSEARCH_USER:$LOGSEARCH_GROUP /shipper-conf
RUN mkdir /checkpoints && chown -R $LOGSEARCH_USER:$LOGSEARCH_GROUP /checkpoints
RUN mkdir -p /var/log/ambari-logsearch-logfeeder && chown -R $LOGFEEDER_USER:$LOGFEEDER_GROUP /var/log/ambari-logsearch-logfeeder
RUN mkdir -p /var/run/ambari-logsearch-logfeeder && chown -R $LOGFEEDER_USER:$LOGFEEDER_GROUP /var/run/ambari-logsearch-logfeeder
RUN find /usr/lib/ambari-logsearch-logfeeder -type d -exec chmod 755 {} +
RUN find /usr/lib/ambari-logsearch-logfeeder -type f -exec chmod 644 {} +
RUN chmod 755 $LOGFEEDER_PATH/bin/logfeeder.sh
RUN chmod 755 $LOGFEEDER_PATH/conf/logfeeder-env.sh
RUN chmod 755 /entrypoint.sh
RUN chmod 755 /init.sh

USER $LOGFEEDER_USER
WORKDIR /usr/lib/ambari-logsearch-logfeeder
CMD ["/entrypoint.sh"]