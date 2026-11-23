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

import importlib.util
import os
from unittest import TestCase


class TestStackAdvisorProxyUsers(TestCase):
  """
  Proxy user hosts recommended for a user whose components are not installed (hbase without the HBase REST
  server, httpfs without the HttpFS gateway, hcat without WebHCat): the list of hosts is empty.
  """

  def setUp(self):
    stacks_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../main/resources/stacks')
    spec = importlib.util.spec_from_file_location('stack_advisor_proxy_users', os.path.join(stacks_path, 'stack_advisor.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    self.advisor = module.DefaultStackAdvisor()

  def recommend(self, method, prefix, core_site, user, value):
    services = {"configurations": {"core-site": {"properties": core_site}}}
    recommended = {}
    getattr(self.advisor, method)(user, value, services=services, configurations={},
                                  put_function=lambda name, val: recommended.__setitem__(name, val))
    return recommended["{0}.proxyuser.{1}.hosts".format(prefix, user)]

  def test_wildcard_set_on_the_cluster_is_kept(self):
    self.assertEqual("*", self.recommend("put_proxyuser_value", "hadoop", {"hadoop.proxyuser.hbase.hosts": "*"}, "hbase", ""))

  def test_nothing_is_opened_without_an_existing_value(self):
    self.assertEqual("", self.recommend("put_proxyuser_value", "hadoop", {}, "hbase", ""))

  def test_hosts_set_on_the_cluster_are_kept(self):
    self.assertEqual("gw1.example.com", self.recommend("put_proxyuser_value", "hadoop", {"hadoop.proxyuser.hcat.hosts": "gw1.example.com"}, "hcat", ""))

  def test_hosts_of_installed_components_are_recommended(self):
    self.assertEqual("hs2a.example.com,hs2b.example.com",
                     self.recommend("put_proxyuser_value", "hadoop", {"hadoop.proxyuser.hive.hosts": "*"}, "hive", "hs2b.example.com,hs2a.example.com"))

  def test_httpfs_proxy_users_follow_the_same_rule(self):
    self.assertEqual("*", self.recommend("put_httpfs_proxyuser_value", "httpfs", {"hadoop.proxyuser.hue.hosts": "*"}, "hue", ""))
