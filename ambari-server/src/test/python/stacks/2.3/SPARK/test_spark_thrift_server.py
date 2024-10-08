#!/usr/bin/env python

'''
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
'''
import json
from mock.mock import MagicMock, patch
from stacks.utils.RMFTestCase import *

from only_for_platform import not_for_platform, PLATFORM_WINDOWS

@not_for_platform(PLATFORM_WINDOWS)
@patch("resource_management.libraries.functions.get_stack_version", new=MagicMock(return_value="2.3.2.0-1597"))
class TestSparkThriftServer(RMFTestCase):
  COMMON_SERVICES_PACKAGE_DIR = "SPARK/1.2.1/package"
  STACK_VERSION = "2.3"
  DEFAULT_IMMUTABLE_PATHS = ['/apps/hive/warehouse', '/apps/falcon', '/mr-history/done', '/app-logs', '/tmp']

  CONFIG_OVERRIDES = {"serviceName":"SPARK", "role":"SPARK_THRIFTSERVER"}

  @patch("resource_management.libraries.functions.copy_tarball.copy_to_hdfs")
  def test_configure_default(self, copy_to_hdfs_mock):
    copy_to_hdfs_mock = True
    self.executeScript(self.COMMON_SERVICES_PACKAGE_DIR + "/scripts/spark_thrift_server.py",
                   classname = "SparkThriftServer",
                   command = "configure",
                   config_file="spark_default.json",
                   stack_version = self.STACK_VERSION,
                   target = RMFTestCase.TARGET_COMMON_SERVICES
    )
    self.assert_configure_default()
    self.assertNoMoreResources()

  @patch("resource_management.libraries.functions.copy_tarball.copy_to_hdfs")
  def test_start_default(self, copy_to_hdfs_mock):
    copy_to_hdfs_mock.return_value = False
    self.executeScript(self.COMMON_SERVICES_PACKAGE_DIR + "/scripts/spark_thrift_server.py",
                   classname = "SparkThriftServer",
                   command = "start",
                   config_file="spark_default.json",
                   stack_version = self.STACK_VERSION,
                   target = RMFTestCase.TARGET_COMMON_SERVICES
    )
    self.assert_configure_default()
    self.assertResourceCalled('Execute', '/usr/hdp/current/spark-client/sbin/start-thriftserver.sh --properties-file /usr/hdp/current/spark-client/conf/spark-thrift-sparkconf.conf --driver-memory 1g',
        environment = {'JAVA_HOME': u'/usr/jdk64/jdk1.7.0_45'},
        not_if = 'ls /var/run/spark/spark-hive-org.apache.spark.sql.hive.thriftserver.HiveThriftServer2-1.pid >/dev/null 2>&1 && ps -p `cat /var/run/spark/spark-hive-org.apache.spark.sql.hive.thriftserver.HiveThriftServer2-1.pid` >/dev/null 2>&1',
        user = 'hive',
    )
    self.assertNoMoreResources()

  def test_stop_default(self):
    self.executeScript(self.COMMON_SERVICES_PACKAGE_DIR + "/scripts/spark_thrift_server.py",
                   classname = "SparkThriftServer",
                   command = "stop",
                   config_file="spark_default.json",
                   stack_version = self.STACK_VERSION,
                   target = RMFTestCase.TARGET_COMMON_SERVICES
    )
    self.assertResourceCalled('Execute', '/usr/hdp/current/spark-client/sbin/stop-thriftserver.sh',
        environment = {'JAVA_HOME': u'/usr/jdk64/jdk1.7.0_45'},
        user = 'hive',
    )
    self.assertResourceCalled('File', '/var/run/spark/spark-hive-org.apache.spark.sql.hive.thriftserver.HiveThriftServer2-1.pid',
        action = ['delete'],
    )
    self.assertNoMoreResources()

  def assert_configure_default(self):
    self.assertResourceCalled('Directory', '/var/run/spark',
        owner = 'spark',
        group = 'hadoop',
        create_parents = True,
        mode = 0o775,
        cd_access = 'a',
    )
    self.assertResourceCalled('Directory', '/var/log/spark',
        owner = 'spark',
        group = 'hadoop',
        create_parents = True,
        mode = 0o775,
        cd_access = 'a',
    )
    self.assertResourceCalled('HdfsResource', '/user/spark',
        immutable_paths = self.DEFAULT_IMMUTABLE_PATHS,
        security_enabled = False,
        hadoop_bin_dir = '/usr/hdp/2.3.2.0-2067/hadoop/bin',
        keytab = UnknownConfigurationMock(),
        default_fs = 'hdfs://c6401.ambari.apache.org:8020',
        hdfs_site = {u'a': u'b'},
        kinit_path_local = '/usr/bin/kinit',
        principal_name = UnknownConfigurationMock(),
        user = 'hdfs',
        owner = 'spark',
        hadoop_conf_dir = '/usr/hdp/2.3.2.0-2067/hadoop/conf',
        type = 'directory',
        action = ['create_on_execute'], hdfs_resource_ignore_file='/var/lib/ambari-agent/data/.hdfs_resource_ignore',
        dfs_type = '',
        mode = 0o775,
    )
    self.assertResourceCalled('HdfsResource', None,
        immutable_paths = self.DEFAULT_IMMUTABLE_PATHS,
        security_enabled = False,
        hadoop_bin_dir = '/usr/hdp/2.3.2.0-2067/hadoop/bin',
        keytab = UnknownConfigurationMock(),
        default_fs = 'hdfs://c6401.ambari.apache.org:8020',
        hdfs_site = {u'a': u'b'},
        kinit_path_local = '/usr/bin/kinit',
        principal_name = UnknownConfigurationMock(),
        user = 'hdfs',
        action = ['execute'], hdfs_resource_ignore_file='/var/lib/ambari-agent/data/.hdfs_resource_ignore',
        dfs_type = '',
        hadoop_conf_dir = '/usr/hdp/2.3.2.0-2067/hadoop/conf',
    )
    self.assertResourceCalled('PropertiesFile', '/usr/hdp/current/spark-client/conf/spark-defaults.conf',
        owner = 'spark',
        key_value_delimiter = ' ',
        group = 'spark',
        properties = self.getConfig()['configurations']['spark-defaults'],
        mode = 0o644
    )
    self.assertResourceCalled('File', '/usr/hdp/current/spark-client/conf/spark-env.sh',
        content = InlineTemplate(self.getConfig()['configurations']['spark-env']['content']),
        owner = 'spark',
        group = 'spark',
        mode = 0o644,
    )
    self.assertResourceCalled('File', '/usr/hdp/current/spark-client/conf/log4j.properties',
        content = '\n# Set everything to be logged to the console\nlog4j.rootCategory=INFO, console\nlog4j.appender.console=org.apache.log4j.ConsoleAppender\nlog4j.appender.console.target=System.err\nlog4j.appender.console.layout=org.apache.log4j.PatternLayout\nlog4j.appender.console.layout.ConversionPattern=%d{yy/MM/dd HH:mm:ss} %p %c{1}: %m%n\n\n# Settings to quiet third party logs that are too verbose\nlog4j.logger.org.eclipse.jetty=WARN\nlog4j.logger.org.eclipse.jetty.util.component.AbstractLifeCycle=ERROR\nlog4j.logger.org.apache.spark.repl.SparkIMain$exprTyper=INFO\nlog4j.logger.org.apache.spark.repl.SparkILoop$SparkILoopInterpreter=INFO',
        owner = 'spark',
        group = 'spark',
        mode = 0o644,
    )
    self.assertResourceCalled('File', '/usr/hdp/current/spark-client/conf/metrics.properties',
        content = InlineTemplate(self.getConfig()['configurations']['spark-metrics-properties']['content']),
        owner = 'spark',
        group = 'spark',
        mode = 0o644
    )
    self.assertResourceCalled('Directory', '/usr/hdp/current/spark-client/logs',
        owner = 'spark',
        group = 'spark',
        mode = 0o755,
    )
    self.assertResourceCalled('PropertiesFile', '/usr/hdp/current/spark-client/conf/spark-thrift-sparkconf.conf',
        key_value_delimiter = ' ',
        owner = 'hive',
        group = 'hadoop',
        properties = self.getConfig()['configurations']['spark-thrift-sparkconf'],
        mode = 0o644
    )
    self.assertResourceCalled('File', '/usr/hdp/current/spark-client/conf/java-opts',
        content = InlineTemplate('  -Dhdp.version=None'),
        owner = 'spark',
        group = 'spark',
        mode = 0o644
    )

  @patch("resource_management.libraries.functions.copy_tarball.copy_to_hdfs")
  def test_pre_upgrade_restart_23(self, copy_to_hdfs_mock):
    config_file = self.get_src_folder()+"/test/python/stacks/2.2/configs/default.json"
    with open(config_file, "r") as f:
      json_content = json.load(f)
    version = '2.3.2.0-1234'
    json_content['commandParams']['version'] = version

    copy_to_hdfs_mock.return_value = True
    mocks_dict = {}
    self.executeScript(self.COMMON_SERVICES_PACKAGE_DIR + "/scripts/spark_thrift_server.py",
                       classname = "SparkThriftServer",
                       command = "pre_upgrade_restart",
                       config_dict = json_content,
                       config_overrides = self.CONFIG_OVERRIDES,
                       stack_version = self.STACK_VERSION,
                       target = RMFTestCase.TARGET_COMMON_SERVICES,
                       mocks_dict = mocks_dict)

    self.assertResourceCalledIgnoreEarlier('Execute', ('ambari-python-wrap', '/usr/bin/hdp-select', 'set', 'spark-thriftserver', version), sudo=True)
    self.assertNoMoreResources()
