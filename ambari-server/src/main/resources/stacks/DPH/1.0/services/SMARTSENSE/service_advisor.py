#!/usr/bin/env ambari-python-wrap
"""
Copyright (c) 2011-2018, Hortonworks Inc.  All rights reserved.
Except as expressly permitted in a written agreement between you
or your company and Hortonworks, Inc, any use, reproduction,
modification,
redistribution, sharing, lending or other exploitation
of all or any part of the contents of this file is strictly prohibited.
"""
import os
import imp
import traceback
from urlparse import urlparse

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
STACKS_DIR = os.path.join(SCRIPT_DIR, '../../../../')
PARENT_FILE = os.path.join(STACKS_DIR, 'service_advisor.py')

try:
  with open(PARENT_FILE, 'rb') as fp:
    service_advisor = imp.load_module('service_advisor', fp, PARENT_FILE, ('.py', 'rb', imp.PY_SOURCE))
except Exception as e:
  traceback.print_exc()
  print "Failed to load parent"

class HDP21SMARTSENSEServiceAdvisor(service_advisor.ServiceAdvisor):

    def __init__(self, *args, **kwargs):
        self.as_super = super(HDP21SMARTSENSEServiceAdvisor, self)
        self.as_super.__init__(*args, **kwargs)

    def colocateService(self, hostsComponentsMap, serviceComponents):
        pass

    def getServiceConfigurationRecommendations(self, configurations, clusterData, services, hosts):

        # Automatically generate the hst server url in the form http(s)://<hst server host>:<hst ui port>
        protocol = 'http'
        port = 9000
        hst_server_hostname = 'localhost'

        if 'hst-server-conf' in services['configurations'] and 'properties' in services['configurations']['hst-server-conf']:
            if 'server.ssl_enabled' in services['configurations']['hst-server-conf']['properties'] \
                and (services['configurations']['hst-server-conf']['properties']['server.ssl_enabled'] == True \
                      or str(services['configurations']['hst-server-conf']['properties']['server.ssl_enabled']).lower()) == 'true':
                    protocol = "https"

            if 'server.port' in services['configurations']['hst-server-conf']['properties']:
                    port = services['configurations']['hst-server-conf']['properties']['server.port']

            hst_server_hostnames = self.getComponentHostNames(services, "SMARTSENSE", "HST_SERVER")
            if hst_server_hostnames :
                hst_server_hostname = hst_server_hostnames[0]
            print "Ambari returned '%s' as HST server hostname." % hst_server_hostname
            if hst_server_hostname == 'localhost':
                if 'server.url' in services['configurations']['hst-server-conf']['properties']:
                    server_url = services['configurations']['hst-server-conf']['properties']['server.url']
                    prev_hst_server_hostname = urlparse(server_url).hostname if server_url and server_url.startswith('http') else ''
                    if len(prev_hst_server_hostname.strip()) > 0:
                        print "Setting previous set server.url host '%s' as HST server hostname." % prev_hst_server_hostname
                        hst_server_hostname = prev_hst_server_hostname

        putHstServerConfProperty = self.putProperty(configurations, "hst-server-conf", services)
        putHstServerConfProperty('server.url', protocol + "://" + hst_server_hostname + ":" + str(port))


        # if self.isSecurityEnabled(services): ## Commenting out as we should have these setup even for non kerberos clusters
        # Get activity-conf/global.activity.analyzer.user and activity-conf/activity.explorer.user, if available
        if 'activity-conf' in services['configurations'] and 'properties' in services['configurations']['activity-conf']:
            global_activity_analyzer_user = services['configurations']['activity-conf']['properties']['global.activity.analyzer.user'] \
                if 'global.activity.analyzer.user' in services['configurations']['activity-conf']['properties'] \
                else None

            activity_explorer_user = services['configurations']['activity-conf']['properties']['activity.explorer.user'] \
                if 'activity.explorer.user' in services['configurations']['activity-conf']['properties'] \
                else None
        else:
            global_activity_analyzer_user = None
            activity_explorer_user = None

        # If activity-conf/global.activity.analyzer.user is available, append it to the set of users
        # listed in yarn-site/yarn.admin.acl
        if global_activity_analyzer_user is not None and global_activity_analyzer_user != '':
          if ('yarn-site' in services['configurations']) and ('properties' in services['configurations']['yarn-site']):
            yarn_site_properties = services["configurations"]["yarn-site"]["properties"]

            if 'yarn-site' in configurations and 'properties' in configurations['yarn-site'] \
              and 'yarn.admin.acl' in configurations['yarn-site']['properties']:
              yarn_admin_acl = configurations['yarn-site']['properties']['yarn.admin.acl']
            elif 'yarn.admin.acl' in yarn_site_properties:
              yarn_admin_acl = yarn_site_properties['yarn.admin.acl']
            else:
              yarn_admin_acl = None

            # Create a unique set of user names for the new yarn.admin.acl
            user_names = set()
            user_names.add(global_activity_analyzer_user)

            if yarn_admin_acl is not None and yarn_admin_acl != '':
              # Parse yarn_admin_acl to get a set of unique user names
              for user_name in yarn_admin_acl.split(','):
                user_name = user_name.strip()
                if user_name:
                  user_names.add(user_name)

            yarn_admin_acl = ','.join(user_names)

            putYarnSiteProperty = self.putProperty(configurations, "yarn-site", services)
            putYarnSiteProperty('yarn.admin.acl', yarn_admin_acl)


        # If activity-conf/global.activity.analyzer.user or activity-conf/activity.explorer.user are
        # available, append them to the set of users listed in ams-hbase-site/hbase.superuser
        if (global_activity_analyzer_user is not None and global_activity_analyzer_user != '') \
          or (activity_explorer_user is not None and activity_explorer_user != ''):

          if ('ams-hbase-site' in services['configurations']) and ('properties' in services['configurations']['ams-hbase-site']):
            ams_hbase_site_properties = services["configurations"]["ams-hbase-site"]["properties"]

            if 'ams-hbase-site' in configurations and 'properties' in configurations['ams-hbase-site'] \
              and 'hbase.superuser' in configurations['ams-hbase-site']['properties']:
              hbase_superuser = configurations['ams-hbase-site']['properties']['hbase.superuse']
            elif 'hbase.superuser' in ams_hbase_site_properties:
              hbase_superuser = ams_hbase_site_properties['hbase.superuser']
            else:
              hbase_superuser = None

            # Create a unique set of user names for the new hbase.superuser value
            user_names = set()
            if global_activity_analyzer_user is not None and global_activity_analyzer_user != '':
              user_names.add(global_activity_analyzer_user)

            if activity_explorer_user is not None and activity_explorer_user != '':
              user_names.add(activity_explorer_user)

            # Parse hbase_superuser to get a set of unique user names
            if hbase_superuser is not None and hbase_superuser != '':
              for user_name in hbase_superuser.split(','):
                user_name = user_name.strip()
                if user_name:
                  user_names.add(user_name)

            hbase_superuser = ','.join(user_names)

            putAmsHbaseSiteProperty = self.putProperty(configurations, "ams-hbase-site", services)
            putAmsHbaseSiteProperty('hbase.superuser', hbase_superuser)

    def getServiceComponentLayoutValidations(self, services, hosts):
        return []

    def getServiceConfigurationsValidationItems(self, configurations, recommendedDefaults, services, hosts):
        return []

class HDP30SMARTSENSEServiceAdvisor(HDP21SMARTSENSEServiceAdvisor):
    def __init__(self, *args, **kwargs):
        self.as_super = super(HDP30SMARTSENSEServiceAdvisor, self)
        self.as_super.__init__(*args, **kwargs)

class HDF32SMARTSENSEServiceAdvisor(HDP21SMARTSENSEServiceAdvisor):
    def __init__(self, *args, **kwargs):
        self.as_super = super(HDF32SMARTSENSEServiceAdvisor, self)
        self.as_super.__init__(*args, **kwargs)

