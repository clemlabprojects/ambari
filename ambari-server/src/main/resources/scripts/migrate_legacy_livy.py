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

ODP (clemlab): migrate the deprecated legacy Livy to the per-Spark Livy.

ODP 1.0 defined one Livy for Spark2 and Spark3: component LIVY2_SERVER and the unprefixed livy2-* config types.
ODP 1.2 introduced SPARK2_LIVY2_SERVER / SPARK3_LIVY2_SERVER with spark2-livy2-* / spark3-livy2-*; the legacy ones
are deprecated, and the LegacyLivyCheck pre-upgrade check blocks upgrades while LIVY2_SERVER is installed.

  --check (default)  report what is installed and what --apply would do; changes nothing.
                     exit code 0: nothing to migrate, 2: legacy Livy found.
  --apply            for each Spark service running LIVY2_SERVER: stop it, copy the livy2-* values the operator changed
                     (from the stack defaults), and always livy.server.port, into the matching sparkN-livy2-* types, add SPARKn_LIVY2_SERVER on the
                     same hosts, install it (and start it if the legacy Livy was running), then delete LIVY2_SERVER.
                     Safe to run again: completed steps are skipped.

Run it on the Ambari Server host as an Ambari administrator, e.g.:
  python3 migrate_legacy_livy.py --url https://ambari.example.com:8443 --user admin --insecure --check
The password is read from the AMBARI_PASSWORD environment variable or prompted for. Property values are never
printed (they may contain passwords), only property names.
"""
import argparse
import base64
import getpass
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

LEGACY_COMPONENT = "LIVY2_SERVER"
LEGACY_TYPES = ["livy2-conf", "livy2-env", "livy2-client-conf", "livy2-log4j-properties", "livy2-spark-blacklist"]
SPARK_SERVICES = ["SPARK2", "SPARK3"]
TERMINAL = ("COMPLETED", "FAILED", "ABORTED", "TIMEDOUT", "SKIPPED_FAILED")


class Ambari(object):
  def __init__(self, url, user, password, insecure):
    self.base = url.rstrip("/") + "/api/v1"
    self.auth = "Basic " + base64.b64encode(("%s:%s" % (user, password)).encode()).decode()
    self.ctx = ssl._create_unverified_context() if insecure else None

  def call(self, method, path, body=None, ok404=False):
    req = urllib.request.Request(self.base + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": self.auth, "X-Requested-By": "migrate_legacy_livy",
                                          "Content-Type": "application/json"})
    try:
      with urllib.request.urlopen(req, context=self.ctx) as resp:
        data = resp.read()
        return json.loads(data) if data.strip() else {}
    except urllib.error.HTTPError as e:
      if ok404 and e.code == 404:
        return None
      raise SystemExit("ERROR: %s %s -> HTTP %d: %s" % (method, path, e.code, e.read().decode(errors="replace")[:500]))

  def get(self, path, ok404=False):
    return self.call("GET", path, ok404=ok404)

  def wait(self, response, what):
    """Waits for the asynchronous request an Ambari call started (if any)."""
    href = (response or {}).get("href")
    if not href or "/requests/" not in href:
      return
    path = href.split("/api/v1", 1)[1]
    while True:
      r = self.get(path + "?fields=Requests/request_status,Requests/progress_percent")["Requests"]
      if r["request_status"] in TERMINAL:
        if r["request_status"] != "COMPLETED":
          raise SystemExit("ERROR: %s ended %s, see %s in Ambari" % (what, r["request_status"], path))
        return
      time.sleep(5)


def q(value):
  return urllib.parse.quote(value, safe="")


def stack_defaults(amb, stack_name, versions, services, config_type):
  """Default properties of a config type, from the first stack version / service defining it."""
  for version in versions:
    for service in services:
      r = amb.get("/stacks/%s/versions/%s/services/%s/configurations?StackConfigurations/type=%s.xml"
                  "&fields=StackConfigurations/property_name,StackConfigurations/property_value"
                  % (stack_name, version, service, config_type), ok404=True)
      items = (r or {}).get("items", [])
      if items:
        return dict((i["StackConfigurations"]["property_name"], i["StackConfigurations"].get("property_value"))
                    for i in items)
  return None


def desired_config(amb, cluster, tags, config_type):
  tag = tags.get(config_type, {}).get("tag")
  if not tag:
    return None
  items = amb.get("/clusters/%s/configurations?type=%s&tag=%s" % (q(cluster), q(config_type), q(tag)))["items"]
  return items[0] if items else None


# Always carried over, even at their default: clients (Zeppelin, Knox, applications) reach Livy through them, and the
# per-Spark defaults differ (legacy Livy listens on 8999, Spark3's Livy defaults to 8998).
ALWAYS_COPIED = {"livy2-conf": ["livy.server.port"]}


def changed_values(legacy, defaults, always=()):
  """Legacy properties whose value differs from the stack default (or that have no default), plus the always copied."""
  defaults = defaults or {}
  return dict((k, v) for k, v in legacy.items()
              if v is not None and (k in always or k not in defaults or defaults[k] != v))


def plan(amb, cluster):
  """Returns (stack_name, stack_version, security_type, [entry per Spark service running legacy Livy])."""
  info = amb.get("/clusters/%s?fields=Clusters/version,Clusters/security_type,Clusters/desired_configs"
                 % q(cluster))["Clusters"]
  stack_name, stack_version = info["version"].split("-", 1)
  tags = info.get("desired_configs", {})
  versions = [stack_version] + [v for v in ("1.2", "1.0") if v != stack_version]
  entries = []
  for service in SPARK_SERVICES:
    r = amb.get("/clusters/%s/services/%s/components/%s?fields=host_components/HostRoles/host_name,"
                "host_components/HostRoles/state" % (q(cluster), service, LEGACY_COMPONENT), ok404=True)
    if not r or not r.get("host_components"):
      continue
    hosts = dict((h["HostRoles"]["host_name"], h["HostRoles"].get("state")) for h in r["host_components"])
    new_component = service + "_LIVY2_SERVER"
    r = amb.get("/clusters/%s/services/%s/components/%s?fields=host_components/HostRoles/host_name"
                % (q(cluster), service, new_component), ok404=True)
    new_hosts = set(h["HostRoles"]["host_name"] for h in (r or {}).get("host_components", []))
    copies = []
    for legacy_type in LEGACY_TYPES:
      legacy = desired_config(amb, cluster, tags, legacy_type)
      if legacy is None:
        continue
      defaults = stack_defaults(amb, stack_name, versions, SPARK_SERVICES, legacy_type)
      changed = changed_values(legacy.get("properties", {}), defaults, ALWAYS_COPIED.get(legacy_type, ()))
      copies.append({"from": legacy_type, "to": service.lower() + "-" + legacy_type, "changed": changed,
                     "defaults_found": defaults is not None})
    entries.append({"service": service, "hosts": hosts, "new_component": new_component, "new_hosts": new_hosts,
                    "copies": copies})
  return stack_name, stack_version, info.get("security_type"), tags, entries


def report(cluster, stack, security_type, entries):
  print("Cluster %s (%s-%s, security %s)" % (cluster, stack[0], stack[1], security_type))
  if not entries:
    print("No legacy Livy (%s) installed: nothing to migrate." % LEGACY_COMPONENT)
    return
  for e in entries:
    print("\n%s: legacy %s on %s" % (e["service"], LEGACY_COMPONENT,
                                     ", ".join("%s (%s)" % (h, s) for h, s in sorted(e["hosts"].items()))))
    missing = sorted(set(e["hosts"]) - e["new_hosts"])
    print("  %s to add on: %s" % (e["new_component"], ", ".join(missing) if missing else "none (already there)"))
    for c in e["copies"]:
      names = ", ".join(sorted(c["changed"])) or "none"
      note = "" if c["defaults_found"] else " (no stack defaults found: every value counts as changed)"
      print("  %s -> %s: changed properties to copy: %s%s" % (c["from"], c["to"], names, note))
  if security_type == "KERBEROS":
    print("\nKerberos is enabled: Ambari must be able to create the keytabs of the new Livy component "
          "(KDC admin credentials stored in Ambari), otherwise regenerate keytabs for missing hosts/components after.")
  print("\nRun again with --apply to migrate. The pre-upgrade check LegacyLivyCheck fails until this is done.")


def apply(amb, cluster, stack, tags, entries):
  for e in entries:
    service, new_component = e["service"], e["new_component"]
    hosts = sorted(e["hosts"])
    was_running = any(s == "STARTED" for s in e["hosts"].values())
    legacy_pred = "HostRoles/service_name=%s&HostRoles/component_name=%s" % (service, LEGACY_COMPONENT)

    print("%s: stopping legacy %s on %s" % (service, LEGACY_COMPONENT, ", ".join(hosts)))
    amb.wait(amb.call("PUT", "/clusters/%s/host_components?%s" % (q(cluster), legacy_pred),
                      {"RequestInfo": {"context": "Stop legacy Livy (%s)" % service},
                       "Body": {"HostRoles": {"state": "INSTALLED"}}}), "stopping the legacy Livy")

    desired = []
    for c in e["copies"]:
      if not c["changed"]:
        continue
      current = desired_config(amb, cluster, tags, c["to"])
      if current is not None:
        props, attrs = dict(current.get("properties", {})), current.get("properties_attributes", {})
      else:
        props = dict((k, v) for k, v in (stack_defaults(amb, stack[0], [stack[1]], [service], c["to"]) or {}).items()
                     if v is not None)
        attrs = {}
      if all(props.get(k) == v for k, v in c["changed"].items()):
        print("%s: %s already has the changed values of %s" % (service, c["to"], c["from"]))
        continue
      props.update(c["changed"])
      desired.append({"type": c["to"], "tag": "version%d" % int(time.time() * 1000), "properties": props,
                      "properties_attributes": attrs,
                      "service_config_version_note": "Migrated from legacy %s" % c["from"]})
      print("%s: copying %s into %s (%s)" % (service, c["from"], c["to"], ", ".join(sorted(c["changed"]))))
    if desired:
      amb.call("PUT", "/clusters/%s" % q(cluster), [{"Clusters": {"desired_config": desired}}])

    missing = sorted(set(hosts) - e["new_hosts"])
    if missing:
      if amb.get("/clusters/%s/services/%s/components/%s" % (q(cluster), service, new_component), ok404=True) is None:
        amb.call("POST", "/clusters/%s/services/%s/components/%s" % (q(cluster), service, new_component))
      print("%s: adding %s on %s" % (service, new_component, ", ".join(missing)))
      amb.call("POST", "/clusters/%s/hosts?Hosts/host_name.in(%s)" % (q(cluster), ",".join(missing)),
               {"host_components": [{"HostRoles": {"component_name": new_component}}]})
    new_pred = "HostRoles/service_name=%s&HostRoles/component_name=%s" % (service, new_component)
    print("%s: installing %s" % (service, new_component))
    amb.wait(amb.call("PUT", "/clusters/%s/host_components?%s&HostRoles/state.in(INIT,INSTALL_FAILED)"
                      % (q(cluster), new_pred),
                      {"RequestInfo": {"context": "Install %s" % new_component},
                       "Body": {"HostRoles": {"state": "INSTALLED"}}}), "installing " + new_component)
    if was_running:
      print("%s: starting %s" % (service, new_component))
      amb.wait(amb.call("PUT", "/clusters/%s/host_components?%s" % (q(cluster), new_pred),
                        {"RequestInfo": {"context": "Start %s" % new_component},
                         "Body": {"HostRoles": {"state": "STARTED"}}}), "starting " + new_component)

    print("%s: deleting legacy %s" % (service, LEGACY_COMPONENT))
    amb.call("DELETE", "/clusters/%s/host_components?%s" % (q(cluster), legacy_pred))
    amb.call("DELETE", "/clusters/%s/services/%s/components/%s" % (q(cluster), service, LEGACY_COMPONENT),
             ok404=True)
  print("Done. The legacy livy2-* config types are left in place, unused.")


def main():
  ap = argparse.ArgumentParser(description=__doc__[__doc__.index("ODP (clemlab)"):],
                               formatter_class=argparse.RawTextHelpFormatter)
  ap.add_argument("--url", default="http://localhost:8080", help="Ambari Server URL (default %(default)s)")
  ap.add_argument("--user", default="admin")
  ap.add_argument("--cluster", help="cluster name (default: the only cluster)")
  ap.add_argument("--insecure", action="store_true", help="do not verify the Ambari TLS certificate")
  mode = ap.add_mutually_exclusive_group()
  mode.add_argument("--check", action="store_true", help="report only (default)")
  mode.add_argument("--apply", action="store_true", help="migrate")
  args = ap.parse_args()
  password = os.environ.get("AMBARI_PASSWORD") or getpass.getpass("Ambari password for %s: " % args.user)
  amb = Ambari(args.url, args.user, password, args.insecure)
  cluster = args.cluster
  if not cluster:
    clusters = [c["Clusters"]["cluster_name"] for c in amb.get("/clusters")["items"]]
    if len(clusters) != 1:
      raise SystemExit("ERROR: use --cluster, clusters found: %s" % ", ".join(clusters))
    cluster = clusters[0]
  stack_name, stack_version, security_type, tags, entries = plan(amb, cluster)
  report(cluster, (stack_name, stack_version), security_type, entries)
  if args.apply and entries:
    print("")
    apply(amb, cluster, (stack_name, stack_version), tags, entries)
    return 0
  return 2 if entries else 0


if __name__ == "__main__":
  sys.exit(main())
