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

Ambari Agent
"""

import time

from resource_management.core.base import Fail
from resource_management.core.providers import Provider
from resource_management.core.resources.system import Execute
from resource_management.core.shell import quote_bash_args
from resource_management.core.logger import Logger


class OzoneResourceProvider(Provider):
  # Retryable failures commonly seen while OM leader election / startup is in progress.
  RETRYABLE_ERROR_SUBSTRINGS = (
    "connection refused",
    "connection timed out",
    "timed out",
    "temporarily unavailable",
    "notleaderexception",
    "service not ready",
    "servicenotreadyexception",
    "om not initialized",
    "leader is not ready",
    "retry",
  )

  def action_run(self):
    command = self.resource.command
    if isinstance(command, (list, tuple)):
      command = " ".join(quote_bash_args(x) for x in command)

    full_command = "{0} {1}".format(self._ozone_base_command(), command).strip()
    self._execute(full_command)

  def action_generate_s3_credentials(self):
    self.assert_parameter_is_set("access_id")

    cmd = [self.resource.ozone_cmd]
    if self.resource.conf_dir:
      cmd += ["--config", self.resource.conf_dir]
    if self.resource.secret_key:
      cmd += ["s3", "setsecret", "-u", self.resource.access_id, "-s", self.resource.secret_key]
    else:
      cmd += ["s3", "getsecret", "-u", self.resource.access_id]
    if self.resource.om_service_id:
      cmd += ["--om-service-id", self.resource.om_service_id]

    full_command = " ".join(quote_bash_args(x) for x in cmd)
    resource_tries = self._as_int(self.resource.tries, 1)
    resource_try_sleep = self._as_int(self.resource.try_sleep, 0)
    tries = resource_tries if resource_tries > 1 else 5
    try_sleep = resource_try_sleep if resource_try_sleep > 0 else 5
    self._execute_with_retry(full_command, tries=tries, try_sleep=try_sleep)

  def _ozone_base_command(self):
    cmd = [self.resource.ozone_cmd]
    if self.resource.conf_dir:
      cmd += ["--config", self.resource.conf_dir]
    return " ".join(quote_bash_args(x) for x in cmd)

  def _execute(self, command, tries=None, try_sleep=None):
    if self.resource.security_enabled:
      self.assert_parameter_is_set("kinit_path_local")
      self.assert_parameter_is_set("keytab")
      self.assert_parameter_is_set("principal_name")
      kinit_cmd = " ".join(
        quote_bash_args(x)
        for x in [self.resource.kinit_path_local, "-kt", self.resource.keytab, self.resource.principal_name]
      )
      command = "{0}; {1}".format(kinit_cmd, command)

    Execute(command,
            user=self.resource.user,
            tries=self.resource.tries if tries is None else tries,
            try_sleep=self.resource.try_sleep if try_sleep is None else try_sleep,
            logoutput=self.resource.logoutput,
            path=self.resource.bin_dir,
            environment=self.resource.environment
            )

  def _execute_with_retry(self, command, tries, try_sleep):
    last_error = None
    retries = self._as_int(tries, 1)
    sleep_seconds = self._as_int(try_sleep, 0)
    for attempt in range(1, retries + 1):
      try:
        self._execute(command, tries=1, try_sleep=0)
        return
      except Exception as err:
        last_error = err
        if attempt >= retries or not self._is_retryable_error(err):
          raise

        Logger.warning(
          "OzoneResource retry {0}/{1} after failure: {2}. Sleeping {3} seconds.".format(
            attempt, retries, err, sleep_seconds
          )
        )
        time.sleep(sleep_seconds)

    if last_error:
      raise last_error

  def _is_retryable_error(self, err):
    error_text = str(err).lower()
    return any(fragment in error_text for fragment in self.RETRYABLE_ERROR_SUBSTRINGS)

  def _as_int(self, value, default):
    try:
      return int(value)
    except Exception:
      return default

  def assert_parameter_is_set(self, parameter_name):
    if not getattr(self.resource, parameter_name):
      raise Fail("Resource parameter '{0}' is not set.".format(parameter_name))
    return True
