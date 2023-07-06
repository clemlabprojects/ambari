<!---
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License. You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->
## Log Search Configurations

| `Name` | `Description` | `Default` | `Examples` |
|---|---|---|---|
|`hadoop.security.credential.provider.path`|Path to interrogate for protected credentials. (see: https://hadoop.apache.org/docs/current/hadoop-project-dist/hadoop-common/CredentialProviderAPI.html)|`EMPTY`|<ul><li>`localjceks://file/home/mypath/my.jceks`</li></ul>|
|`logsearch.admin.kerberos.cookie.domain`|Domain for Kerberos cookie.|localhost|<ul><li>`c6401.ambari.apache.org`</li><li>`localhost`</li></ul>|
|`logsearch.admin.kerberos.cookie.path`|Cookie path of the kerberos cookie|/|<ul><li>`/`</li></ul>|
|`logsearch.admin.kerberos.token.valid.seconds`|Kerberos token validity in seconds.|30|<ul><li>`30`</li></ul>|
|`logsearch.auth.external_auth.enabled`|Enable external authentication (currently Ambari acts as an external authentication server).|false|<ul><li>`true`</li><li>`false`</li></ul>|
|`logsearch.auth.external_auth.host_url`|External authentication server URL (host and port).|http://ip:port|<ul><li>`https://c6401.ambari.apache.org:8080`</li></ul>|
|`logsearch.auth.external_auth.login_url`|Login URL for external authentication server ($USERNAME parameter is replaced with the Login username).|/api/v1/users/$USERNAME/privileges?fields=*|<ul><li>`/api/v1/users/$USERNAME/privileges?fields=*`</li></ul>|
|`logsearch.auth.file.enabled`|Enable file based authentication (in json file at logsearch configuration folder).|true|<ul><li>`true`</li><li>`false`</li></ul>|
|`logsearch.auth.jwt.audiances`|Comma separated list of acceptable audiences for the JWT token.|`EMPTY`|<ul><li>`audiance1,audiance2`</li></ul>|
|`logsearch.auth.jwt.cookie.name`|The name of the cookie that contains the JWT token.|hadoop-jwt|<ul><li>`hadoop-jwt`</li></ul>|
|`logsearch.auth.jwt.enabled`|Enable JWT based authentication (e.g.: for KNOX).|false|<ul><li>`true`</li><li>`false`</li></ul>|
|`logsearch.auth.jwt.provider_url`|URL to the JWT authentication server.|`EMPTY`|<ul><li>`https://c6401.ambari.apache.org:8443/mypath`</li></ul>|
|`logsearch.auth.jwt.public_key`|PEM formatted public key for JWT token without the header and the footer.|`EMPTY`|<ul><li>`MIGfMA0GCSqGSIb3DQEBA...`</li></ul>|
|`logsearch.auth.jwt.query.param.original_url`|Name of the original request URL which is used to redirect to Log Search Portal.|originalUrl|<ul><li>`myUrl`</li></ul>|
|`logsearch.auth.jwt.user.agents`|Comma separated web user agent list. (Used as prefixes)|Mozilla,Opera,Chrome|<ul><li>`Mozilla,Chrome`</li></ul>|
|`logsearch.auth.ldap.base.dn`|Base DN of LDAP database.|`EMPTY`|<ul><li>`dc=apache,dc=org`</li></ul>|
|`logsearch.auth.ldap.enabled`|Enable LDAP based authentication (currenty not supported).|false|<ul><li>`true`</li><li>`false`</li></ul>|
|`logsearch.auth.ldap.group.role.attribute`|Attribute for identifying LDAP groups (group name)|cn|<ul><li>`cn`</li></ul>|
|`logsearch.auth.ldap.group.role.map`|Map of LDAP groups to Log Search roles|`EMPTY`|<ul><li>`ROLE_CUSTOM1:ROLE_USER,ROLE_CUSTOM2:ROLE_ADMIN`</li></ul>|
|`logsearch.auth.ldap.group.search.base`|Group search base - defines where to find LDAP groups. Won't do any authority/role mapping if this field is empty.|`EMPTY`|<ul><li>`ou=people`</li></ul>|
|`logsearch.auth.ldap.group.search.filter`|Group search filter which is used to get membership data for a specific user|`EMPTY`|<ul><li>`(memberUid={0})`</li></ul>|
|`logsearch.auth.ldap.manager.dn`|DN of the LDAP manger user (it is a must if LDAP groups are used).|`EMPTY`|<ul><li>`cn=admin,dc=apache,dc=org`</li></ul>|
|`logsearch.auth.ldap.manager.password`|Password of the LDAP manager user.|`EMPTY`|<ul><li>`mypassword`</li></ul>|
|`logsearch.auth.ldap.manager.password.file`|File that contains password of the LDAP manager user.|`EMPTY`|<ul><li>`/my/path/passwordfile`</li></ul>|
|`logsearch.auth.ldap.password.attribute`|Password attribute for LDAP authentication|userPassword|<ul><li>`password`</li></ul>|
|`logsearch.auth.ldap.referral.method`|Set the method to handle referrals for LDAP|ignore|<ul><li>`follow`</li></ul>|
|`logsearch.auth.ldap.role.prefix`|Role prefix that is added for LDAP groups (as authorities)|ROLE_|<ul><li>`ROLE_`</li></ul>|
|`logsearch.auth.ldap.url`|URL of LDAP database.|`EMPTY`|<ul><li>`ldap://localhost:389`</li></ul>|
|`logsearch.auth.ldap.user.dn.pattern`|DN pattern that is used during login (dn should contain the username), can be used instead of user filter|`EMPTY`|<ul><li>`uid={0},ou=people`</li></ul>|
|`logsearch.auth.ldap.user.search.base`|User search base for user search filter|`EMPTY`|<ul><li>`ou=people`</li></ul>|
|`logsearch.auth.ldap.user.search.filter`|Used for get a user based on on LDAP search (username is the input), if it is empty, user dn pattern is used.|`EMPTY`|<ul><li>`uid={0}`</li></ul>|
|`logsearch.auth.proxyserver.ip`|IP of trusted Knox Proxy server(s) that Log Search will trust on|`EMPTY`|<ul><li>`192.168.0.1,192.168.0.2`</li></ul>|
|`logsearch.auth.proxyuser.groups`|List of user-groups which trusted-proxy user ‘knox’ can proxy for|*|<ul><li>`admin,user`</li></ul>|
|`logsearch.auth.proxyuser.hosts`|List of hosts from which trusted-proxy user ‘knox’ can connect from|*|<ul><li>`host1,host2`</li></ul>|
|`logsearch.auth.proxyuser.users`|List of users which the trusted-proxy user ‘knox’ can proxy for|knox|<ul><li>`knox,hdfs`</li></ul>|
|`logsearch.auth.redirect.forward`|Forward redirects for HTTP calls. (useful in case of proxies)|false|<ul><li>`true`</li></ul>|
|`logsearch.auth.simple.enabled`|Enable simple authentication. That means you won't require password to log in.|false|<ul><li>`true`</li><li>`false`</li></ul>|
|`logsearch.auth.trusted.proxy`|A boolean property to enable/disable trusted-proxy 'knox' authentication|false|<ul><li>`true`</li></ul>|
|`logsearch.authr.file.enabled`|A boolean property to enable/disable file based authorization|false|<ul><li>`true`</li></ul>|
|`logsearch.authr.role.file`|Simple file that contains user/role mappings.|roles.json|<ul><li>`logsearch-roles.json`</li></ul>|
|`logsearch.cert.algorithm`|Algorithm to generate certificates for SSL (if needed).|sha256WithRSA|<ul><li>`sha256WithRSA`</li></ul>|
|`logsearch.cert.folder.location`|Folder where the generated certificates (SSL) will be located. Make sure the user of Log Search Server can access it.|/usr/lib/ambari-logsearch-portal/conf/keys|<ul><li>`/etc/mypath/keys`</li></ul>|
|`logsearch.config.api.enabled`|Enable config API feature and shipperconfig API endpoints.|true|<ul><li>`false`</li></ul>|
|`logsearch.config.api.filter.solr.enabled`|Use solr as a log level filter storage|false|<ul><li>`true`</li></ul>|
|`logsearch.config.api.filter.zk.enabled`|Use zookeeper as a log level filter storage|false|<ul><li>`true`</li></ul>|
|`logsearch.config.zk_acls`|ZooKeeper ACLs for handling configs. (read & write)|world:anyone:cdrwa|<ul><li>`world:anyone:r,sasl:solr:cdrwa,sasl:logsearch:cdrwa`</li></ul>|
|`logsearch.config.zk_connect_string`|ZooKeeper connection string.|`EMPTY`|<ul><li>`localhost1:2181,localhost2:2181/znode`</li></ul>|
|`logsearch.config.zk_connection_retry_time_out_ms`|The maximum elapsed time for connecting to ZooKeeper in milliseconds. 0 means retrying forever.|`EMPTY`|<ul><li>`1200000`</li></ul>|
|`logsearch.config.zk_connection_time_out_ms`|ZooKeeper connection timeout in milliseconds|`EMPTY`|<ul><li>`30000`</li></ul>|
|`logsearch.config.zk_root`|ZooKeeper root node where the shippers are stored. (added to the connection string)|`EMPTY`|<ul><li>`/logsearch`</li></ul>|
|`logsearch.config.zk_session_time_out_ms`|ZooKeeper session timeout in milliseconds|`EMPTY`|<ul><li>`60000`</li></ul>|
|`logsearch.hadoop.security.auth_to_local`|Rules that will be applied on authentication names and map them into local usernames.|DEFAULT|<ul><li>`RULE:[1:$1@$0](.*@EXAMPLE.COM)s/@.*//`</li><li>`DEFAULT`</li></ul>|
|`logsearch.http.header.access-control-allow-credentials`|Access-Control-Allow-Credentials header for Log Search Server.|true|<ul><li>`true`</li><li>`false`</li></ul>|
|`logsearch.http.header.access-control-allow-headers`|Access-Control-Allow-Headers header for Log Search Server.|origin, content-type, accept, authorization|<ul><li>`content-type, authorization`</li></ul>|
|`logsearch.http.header.access-control-allow-methods`|Access-Control-Allow-Methods header for Log Search Server.|GET, POST, PUT, DELETE, OPTIONS, HEAD|<ul><li>`GET, POST`</li></ul>|
|`logsearch.http.header.access-control-allow-origin`|Access-Control-Allow-Origin header for Log Search Server.|*|<ul><li>`*`</li><li>`http://c6401.ambari.apache.org`</li></ul>|
|`logsearch.http.port`|Log Search http port|61888|<ul><li>`61888`</li><li>`8888`</li></ul>|
|`logsearch.https.port`|Log Search https port|61889|<ul><li>`61889`</li><li>`8889`</li></ul>|
|`logsearch.jetty.access.log.enabled`|Enable jetty access logs|false|<ul><li>`true`</li></ul>|
|`logsearch.login.credentials.file`|Name of the credential file which contains the users for file authentication (see: logsearch.auth.file.enabled).|user_pass.json|<ul><li>`logsearch-admin.json`</li></ul>|
|`logsearch.protocol`|Log Search Protocol (http or https)|http|<ul><li>`http`</li><li>`https`</li></ul>|
|`logsearch.roles.allowed`|Comma separated roles for external authentication.|AMBARI.ADMINISTRATOR,CLUSTER.ADMINISTRATOR|<ul><li>`AMBARI.ADMINISTRATOR`</li></ul>|
|`logsearch.session.timeout`|Log Search http session timeout in minutes.|30|<ul><li>`300`</li></ul>|
|`logsearch.solr.audit.logs.alias.name`|Alias name for audit log collection (can be used for Log Search audit collection and ranger collection as well).|audit_logs_alias|<ul><li>`audit_logs_alias`</li></ul>|
|`logsearch.solr.audit.logs.collection`|Name of Log Search audit collection.|audit_logs|<ul><li>`audit_logs`</li></ul>|
|`logsearch.solr.audit.logs.config.name`|Solr configuration name of the audit collection.|audit_logs|<ul><li>`audit_logs`</li></ul>|
|`logsearch.solr.audit.logs.config_set.folder`|Location of Log Search audit collection configs for Solr.|/usr/lib/ambari-logsearch-portal/conf/solr_configsets|<ul><li>`/usr/lib/ambari-logsearch-portal/conf/solr_configsets`</li></ul>|
|`logsearch.solr.audit.logs.numshards`|Number of Solr shards for audit collection (bootstrapping).|1|<ul><li>`2`</li></ul>|
|`logsearch.solr.audit.logs.replication.factor`|Solr replication factor for audit collection (bootstrapping).|1|<ul><li>`2`</li></ul>|
|`logsearch.solr.audit.logs.url`|URL of Solr (non cloud mode) - currently unsupported.|`EMPTY`|<ul><li>`localhost1:8868`</li></ul>|
|`logsearch.solr.audit.logs.zk.acls`|List of Zookeeper ACLs for Log Search audit collection (Log Search and Solr must be able to read/write collection details)|`EMPTY`|<ul><li>`world:anyone:r,sasl:solr:cdrwa,sasl:logsearch:cdrwa`</li></ul>|
|`logsearch.solr.audit.logs.zk_connect_string`|Zookeeper connection string for Solr (used for audit log collection).|`EMPTY`|<ul><li>`localhost1:2181,localhost2:2181/mysolr_znode`</li></ul>|
|`logsearch.solr.config_set.folder`|Location of Solr collection configs.|/usr/lib/ambari-logsearch-portal/conf/solr_configsets|<ul><li>`/usr/lib/ambari-logsearch-portal/conf/solr_configsets`</li></ul>|
|`logsearch.solr.implicit.routing`|Use implicit routing for Solr Collections.|false|<ul><li>`true`</li></ul>|
|`logsearch.solr.implicit.routing`|Use implicit routing for Solr Collections.|false|<ul><li>`true`</li></ul>|
|`logsearch.solr.jaas.file`|Path of the JAAS file for Kerberos based Solr Cloud authentication.|/usr/lib/ambari-logsearch-portal/logsearch_solr_jaas.conf|<ul><li>`/my/path/jaas_file.conf`</li></ul>|
|`logsearch.solr.kerberos.enable`|Enable Kerberos Authentication for Solr Cloud.|false|<ul><li>`true`</li><li>`false`</li></ul>|
|`logsearch.solr.metadata`|Name of Log Search metadata collection.|logsearch_metadata|<ul><li>`logsearch_metadata`</li></ul>|
|`logsearch.solr.metadata.config.name`|Solr configuration name of the logsearch metadata collection.|logsearch_metadata|<ul><li>`logsearch_metadata`</li></ul>|
|`logsearch.solr.metadata.numshards`|Number of Solr shards for logsearch metadta collection (bootstrapping).|2|<ul><li>`3`</li></ul>|
|`logsearch.solr.metadata.replication.factor`|Solr replication factor for event metadata collection (bootstrapping).|2|<ul><li>`3`</li></ul>|
|`logsearch.solr.metadata.schema.fields.populate.interval.mins`|Interval in minutes for populating schema fiels for metadata collections.|1|<ul><li>`10`</li></ul>|
|`logsearch.solr.ranger.audit.logs.collection`|Name of Ranger audit collections (can be used if ranger audits managed by the same Solr which is used for Log Search).|`EMPTY`|<ul><li>`ranger_audits`</li></ul>|
|`logsearch.solr.service.logs`|Name of Log Search service log collection.|hadoop_logs|<ul><li>`hadoop_logs`</li></ul>|
|`logsearch.solr.service.logs.config.name`|Solr configuration name of the service log collection.|hadoop_logs|<ul><li>`hadoop_logs`</li></ul>|
|`logsearch.solr.service.logs.numshards`|Number of Solr shards for service log collection (bootstrapping).|1|<ul><li>`2`</li></ul>|
|`logsearch.solr.service.logs.replication.factor`|Solr replication factor for service log collection (bootstrapping).|1|<ul><li>`2`</li></ul>|
|`logsearch.solr.url`|URL of Solr (non cloud mode) - currently unsupported.|`EMPTY`|<ul><li>`localhost1:8868`</li></ul>|
|`logsearch.solr.zk.acls`|List of Zookeeper ACLs for Log Search Collections (Log Search and Solr must be able to read/write collection details)|`EMPTY`|<ul><li>`world:anyone:r,sasl:solr:cdrwa,sasl:logsearch:cdrwa`</li></ul>|
|`logsearch.solr.zk_connect_string`|Zookeeper connection string for Solr.|`EMPTY`|<ul><li>`localhost1:2181,localhost2:2181/mysolr_znode`</li></ul>|
|`logsearch.spnego.kerberos.enabled`|Enable SPNEGO based authentication for Log Search Server.|false|<ul><li>`true`</li><li>`false`</li></ul>|
|`logsearch.spnego.kerberos.host`||localhost|<ul><li>`c6401.ambari.apache.org`</li><li>`localhost`</li></ul>|
|`logsearch.spnego.kerberos.keytab`|Keytab for SPNEGO authentication for Http requests.|`EMPTY`|<ul><li>`/etc/security/keytabs/mykeytab.keytab`</li></ul>|
|`logsearch.spnego.kerberos.principal`|Principal for SPNEGO authentication for Http requests|`EMPTY`|<ul><li>`myuser@EXAMPLE.COM`</li></ul>|
|`logsearch.web.audit_logs.component.labels`|Map of component component labels.|ambari:Ambari,hdfs:Hdfs,RangerAudit:Ranger|<ul><li>`ambari:Ambari,RangerAudit:ranger`</li></ul>|
|`logsearch.web.audit_logs.field.common.excludes`|List of fields that will be excluded from metadata schema responses for every audit components.|tags,tags_str,seq_num|<ul><li>`reqUser,resp,tag_str`</li></ul>|
|`logsearch.web.audit_logs.field.common.filterable.common.excludes`|List of fields that will be excluded from filter selection on the UI for every audit components.|`EMPTY`|<ul><li>`tag_str,resp,tag_str`</li></ul>|
|`logsearch.web.audit_logs.field.common.labels`|Map of fields labels for audits (common).|enforcer:Access Enforcer,access:Access Type,cliIP:Client Ip,cliType:Client Type,dst:DST,evtTime:Event Time,ip:IP,logtime:Log Time,sess:Session,ugi:UGI,reqUser:User,repo:Audit Source|<ul><li>`reqUser:Req User,resp:Response`</li></ul>|
|`logsearch.web.audit_logs.field.common.visible`|List of fields that will be displayed by default on the UI for every audit components.|access,cliIP,evtTime,repo,resource,result,reqUser|<ul><li>`reqUser,resp`</li></ul>|
|`logsearch.web.audit_logs.field.excludes`|List of fields that will be excluded from metadata schema responses for different audit components.|`EMPTY`|<ul><li>`ambari:reqUser,resp,hdfs:ws_user,ws_role`</li></ul>|
|`logsearch.web.audit_logs.field.filterable.excludes`|List of fields that will be excluded from filter selection on the UI for different audit components.|`EMPTY`|<ul><li>`ambari:tag_str,resp,tag_str;RangerAudit:path,ip`</li></ul>|
|`logsearch.web.audit_logs.field.filterable.excludes`|Enable label fallback. (replace _ with spaces and capitalize properly)|true|<ul><li>`false`</li></ul>|
|`logsearch.web.audit_logs.field.labels`|Map of fields (key-value pairs) labels for different component types.|`EMPTY`|<ul><li>`ambari#reqUser:Ambari User,ws_response:Response;RangerAudit#reqUser:Req User`</li></ul>|
|`logsearch.web.audit_logs.field.visible`|List of fields that will be displayed by default on the UI for different audit components.|`EMPTY`|<ul><li>`ambari:reqUser,resp;RangerAudit:reqUser,repo`</li></ul>|
|`logsearch.web.labels.service_logs.field.fallback.prefixes`|List of prefixes that should be removed during fallback of audit field labels.|ws_,std_|<ul><li>`ws_,std_,sdi_`</li></ul>|
|`logsearch.web.labels.service_logs.field.fallback.prefixes`|List of prefixes that should be removed during fallback of service field labels.|ws_,sdi_,std_|<ul><li>`ws_,std_,sdi_`</li></ul>|
|`logsearch.web.labels.service_logs.field.fallback.suffixes`|List of suffixes that should be removed during fallback of audit field labels.|_i,_l,_s,_b|<ul><li>`_i,_l,_s,_b`</li></ul>|
|`logsearch.web.labels.service_logs.field.fallback.suffixes`|List of suffixes that should be removed during fallback of service field labels.|_i,_l,_s,_b|<ul><li>`_i,_l,_s,_b`</li></ul>|
|`logsearch.web.service_logs.component.labels`|Map of serivce component labels.|`EMPTY`|<ul><li>`ambari_agent:Ambari Agent,ambari_server:Ambari Servcer`</li></ul>|
|`logsearch.web.service_logs.field.excludes`|List of fields that will be excluded from metadata schema responses.|id,tags,text,message,seq_num,case_id,bundle_id,rowtype,event_count|<ul><li>`seq_num,tag`</li></ul>|
|`logsearch.web.service_logs.field.filterable.excludes`|List of fields that will be excluded from filter selection on the UI.|`EMPTY`|<ul><li>`path,method,logger_name`</li></ul>|
|`logsearch.web.service_logs.field.labels`|Map of serivce field labels.|log_message:Message,type:Component,logtime:Log Time,thread_name:Thread|<ul><li>`log_message:Message,ip:IP Address`</li></ul>|
|`logsearch.web.service_logs.field.visible`|List of fields that will be displayed by default on the UI.|log_message,level,logtime,type|<ul><li>`log_message,path,logtime`</li></ul>|
|`logsearch.web.service_logs.group.labels`|Map of serivce group labels|`EMPTY`|<ul><li>`ambari:Ambari,yarn:YARN`</li></ul>|
