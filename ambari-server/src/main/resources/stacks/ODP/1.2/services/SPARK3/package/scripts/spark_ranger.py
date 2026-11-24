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

from resource_management.core.logger import Logger
from resource_management.libraries.functions.ranger_functions_v2 import RangeradminV2


def _ranger_admin():
  import params
  return RangeradminV2(url=params.ranger_policymgr_url, skip_if_rangeradmin_down=True)


def create_spark_ranger_user():
  """
  Declares the spark user in Ranger, so that policies can name it before the user sync has brought it.
  Never fails the command: the Spark servers do not need Ranger to start.
  """
  import params

  if not params.ranger_yarn_authorization:
    return
  try:
    _ranger_admin().create_policy_user(params.ranger_admin_username, params.ranger_admin_password, params.spark_user)
  except Exception as e:
    Logger.warning("Could not create the user %s in Ranger: %s" % (params.spark_user, str(e)))

