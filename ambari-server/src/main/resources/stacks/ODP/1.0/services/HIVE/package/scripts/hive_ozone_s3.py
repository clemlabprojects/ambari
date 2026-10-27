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
import pwd
import shlex
import tempfile

from resource_management.core.logger import Logger
from resource_management.libraries.functions.get_user_call_output import get_user_call_output


def ensure_ozone_s3_credential():
  """
  Makes the Ozone S3 Gateway accept the S3A credential of the Hive Metastore.

  The credential is configuration: fs.s3a.access.key (an Ozone S3 identity, the Hive user by
  default) and the password fs.s3a.secret.key of hive-site, kept in the Ambari credential store.
  As the Ozone administrator, the identity is created when missing ('getsecret') and given the
  configured secret ('setsecret'), so rotating the secret is a configuration change.
  """
  import params

  if not params.hive_ozone_s3_credential_enabled:
    return

  access_key = params.hive_s3a_access_key
  _run_ozone_as_admin(["s3", "getsecret", "-u", access_key], "Ensuring the Ozone S3 identity '{0}'".format(access_key))
  # the secret is read from a file owned by the Ozone user: the command line Ambari logs on failure never holds it
  fd, secret_file = tempfile.mkstemp(prefix="hive-ozone-s3-", dir=params.tmp_dir)
  try:
    with os.fdopen(fd, "w") as f:
      f.write(params.hive_s3a_secret_key)
    os.chown(secret_file, pwd.getpwnam(params.ozone_user).pw_uid, -1)
    secret_set = _run_ozone_as_admin(["s3", "setsecret", "-u", access_key],
                                     "Setting the configured secret of the Ozone S3 identity '{0}'".format(access_key),
                                     '-s "$(cat {0})"'.format(shlex.quote(secret_file)))
  finally:
    os.remove(secret_file)
  if secret_set:
    Logger.info("The Ozone S3 Gateway accepts the Hive Metastore credential '{0}'.".format(access_key))
  else:
    Logger.warning("Could not set the Ozone S3 secret of '{0}': Hive Metastore operations on S3 locations "
                   "stored in Ozone will be refused.".format(access_key))


def _run_ozone_as_admin(args, description, shell_args=None):
  import params

  cmd = [params.ozone_cmd, "--config", params.hadoop_conf_dir] + args
  if params.ozone_om_service_id:
    cmd += ["--om-service-id", params.ozone_om_service_id]
  Logger.info("{0}.".format(description))

  command = " ".join(shlex.quote(str(x)) for x in cmd)
  if shell_args:
    command = "{0} {1}".format(command, shell_args)
  if params.security_enabled:
    command = "{0} -kt {1} {2} && {3}".format(
      shlex.quote(params.kinit_path_local), shlex.quote(params.ozone_admin_keytab),
      shlex.quote(params.ozone_admin_principal), command)

  # quiet: the output carries the secret
  code, _, _ = get_user_call_output(command, user=params.ozone_user, quiet=True, is_checked_call=False)
  return code == 0
