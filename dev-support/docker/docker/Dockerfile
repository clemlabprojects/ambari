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

ARG BASE_IMAGE=rockylinux:9
ARG DNF_UPDATE=0
FROM ${BASE_IMAGE}
ARG DNF_UPDATE

RUN echo root:changeme | chpasswd

## Install some basic utilities that aren't in the default image
RUN if [ -f /etc/yum.repos.d/CentOS-Base.repo ] && grep -q "CentOS.*7" /etc/redhat-release 2>/dev/null; then \
      sed -i 's|^mirrorlist=|#mirrorlist=|g' /etc/yum.repos.d/CentOS-Base.repo && \
      sed -i 's|^#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-Base.repo; \
    fi
RUN set -eux; \
    rhel_major="0"; \
    if [ -f /etc/os-release ]; then . /etc/os-release; rhel_major="${VERSION_ID%%.*}"; fi; \
    if [ "${rhel_major}" -ge 9 ]; then \
      dnf -y install dnf-plugins-core epel-release || true; \
      dnf config-manager --set-enabled crb || true; \
      if [ "${DNF_UPDATE}" = "1" ]; then dnf -y update; fi; \
      dnf -y install --allowerasing \
        vim-enhanced wget curl rpm-build sudo which telnet tar openssh-server openssh-clients chrony git \
        python3 python3-devel python3-setuptools python3-pip \
        httpd gcc gcc-c++ make openssl \
        zip unzip autoconf automake cppunit-devel ant libtool \
        fuse-devel fuse fuse-libs cmake lzo-devel openssl-devel \
        cyrus-sasl-devel cyrus-sasl-gssapi krb5-devel openldap-devel sqlite-devel \
        fontconfig freetype libstdc++ glibc-common procps-ng \
        iproute hostname inotify-tools yum-utils \
        bzip2 xz; \
      dnf clean all; \
      ln -sf /usr/bin/python3 /usr/bin/python || true; \
    else \
      yum clean all -y && yum update -y; \
      yum -y install vim wget rpm-build sudo which telnet tar openssh-server openssh-clients ntp git python-setuptools python-devel httpd; \
      yum -q -y install \
        curl gcc gcc-c++ git make openssl python python-devel python-setuptools \
        python3 python3-devel python3-setuptools \
        rpm-build which zip unzip \
        autoconf automake cppunit-devel ant libtool \
        fuse-devel fuse cmake fuse-libs lzo-devel openssl-devel \
        cyrus-sasl-devel cyrus-sasl-gssapi krb5-devel openldap-devel sqlite-devel procps-ng \
        && yum clean all; \
    fi
# Install Java 17 for builds that target release 17.
RUN JDK_ARCH="x64" \
  && if [ "$(uname -m)" = "aarch64" ]; then JDK_ARCH="aarch64"; fi \
  && curl -fsSLo /tmp/jdk17.tgz "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.12%2B7/OpenJDK17U-jdk_${JDK_ARCH}_linux_hotspot_17.0.12_7.tar.gz" \
  && mkdir -p /opt/java \
  && tar -xzf /tmp/jdk17.tgz -C /opt/java --strip-components=1 \
  && rm -f /tmp/jdk17.tgz

# phantomjs dependency
RUN yum -y install fontconfig freetype libstdc++
RUN rpm -e --nodeps --justdb glibc-common || true
RUN yum -y install glibc-common


WORKDIR /opt
RUN if [ "$(uname -m)" = "aarch64" ]; then \
      yum -y install protobuf protobuf-compiler; \
    else \
      curl -OL https://github.com/protocolbuffers/protobuf/releases/download/v2.5.0/protobuf-2.5.0.zip; \
      unzip protobuf-2.5.0.zip; \
      cd /opt/protobuf-2.5.0; \
      ./configure; \
      make; \
      make install; \
    fi \
 && protoc --version

WORKDIR     /opt
RUN         curl -OL https://github.com/Kitware/CMake/releases/download/v3.15.3/cmake-3.15.3.tar.gz
RUN         tar -xzf cmake-3.15.3.tar.gz
WORKDIR     /opt/cmake-3.15.3
RUN         ./bootstrap && make && make install



ENV HOME /root
ENV JAVA_HOME /opt/java

#Install Maven
RUN mkdir -p /opt/maven
WORKDIR /opt/maven
RUN curl -fsSLo /opt/maven/apache-maven-3.6.3-bin.tar.gz \
  https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.6.3/apache-maven-3.6.3-bin.tar.gz
RUN tar -xvzf /opt/maven/apache-maven-3.6.3-bin.tar.gz
RUN rm -rf /opt/maven/apache-maven-3.6.3-bin.tar.gz
ENV M2_HOME /opt/maven/apache-maven-3.6.3
ENV MAVEN_OPTS -Xmx2048m -XX:MaxMetaspaceSize=256m
ENV PATH $JAVA_HOME/bin:$M2_HOME/bin:$PATH


# SSH key
RUN ssh-keygen -A
RUN mkdir -p /var/run/sshd
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
  && NODE_ARCH="linux-x64" \
  && if [ "$(uname -m)" = "aarch64" ]; then NODE_ARCH="linux-arm64"; fi \
  && curl -SLO "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-$NODE_ARCH.tar.xz" \
  && tar -xJf "node-v$NODE_VERSION-$NODE_ARCH.tar.xz" -C /usr/local --strip-components=1 \
  && rm -f "/node-v$NODE_VERSION-$NODE_ARCH.tar.xz"
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
