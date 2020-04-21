FROM ambari/build:latest
# Install epel (requirement for service nginx)
RUN yum install -y epel-release

# Install supervisor
RUN yum install -y iproute python-setuptools hostname inotify-tools yum-utils which 
RUN yum install -y supervisor
ADD ./supervisord.conf /etc/supervisord.conf

# Install SSH
# RUN yum install -y openssh-server openssh-clients
# RUN ssh-keygen -t rsa -f ~/.ssh/id_rsa -N ''
# RUN cat ~/.ssh/id_rsa.pub > ~/.ssh/authorized_keys
# RUN ssh-keygen -f /etc/ssh/ssh_host_rsa_ke

# Install Java, OpenSSL, GIT and compression dependencies
RUN yum install -y java openssl git zip unzip bzip2

RUN yum clean all

ADD ./run.sh /run.sh
RUN mkdir -p /nikita
WORKDIR /tmp
ENV TERM xterm

#CMD ["node_modules/.bin/mocha", "test/api/"]
ENTRYPOINT ["/run.sh"]
CMD []