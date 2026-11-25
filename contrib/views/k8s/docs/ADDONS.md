<!--
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
-->

# KDPS add-ons

An add-on adds a service to the KDPS catalog of a running Ambari server, without a new KDPS build: a folder with the
service's definition, installed by a package (rpm, deb) or by any other way of placing files. The catalog shows it after
the services built into KDPS; removing the folder removes it. Nothing needs a restart: KDPS reads add-ons again at every
use.

An add-on brings **definitions only**: what the wizard asks and how it turns the answers into Helm values, with the step
types and form field types KDPS already has. Its chart and images come from the Helm repository and the image registry
configured in KDPS, like those of the built-in services. An add-on that needs a new kind of step or of form field needs
a KDPS release.

## Where KDPS looks

| Setting | Value |
| --- | --- |
| Default | `/var/lib/ambari-server/resources/kdps-addons` |
| View property | `kdps.addons.dir` (Manage Ambari → Views → K8S-VIEW → the instance) |
| Environment | `KDPS_ADDONS_DIR` (the `ambari-kdps` container, where the folder is a mounted volume) |

The view property wins over the environment, which wins over the default.

## Layout

One folder per service, named after its key in upper case (letters, digits, `_`, `-`), with the layout of a service
built into KDPS (`src/main/resources/KDPS/services/<KEY>/`):

```
kdps-addons/
  REDIS_DEMO/
    service.json                    the definition: see service-json.md
    configurations/*.json           optional: stack configurations, as for a built-in service
    configurations/templates/*      optional: files the configurations name in valueSourceFile
    icon.svg | icon.png             optional: the catalog's icon (otherwise the generic one)
```

`service.json` takes one field built-in services do not use: `requiresKdps`, the oldest KDPS view version the add-on
works with (`"1.0.0.10"`). A view older than that does not offer it.

A complete example is in [addon-example/](addon-example/): a Redis service (`REDIS_DEMO`), an rpm spec and a script
that builds the deb.

## What KDPS refuses

A folder is left out of the catalog, with a warning in the Ambari server log, when:

- its name is not upper case, or no `service.json` is in it;
- a service built into KDPS has the same key: an add-on cannot replace one;
- the add-on directory, the folder or one of its files belongs to another account than root or the one running Ambari,
  can be written by another account than its owner, or is a symbolic link. A definition decides what KDPS creates in
  the cluster, in the KDC and in Ranger, so a file someone else could change is not read;
- its `requiresKdps` is newer than the running view;
- a configuration names a template outside the add-on's folder (`../`): that configuration alone is skipped.

`GET <view api>/services/addons` lists the add-on directory, the add-ons KDPS offers and those it left out with the
reason:

```
curl -u admin https://<ambari>/api/v1/views/K8S-VIEW/versions/1.0.0.10/instances/K8S_VIEW_INSTANCE/resources/api/services/addons
{"directory":"/var/lib/ambari-server/resources/kdps-addons","viewVersion":"1.0.0.10",
 "accepted":[{"key":"REDIS_DEMO","label":"Redis (add-on example)","chart":"redis","version":"23.0.4","requiresKdps":"1.0.0.10"}],
 "refused":[]}
```

## Packaging

Install the folder owned by root, folders `0755`, files `0644`, and nothing else: no scriptlet, no restart.

- **rpm**: [addon-example/packaging/kdps-addon-redis-demo.spec](addon-example/packaging/kdps-addon-redis-demo.spec).
  Several add-on packages may own `/var/lib/ambari-server/resources/kdps-addons`.
- **deb**: [addon-example/packaging/build-deb.sh](addon-example/packaging/build-deb.sh) (`dpkg-deb --root-owner-group`).
- **ambari-kdps container**: mount the folders (a volume, a ConfigMap per add-on) under the directory
  `KDPS_ADDONS_DIR` names, or build an image from `ambari-kdps` that adds them. The files must still belong to root or
  to the container's user and be writable by no one else.

Upgrading the package replaces the files; the next wizard or deploy reads the new definition. Releases already deployed
from the add-on keep running when it is removed and stay on the Releases page; KDPS can still uninstall them, but cannot
upgrade or reconfigure them until the add-on is back.

## What an add-on cannot do yet

- **Recommendations**: the advisor (`KDPS/advisors/stack_advisor.py`) has no rules for add-ons.
- **New context capabilities**: an add-on uses those KDPS knows (Hive, Ranger, Atlas, Kerberos, OIDC...).
- **New step types or form field types**: those are KDPS code.
