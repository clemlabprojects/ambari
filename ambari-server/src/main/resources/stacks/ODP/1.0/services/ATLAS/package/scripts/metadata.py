#!/usr/bin/env python3
"""
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

"""
import os
import hashlib

from resource_management import Package
from resource_management import StackFeature
from resource_management.core.resources.system import Directory, File, Execute
from resource_management.core.source import StaticFile, InlineTemplate, Template
from resource_management.core.exceptions import Fail
from resource_management.libraries.functions.format import format
from resource_management.libraries.functions.default import default
from resource_management.libraries.functions.decorator import retry
from resource_management.libraries.functions import solr_cloud_util
from resource_management.libraries.functions.generate_logfeeder_input_config import generate_logfeeder_input_config
from resource_management.libraries.functions.stack_features import check_stack_feature, get_stack_feature_version
from resource_management.libraries.resources.properties_file import PropertiesFile
from resource_management.libraries.resources.template_config import TemplateConfig
from resource_management.libraries.resources.xml_config import XmlConfig
from resource_management.libraries.functions.is_empty import is_empty
from resource_management.libraries.resources.modify_properties_file import ModifyPropertiesFile


def metadata(type='server'):
    import params

    # Needed by both Server and Client
    Directory(params.conf_dir,
              mode=0o755,
              cd_access='a',
              owner=params.metadata_user,
              group=params.user_group,
              create_parents = True
    )

    if type == "server":
      Directory([params.pid_dir],
                mode=0o755,
                cd_access='a',
                owner=params.metadata_user,
                group=params.user_group,
                create_parents = True
      )
      Directory(format('{conf_dir}/solr'),
                mode=0o755,
                cd_access='a',
                owner=params.metadata_user,
                group=params.user_group,
                create_parents = True,
                recursive_ownership=True
      )
      Directory(params.log_dir,
                mode=0o755,
                cd_access='a',
                owner=params.metadata_user,
                group=params.user_group,
                create_parents = True
      )
      Directory(params.data_dir,
                mode=0o644,
                cd_access='a',
                owner=params.metadata_user,
                group=params.user_group,
                create_parents = True
      )
      Directory(params.expanded_war_dir,
                mode=0o644,
                cd_access='a',
                owner=params.metadata_user,
                group=params.user_group,
                create_parents = True
      )
      File(format("{expanded_war_dir}/atlas.war"),
           content = StaticFile(format('{metadata_home}/server/webapp/atlas.war'))
      )
      File(format("{conf_dir}/atlas-log4j.xml"),
           mode=0o644,
           owner=params.metadata_user,
           group=params.user_group,
           content=InlineTemplate(params.metadata_log4j_content)
      )
      File(format("{conf_dir}/atlas-env.sh"),
           owner=params.metadata_user,
           group=params.user_group,
           mode=0o755,
           content=InlineTemplate(params.metadata_env_content)
      )

      if params.atlas_admin_username and params.atlas_admin_password:
        psswd_output = hashlib.sha256(params.atlas_admin_password.encode('utf-8')).hexdigest()
        ModifyPropertiesFile(format("{conf_dir}/users-credentials.properties"),
          properties={format(params.atlas_admin_username): format(f'ROLE_ADMIN::{psswd_output}')},
          owner=params.metadata_user
        )

      files_to_chown = [format("{conf_dir}/atlas-simple-authz-policy.json"), format("{conf_dir}/users-credentials.properties")]
      for file in files_to_chown:
        if os.path.exists(file):
          Execute(('chown', format('{metadata_user}:{user_group}'), file),
                  sudo=True
                  )
          Execute(('chmod', '640', file),
                  sudo=True
                  )

      if params.metadata_solrconfig_content:
        File(format("{conf_dir}/solr/solrconfig.xml"),
             mode=0o644,
             owner=params.metadata_user,
             group=params.user_group,
             content=InlineTemplate(params.metadata_solrconfig_content)
        )
        File(format("{conf_dir}/solr/schema.xml"),
             mode=0o644,
             owner=params.metadata_user,
             group=params.user_group,
             content=InlineTemplate(params.metadata_solrschema_content)
        )

      generate_logfeeder_input_config('atlas', Template("input.config-atlas.json.j2", extra_imports=[default]))

    PropertiesFile(format('{conf_dir}/{conf_file}'),
         properties = params.application_properties,
         mode=0o600,
         owner=params.metadata_user,
         group=params.user_group
    )

    if params.security_enabled:
      TemplateConfig(format(params.atlas_jaas_file),
                     owner=params.metadata_user)

    if type == 'server' and params.search_backend_solr and params.has_infra_solr:
      solr_cloud_util.setup_solr_client(params.config)
      check_znode()
      jaasFile=params.atlas_jaas_file if params.security_enabled else None
      upload_conf_set('atlas_configs', jaasFile)

      if params.security_enabled: # update permissions before creating the collections
        solr_cloud_util.add_solr_roles(params.config,
                                       roles = [params.infra_solr_role_atlas, params.infra_solr_role_ranger_audit, params.infra_solr_role_dev],
                                       new_service_principals = [params.atlas_jaas_principal])

      create_collection('vertex_index', 'atlas_configs', jaasFile)
      create_collection('edge_index', 'atlas_configs', jaasFile)
      create_collection('fulltext_index', 'atlas_configs', jaasFile)

      if params.security_enabled:
        secure_znode(format('{infra_solr_znode}/configs/atlas_configs'), jaasFile)
        secure_znode(format('{infra_solr_znode}/collections/vertex_index'), jaasFile)
        secure_znode(format('{infra_solr_znode}/collections/edge_index'), jaasFile)
        secure_znode(format('{infra_solr_znode}/collections/fulltext_index'), jaasFile)

    File(params.atlas_hbase_setup,
         group=params.user_group,
         owner=params.hbase_user,
         content=Template("atlas_hbase_setup.rb.j2")
    )

    is_atlas_upgrade_support = check_stack_feature(StackFeature.ATLAS_UPGRADE_SUPPORT, get_stack_feature_version(params.config))

    if is_atlas_upgrade_support and params.security_enabled:

      if params.atlas_kafka_3_acl_support:
        File(params.atlas_kafka_setup,
            group=params.user_group,
            owner=params.kafka_user,
            content=Template("atlas_kafka_3_acl.sh.j2"))

        File(params.kafka_cmd_config_file,
            group=params.user_group,
            owner=params.kafka_user,
            content=Template("atlas_kafka_3_cmd_config.txt.j2"))
      else:
        File(params.atlas_kafka_setup,
            group=params.user_group,
            owner=params.kafka_user,
            content=Template("atlas_kafka_acl.sh.j2"))

      #  files required only in case if kafka broker is not present on the host as configured component
      if not params.host_with_kafka:
        File(format("{kafka_conf_dir}/kafka-env.sh"),
             owner=params.kafka_user,
             content=InlineTemplate(params.kafka_env_sh_template))

        File(format("{kafka_conf_dir}/kafka_jaas.conf"),
             group=params.user_group,
             owner=params.kafka_user,
             content=Template("kafka_jaas.conf.j2"))

    if params.stack_supports_atlas_hdfs_site_on_namenode_ha and len(params.namenode_host) > 1:
      XmlConfig("hdfs-site.xml",
                conf_dir=params.conf_dir,
                configurations=params.config['configurations']['hdfs-site'],
                configuration_attributes=params.config['configurationAttributes']['hdfs-site'],
                owner=params.metadata_user,
                group=params.user_group,
                mode=0o644
                )
    else:
      File(format('{conf_dir}/hdfs-site.xml'), action="delete")

    '''
    Atlas requires hadoop core-site.xml to resolve users/groups synced in HadoopUGI for
    authentication and authorization process. Earlier the core-site.xml was available in
    Hbase conf directory which is a part of Atlas class-path, from stack 2.6 onwards,
    core-site.xml is no more available in Hbase conf directory. Hence need to create
    core-site.xml in Atlas conf directory.
    '''
    if params.stack_supports_atlas_core_site and params.has_namenode:
      XmlConfig("core-site.xml",
        conf_dir=params.conf_dir,
        configurations=params.config['configurations']['core-site'],
        configuration_attributes=params.config['configurationAttributes']['core-site'],
        owner=params.metadata_user,
        group=params.user_group,
        mode=0o644,
        xml_include_file=params.mount_table_xml_inclusion_file_full_path
      )

      if params.mount_table_content:
        File(params.mount_table_xml_inclusion_file_full_path,
             owner=params.metadata_user,
             group=params.user_group,
             content=params.mount_table_content,
             mode=0o644
        )

    Directory(format('{metadata_home}/'),
      owner = params.metadata_user,
      group = params.user_group,
      recursive_ownership = True,
    )

def upload_conf_set(config_set, jaasFile):
  import params

  solr_cloud_util.upload_configuration_to_zk(
      zookeeper_quorum=params.zookeeper_quorum,
      solr_znode=params.infra_solr_znode,
      config_set_dir=format("{conf_dir}/solr"),
      config_set=config_set,
      tmp_dir=params.tmp_dir,
      java64_home=params.java64_home,
      solrconfig_content=InlineTemplate(params.metadata_solrconfig_content),
      jaas_file=jaasFile,
      retry=30, interval=5)

def create_collection(collection, config_set, jaasFile):
  import params

  solr_cloud_util.create_collection(
      zookeeper_quorum=params.zookeeper_quorum,
      solr_znode=params.infra_solr_znode,
      collection = collection,
      config_set=config_set,
      java64_home=params.java64_home,
      jaas_file=jaasFile,
      shards=params.atlas_solr_shards,
      replication_factor = params.infra_solr_replication_factor)

def secure_znode(znode, jaasFile):
  import params
  solr_cloud_util.secure_znode(config=params.config, zookeeper_quorum=params.zookeeper_quorum,
                               solr_znode=znode,
                               jaas_file=jaasFile,
                               java64_home=params.java64_home, sasl_users=[params.atlas_jaas_principal])



@retry(times=10, sleep_time=5, err_class=Fail)
def check_znode():
  import params
  solr_cloud_util.check_znode(
    zookeeper_quorum=params.zookeeper_quorum,
    solr_znode=params.infra_solr_znode,
    java64_home=params.java64_home)
