#!/bin/sh
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# Builds kdps-addon-redis-demo_1.0.0_all.deb from ../REDIS_DEMO. Run as any user: --root-owner-group makes root own the files.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(mktemp -d)
D=$ROOT/var/lib/ambari-server/resources/kdps-addons/REDIS_DEMO
install -d -m 0755 "$D" "$ROOT/DEBIAN"
install -m 0644 "$HERE/../REDIS_DEMO/service.json" "$HERE/../REDIS_DEMO/icon.svg" "$D/"
cat > "$ROOT/DEBIAN/control" <<CONTROL
Package: kdps-addon-redis-demo
Version: 1.0.0
Architecture: all
Maintainer: KDPS add-on maintainer <maintainer@example.com>
Description: KDPS add-on: Redis (example)
 Adds the REDIS_DEMO service to the KDPS catalog of the Ambari server it is installed on.
CONTROL
dpkg-deb --root-owner-group --build "$ROOT" "$HERE/kdps-addon-redis-demo_1.0.0_all.deb"
rm -rf "$ROOT"
