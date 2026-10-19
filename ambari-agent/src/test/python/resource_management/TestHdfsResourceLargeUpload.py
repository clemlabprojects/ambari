#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from types import SimpleNamespace
from unittest import TestCase
from unittest.mock import Mock, patch

from resource_management.core.environment import Environment
from resource_management.core.exceptions import Fail
from resource_management.libraries.providers.hdfs_resource import (
  HdfsResourceWebHDFS, LOCAL_FILE_HDFS_CLI_THRESHOLD, WebHDFSUtil,
)


MODULE = 'resource_management.libraries.providers.hdfs_resource'


class TestHdfsResourceLargeUpload(TestCase):
  def setUp(self):
    self.env = Environment()
    self.env.__enter__()
    self.target = '/odp/apps/test/mapreduce/mapreduce.tar.gz'
    self.source = '/usr/odp/test/hadoop/mapreduce.tar.gz'
    self.provider = HdfsResourceWebHDFS()
    self.provider.main_resource = SimpleNamespace(resource=SimpleNamespace(
      target=self.target, user='hdfs', replace_existing_files=True,
      hadoop_bin_dir='/usr/odp/test/hadoop/bin',
      hadoop_conf_dir='/etc/hadoop/test/0',
    ))
    self.provider.target_status = None
    self.provider.util = Mock()
    self.provider.kerberos_env = {'KRB5CCNAME': '/tmp/webhdfs-hdfs-cache'}
    self.checked_call = patch(MODULE + '.shell.checked_call').start()
    self.getsize = patch(MODULE + '.os.path.getsize',
                         return_value=LOCAL_FILE_HDFS_CLI_THRESHOLD + 1).start()
    self.addCleanup(patch.stopall)

  def tearDown(self):
    self.env.__exit__(None, None, None)

  def test_large_file_uses_selected_hadoop_and_kerberos_cache(self):
    self.provider._create_file(self.target, self.source, '444')
    self.checked_call.assert_called_once_with(
      ['/usr/odp/test/hadoop/bin/hadoop', '--config', '/etc/hadoop/test/0',
       'fs', '-copyFromLocal', '-f', self.source, self.target],
      user='hdfs', env=self.provider.kerberos_env,
    )
    self.provider.util.run_command.assert_not_called()

  def test_threshold_file_uses_webhdfs(self):
    self.getsize.return_value = LOCAL_FILE_HDFS_CLI_THRESHOLD
    self.provider._create_file(self.target, self.source, '444')
    self.checked_call.assert_not_called()
    self.provider.util.run_command.assert_called_once_with(
      self.target, 'CREATE', method='PUT', overwrite=True,
      assertable_result=False, file_to_put=self.source, permission='444',
    )

  def test_insecure_upload_without_explicit_hadoop_directory(self):
    self.provider.main_resource.resource.hadoop_bin_dir = None
    self.provider.kerberos_env = None
    self.provider._create_file(self.target, self.source)
    args, kwargs = self.checked_call.call_args
    self.assertEqual('hadoop', args[0][0])
    self.assertIsNone(kwargs['env'])

  def test_empty_file_uses_webhdfs(self):
    self.provider._create_file(self.target)
    self.checked_call.assert_not_called()
    self.provider.util.run_command.assert_called_once_with(
      self.target, 'CREATE', method='PUT', overwrite=True,
      assertable_result=False, file_to_put=None,
    )

  def test_failed_new_upload_removes_partial_file(self):
    failure = Fail('upload failed')
    self.checked_call.side_effect = failure
    self.provider._get_file_status = Mock(return_value={'length': 128})
    with self.assertRaises(Fail) as raised:
      self.provider._create_file(self.target, self.source)
    self.assertIs(failure, raised.exception)
    self.provider.util.run_command.assert_called_once_with(
      self.target, 'DELETE', method='DELETE', recursive=True,
      ignore_status_codes=['404'],
    )

  def test_cleanup_failure_preserves_upload_exception(self):
    failure = Fail('upload failed')
    self.checked_call.side_effect = failure
    self.provider._get_file_status = Mock(side_effect=Fail('cleanup failed'))
    with patch(MODULE + '.Logger.exception'):
      with self.assertRaises(Fail) as raised:
        self.provider._create_file(self.target, self.source)
    self.assertIs(failure, raised.exception)

  @patch(MODULE + '.os.stat')
  def test_failed_overwrite_does_not_delete_existing_file(self, stat):
    self.provider.target_status = {'length': 100}
    stat.return_value.st_size = LOCAL_FILE_HDFS_CLI_THRESHOLD + 1
    self.checked_call.side_effect = Fail('upload failed')
    with self.assertRaises(Fail):
      self.provider._create_file(self.target, self.source)
    self.provider.util.run_command.assert_not_called()

  @patch(MODULE + '.os.stat')
  def test_existing_file_is_preserved_when_replacement_disabled(self, stat):
    self.provider.target_status = {'length': 100}
    self.provider.main_resource.resource.replace_existing_files = False
    stat.return_value.st_size = LOCAL_FILE_HDFS_CLI_THRESHOLD + 1
    self.provider._create_file(self.target, self.source)
    self.checked_call.assert_not_called()
    self.provider.util.run_command.assert_not_called()

  @patch(MODULE + '.os.path.exists', return_value=True)
  @patch(MODULE + '.get_user_call_output', return_value=(0, '201', ''))
  def test_curl_request_body_never_uses_streaming_upload(self, call, exists):
    util = WebHDFSUtil.__new__(WebHDFSUtil)
    util.address = 'http://namenode:9870'
    util.run_user = 'hdfs'
    util.security_enabled = False
    util.is_https_enabled = False
    util.logoutput = False
    util.kerberos_env = None
    util._run_command(self.target, 'CREATE', method='PUT',
                      assertable_result=False, file_to_put=self.source)
    command = call.call_args[0][0]
    self.assertNotIn('-T', command)
    self.assertIn('--data-binary', command)
    self.assertIn('@' + self.source, command)
