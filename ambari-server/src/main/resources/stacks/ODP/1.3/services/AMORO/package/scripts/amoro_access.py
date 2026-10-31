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

Data access of the Amoro user: its optimizers rewrite the files of the tables Amoro maintains and
delete the expired and orphan ones, in the Hive warehouse (HDFS or Ozone) and in the bucket of the
Polaris Ozone catalog. With the Ranger HDFS and Ozone plugins, Ambari grants it through Ranger policies.
"""
import urllib.parse

from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger

import amoro_catalogs

HDFS_ACCESSES = ["read", "write", "execute"]
OZONE_VOLUME_ACCESSES = ["read", "list"]
OZONE_BUCKET_ACCESSES = ["read", "write", "create", "list", "delete"]
# Volume of the buckets served by the Ozone S3 gateway.
S3_VOLUME = "s3v"


def setup_access(required=False):
  """Ensures the Ranger policies of the Amoro user. At start (required=False) a failure only logs a
  warning; the SETUP_ACCESS action fails with it."""
  import params

  policies = access_policies()
  if not policies:
    Logger.info("No Ranger-authorized HDFS or Ozone data for Amoro: no access to set up.")
    return
  errors = []
  for service, name, resources, accesses, recursive in policies:
    try:
      amoro_catalogs.ensure_ranger_policy(service, name, resources, params.amoro_user, accesses, recursive)
    except Fail as error:
      errors.append(str(error))
      Logger.warning("Ranger access of Amoro not set ({0}): {1}".format(name, error))
  if errors and required:
    raise Fail("Ranger access setup failed. " + " ".join(errors))


def access_policies():
  """(Ranger service, policy name, resources, accesses, recursive resources) the Amoro user needs."""
  import params

  hdfs_paths, buckets = [], []
  for path in params.hive_warehouse_dirs:
    location = storage_location(path, params.default_fs)
    if location[0] == "hdfs" and location[1] not in hdfs_paths:
      hdfs_paths.append(location[1])
    elif location[0] == "ozone" and location[1] not in buckets:
      buckets.append(location[1])
  if params.has_polaris_ozone_catalog:
    bucket = urllib.parse.urlparse(params.polaris_catalog_base_location).netloc
    if bucket and (S3_VOLUME, bucket) not in buckets:
      buckets.append((S3_VOLUME, bucket))

  policies = []
  if hdfs_paths and params.ranger_hdfs_enabled:
    policies.append((params.ranger_hdfs_service, "amoro-hdfs-warehouse", {"path": hdfs_paths}, HDFS_ACCESSES, ("path",)))
  if buckets and params.ranger_ozone_enabled:
    for volume in sorted(set(volume for volume, _ in buckets)):
      policies.append((params.ranger_ozone_service, "amoro-ozone-" + volume, {"volume": [volume]},
                       OZONE_VOLUME_ACCESSES, ()))
    for volume, bucket in buckets:
      policies.append((params.ranger_ozone_service, "amoro-ozone-{0}-{1}".format(volume, bucket),
                       {"volume": [volume], "bucket": [bucket], "key": ["*"]}, OZONE_BUCKET_ACCESSES, ()))
  return policies


def storage_location(path, default_fs):
  """("hdfs", path), ("ozone", (volume, bucket)) or (None, None) for a warehouse path: ofs://<om>/<volume>/<bucket>/...,
  o3fs://<bucket>.<volume>[.<om>]/..., hdfs://<nameservice>/... or a path without scheme on the default filesystem."""
  url = urllib.parse.urlparse(path)
  if not url.scheme:
    url = urllib.parse.urlparse(default_fs.rstrip("/") + "/" + path.lstrip("/"))
  if url.scheme in ("hdfs", "viewfs"):
    return "hdfs", url.path or "/"
  if url.scheme == "ofs":
    parts = [part for part in url.path.split("/") if part]
    # ofs://<om>/tmp is the per-user tmp mount: no table lives there
    if len(parts) >= 2 and parts[0] != "tmp":
      return "ozone", (parts[0], parts[1])
  if url.scheme == "o3fs":
    host = url.netloc.split(":")[0].split(".")
    if len(host) >= 2:
      return "ozone", (host[1], host[0])
  return None, None
