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
FROM openjdk:10-jre
MAINTAINER dev@ambari.apache.org

RUN apt-get update && apt-get install -y python

ADD bin /infra-solr/bin
ADD conf /infra-solr/conf
ADD solr /usr/lib/ambari-infra-solr
ADD solr-client /usr/lib/ambari-infra-solr-client

RUN mkdir -p /var/lib/ambari-infra-solr/data && mkdir -p /etc/ambari-infra-solr/conf

ENV SOLR_USER="infra-solr"
ENV SOLR_GROUP="infra-solr"
ENV SOLR_UID="8983"
ENV SOLR_GID="8983"
ENV SOLR_HOME /var/lib/ambari-infra-solr/data
ENV SOLR_INCLUDE /usr/lib/ambari-infra-solr/bin/solr.in.sh
ENV SOLR_INIT_FILE /infra-solr/bin/init.sh
ENV SOLR_ZNODE /infra-solr
ENV SOLR_PORT="8886"

RUN cp /infra-solr/conf/infra-solr.conf /etc/security/limits.d/

RUN groupadd -r --gid $SOLR_GID $SOLR_GROUP && useradd -r --uid $SOLR_UID --gid $SOLR_GID $SOLR_USER
RUN chown -R $SOLR_USER:$SOLR_GROUP /usr/lib/ambari-infra-solr
RUN chown -R $SOLR_USER:$SOLR_GROUP /etc/ambari-infra-solr/conf
RUN chown -R $SOLR_USER:$SOLR_GROUP /var/lib/ambari-infra-solr/data
RUN chown -R $SOLR_USER:$SOLR_GROUP /usr/lib/ambari-infra-solr-client
RUN chown -R $SOLR_USER:$SOLR_GROUP /infra-solr && chmod +x /infra-solr/bin/entrypoint.sh
RUN find /usr/lib/ambari-infra-solr -type d -exec chmod 755 {} +
RUN find /usr/lib/ambari-infra-solr -type f -exec chmod 644 {} +
RUN find /usr/lib/ambari-infra-solr-client -type d -exec chmod 755 {} +
RUN find /usr/lib/ambari-infra-solr-client -type f -exec chmod 644 {} +
RUN chmod +x /usr/lib/ambari-infra-solr/bin/solr && chmod +x /usr/lib/ambari-infra-solr/bin/solr.in.sh
RUN chmod +x /usr/lib/ambari-infra-solr-client/solrCloudCli.sh && chmod +x /usr/lib/ambari-infra-solr-client/solrCloudCli.sh && chmod +X /usr/lib/ambari-infra-solr-client/solrIndexHelper.sh && chmod +x /usr/lib/ambari-infra-solr-client/solrDataManager.py && chmod +x /usr/lib/ambari-infra-solr-client/migrationHelper.py

USER $SOLR_USER

WORKDIR /infra-solr/bin/
ENTRYPOINT ["/infra-solr/bin/entrypoint.sh"]
CMD ["server"]