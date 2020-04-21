#!/bin/bash
# in docker container
psql -v ON_ERROR_STOP=1 --username "postgres" --dbname "postgres" <<-EOSQL
    CREATE DATABASE ambari_db;
    CREATE USER ambari_user WITH PASSWORD 'ambari123';
    GRANT ALL PRIVILEGES ON DATABASE ambari_db TO ambari_user;
    \connect ambari_db;
    CREATE SCHEMA ambari_schema AUTHORIZATION ambari_user;
    ALTER SCHEMA ambari_schema OWNER TO ambari_user;
    ALTER ROLE ambari_user SET search_path to 'ambari_schema', 'public';
    alter database ambari_db set timezone to 'UTC';
EOSQL

# on localhost
psql -h localhost -p 5454 -U ambari_user -d ambari_db -W -f ./Ambari-DDL-Postgres-CREATE.sql;

yum install -y postgresql-* net-tools
# on virtual host  ambari-server
export PYTHON=python2.7
cd /tmp/ambari/ambari-server/target/ambari-server-2.7.6.0.0-dist
for d in var usr ; do rm -rf /$d/lib/ambari*; done
rm -rf /etc/ambari* /etc/init/*
rm -f /usr/sbin/ambari_server_main*
rm -f /usr/sbin/ambari-server.py
rm -f /usr/local/sbin/ambari*
rm -f /usr/local/bin/ambari-python-wrap
for d in var etc usr ; do cp -R -f $d/* /$d; done
for d in var etc usr ; do cp -R  /tmp/ambari/ambari-agent/target/ambari-agent-2.7.6.0.0/$d/* /$d; done
cp ../../sbin/ambari-server /usr/local/sbin/
cp /tmp/ambari/ambari-agent/target/ambari-agent-2.7.6.0.0/var/lib/ambari-agent/bin/ambari-agent /usr/local/sbin/
cp /tmp/ambari/ambari-common/src/main/unix/* /usr/local/bin/
sed -i -e "s|VERSION.*|VERSION\=2.7.6.0.0|" /usr/local/sbin/ambari-server
sed -i -e "s|HASH.*|HASH\=7ee807e194f55e732298abdb8c672413f267c2f344cc573c50f76803fe38f5e1708db3605086048560dfefa6a2cda1ac6e704ee1686156fd1e9acce1dc60def7|" /usr/local/sbin/ambari-server
sed -i -e "s|hostname.*|hostname\=localhost|" /etc/ambari-agent/conf/ambari-agent.ini
ambari-server setup --jdbc-db=postgres --jdbc-driver=/usr/lib/ambari-server/postgresql-42.2.2.jar
ambari-server setup --databaseport=5454 --databasehost=host.docker.internal --database=postgres --databasename=ambari_db --databaseusername=ambari_user --databasepassword=ambari123 --java-home=/usr/lib/jvm/java/ --silent
cd /usr/lib/ambari-server
rm -rf web-orig
mv web web-orig
ln -s /tmp/ambari/ambari-web/public web
ambari-server restart
ambari-agent restart


# each ambari-agent
yum install -y postgresql-* net-tools

# on virtual host  ambari-agent
export PYTHON=python2.7
cd /tmp/ambari/ambari-server/target/ambari-server-2.7.6.0.0-dist
for d in var usr ; do rm -rf /$d/lib/ambari*; done
rm -rf /etc/ambari* /etc/init/*
rm -f /usr/sbin/ambari_server_main*
rm -f /usr/sbin/ambari-server.py
rm -f /usr/local/sbin/ambari*
rm -f /usr/local/bin/ambari-python-wrap
for d in var etc usr ; do cp -R -f $d/* /$d; done
for d in var etc usr ; do cp -R  /tmp/ambari/ambari-agent/target/ambari-agent-2.7.6.0.0/$d/* /$d; done
cp ../../sbin/ambari-server /usr/local/sbin/
cp /tmp/ambari/ambari-agent/target/ambari-agent-2.7.6.0.0/var/lib/ambari-agent/bin/ambari-agent /usr/local/sbin/
cp /tmp/ambari/ambari-common/src/main/unix/* /usr/local/bin/
sed -i -e "s|VERSION.*|VERSION\=2.7.6.0.0|" /usr/local/sbin/ambari-server
sed -i -e "s|HASH.*|HASH\=7ee807e194f55e732298abdb8c672413f267c2f344cc573c50f76803fe38f5e1708db3605086048560dfefa6a2cda1ac6e704ee1686156fd1e9acce1dc60def7|" /usr/local/sbin/ambari-server
sed -i -e "s|hostname.*|hostname\=ambari-server|" /etc/ambari-agent/conf/ambari-agent.ini
ambari-agent restart






/Users/lbakalian/luc-data/DPH/ambari-clemlabprojects/ambari-server/target/ambari-server-2.7.6.0.0-dist/usr/lib/ambari-server/web/javascripts/app.js
ambari-server setup --jdbc-db=postgres --jdbc-driver=/usr/lib/ambari-server/postgresql-42.2.2.jar
ambari-server setup --databaseport=5454 --databasehost=10.10.10.1 --database=postgres --databasename=ambari_db --databaseusername=ambari_user --databasepassword=ambari123 --java-home=/usr/lib/jvm/java/ --silent
ambari-server setup-security --security-option=setup-truststore --truststore-path=/etc/security/.ssl/truststore.jks --truststore-type=jks --truststore-password=truststore123 --truststore-reconfigure

# if vm prepare vagrant security
ambari-server setup-security --security-option=setup-https --api-ssl=true --api-ssl-port=8442 --pem-password= --import-cert-path="/home/ansible/master01.clemlab.com.cert.pem" --import-key-path="/home/ansible/master01.clemlab.com.key.pem"



ambari-server setup --jdbc-db=postgres --jdbc-driver=/usr/lib/ambari-server/postgresql-42.2.2.jar
ambari-server setup --databaseport=5454 --databasehost=localhost --database=postgres --databasename=ambari_db --databaseusername=ambari_user --databasepassword=ambari123 --java-home=/usr/lib/jvm/java/ --silent
ambari-server setup-security --security-option=setup-https --api-ssl=true --api-ssl-port=8442 --pem-password= --import-cert-path="/home/ansible/master01.clemlab.com.cert.pem" --import-key-path="/home/ansible/master01.clemlab.com.key.pem"
ambari-server setup-security --security-option=setup-truststore --truststore-path=/etc/security/.ssl/truststore.jks --truststore-type=jks --truststore-password=truststore123 --truststore-reconfigure
