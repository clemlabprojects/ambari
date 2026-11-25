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
# rpmbuild -bb --define "_sourcedir $PWD/../REDIS_DEMO" kdps-addon-redis-demo.spec
Name:           kdps-addon-redis-demo
Version:        1.0.0
Release:        1
Summary:        KDPS add-on: Redis (example)
License:        Apache-2.0
BuildArch:      noarch
# KDPS reads the definition at every use: nothing to restart, no scriptlet.

%description
Adds the REDIS_DEMO service to the KDPS catalog of the Ambari server it is installed on.

%install
install -d -m 0755 %{buildroot}/var/lib/ambari-server/resources/kdps-addons/REDIS_DEMO
install -m 0644 %{_sourcedir}/service.json %{buildroot}/var/lib/ambari-server/resources/kdps-addons/REDIS_DEMO/service.json
install -m 0644 %{_sourcedir}/icon.svg     %{buildroot}/var/lib/ambari-server/resources/kdps-addons/REDIS_DEMO/icon.svg

%files
%defattr(-,root,root,-)
%dir /var/lib/ambari-server/resources/kdps-addons
/var/lib/ambari-server/resources/kdps-addons/REDIS_DEMO
