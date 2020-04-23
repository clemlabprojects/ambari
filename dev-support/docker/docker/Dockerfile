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

FROM centos:centos7

RUN echo root:changeme | chpasswd

## Install some basic utilities that aren't in the default image
RUN yum clean all -y && yum update -y
RUN yum -y install vim wget rpm-build sudo which telnet tar openssh-server openssh-clients ntp git python-setuptools python-devel httpd java-1.8.0-openjdk-devel
RUN yum -q -y install \
             curl \
             gcc \
             gcc-c++ \
             git \
             java-1.8.0-openjdk-devel \
             make \
             openssl \
             python \
             python-devel \
             python-setuptools \
             rpm-build \
             which \
             zip \
             unzip \
             python-setuptools \
             rpm-build \
             which \
             autoconf automake cppunit-devel ant libtool \
             fuse-devel fuse cmake fuse-libs lzo-devel openssl-devel \
             cyrus-sasl-devel  cyrus-sasl-gssapi krb5-devel openldap-devel  sqlite-devel \
           && yum clean all
# phantomjs dependency
RUN yum -y install fontconfig freetype libfreetype.so.6 libfontconfig.so.1 libstdc++.so.6
RUN rpm -e --nodeps --justdb glibc-common
RUN yum -y install glibc-common


WORKDIR     /opt
RUN         curl -OL https://github.com/protocolbuffers/protobuf/releases/download/v2.5.0/protobuf-2.5.0.zip
RUN         unzip protobuf-2.5.0.zip
WORKDIR      /opt/protobuf-2.5.0
RUN         ./configure
RUN          make
RUN         make install
RUN        protoc --version

WORKDIR     /opt
RUN         curl -OL https://github.com/Kitware/CMake/releases/download/v3.15.3/cmake-3.15.3.tar.gz
RUN         tar -xzf cmake-3.15.3.tar.gz
WORKDIR     /opt/cmake-3.15.3
RUN         ./bootstrap && make && make install



ENV HOME /root
ENV JAVA_HOME /usr/lib/jvm/java

#Install Maven
RUN mkdir -p /opt/maven
WORKDIR /opt/maven
RUN wget http://mirrors.standaloneinstaller.com/apache/maven/maven-3/3.6.3/binaries/apache-maven-3.6.3-bin.tar.gz
RUN tar -xvzf /opt/maven/apache-maven-3.6.3-bin.tar.gz
RUN rm -rf /opt/maven/apache-maven-3.6.3-bin.tar.gz
ENV M2_HOME /opt/maven/apache-maven-3.6.3
ENV MAVEN_OPTS -Xmx2048m -XX:MaxPermSize=256m
ENV PATH $PATH:$JAVA_HOME/bin:$M2_HOME/bin


# SSH key
RUN ssh-keygen -f /root/.ssh/id_rsa -t rsa -N ''
RUN cat /root/.ssh/id_rsa.pub > /root/.ssh/authorized_keys
RUN chmod 600 /root/.ssh/authorized_keys
RUN sed -ri 's/UsePAM yes/UsePAM no/g' /etc/ssh/sshd_config

#To allow bower install behind proxy. See https://github.com/bower/bower/issues/731
RUN git config --global url."https://".insteadOf git://

# Install python, nodejs and npm
RUN yum -y install epel-release

WORKDIR '/tmp'
ENV NPM_CONFIG_LOGLEVEL info
ENV NODE_VERSION 12.14.0
RUN yum install -y xz \
  && curl -SLO "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-linux-x64.tar.xz" \
  && tar -xJf "node-v$NODE_VERSION-linux-x64.tar.xz" -C /usr/local --strip-components=1 \
  && rm -f "/node-v$NODE_VERSION-linux-x64.tar.xz"
RUN node -v
RUN npm install -g bower yarn

# Once run some mvn commands to cache .m2/repository
WORKDIR /tmp
# RUN git clone https://github.com/apache/ambari.git
# WORKDIR /tmp/ambari

# RUN mvn -B -X clean install package rpm:rpm -DskipTests -Dpython.ver="python >= 2.6" -Preplaceurl

# # clean git code because I want to use the one on local filesystem.
# WORKDIR /tmp
# RUN rm -rf /tmp/ambari

# RUN mkdir -p /tmp/ambari-build-docker/blueprints
# ADD ./blueprints /tmp/ambari-build-docker/blueprints
# RUN mkdir -p /tmp/ambari-build-docker/bin
# ADD ./bin /tmp/ambari-build-docker/bin

WORKDIR /tmp

