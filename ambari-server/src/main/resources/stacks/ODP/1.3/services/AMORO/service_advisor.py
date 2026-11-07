#!/usr/bin/env ambari-python-wrap
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

import importlib.util
import os
import re
import traceback

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
STACKS_DIR = os.path.join(SCRIPT_DIR, '../../../../../stacks/')
PARENT_FILE = os.path.join(STACKS_DIR, 'service_advisor.py')

try:
  if "BASE_SERVICE_ADVISOR" in os.environ:
    PARENT_FILE = os.environ["BASE_SERVICE_ADVISOR"]
  with open(PARENT_FILE, 'rb') as fp:
    spec = importlib.util.spec_from_file_location('service_advisor', PARENT_FILE)
    service_advisor = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(service_advisor)
except Exception:
  traceback.print_exc()
  print("Failed to load parent")


def get_property(configurations, services, site, name):
  """Value of site/name, from the recommendations first, then from the request."""
  for source in (configurations, services.get("configurations", {})):
    value = source.get(site, {}).get("properties", {}).get(name)
    if value is not None:
      return value
  return None


def get_amoro_security_modes(configurations, services):
  """Cluster security that requires the dashboard behind Knox and TLS: Kerberos and/or the cluster OIDC."""
  modes = []
  if str(get_property(configurations, services, "cluster-env", "security_enabled") or "false").strip().lower() == "true":
    modes.append("Kerberos")
  if (str(get_property(configurations, services, "oidc-env", "oidc_admin_url") or "").strip()
      and str(get_property(configurations, services, "oidc-env", "oidc_realm") or "").strip()):
    modes.append("OIDC")
  return modes


def jdbc_urls(db_flavor, db_host, db_port, db_name):
  """(driver class, Amoro JDBC URL, DBA JDBC URL) of a DB flavor."""
  host_port = db_host if ":" in db_host else "{0}:{1}".format(db_host, db_port)
  if db_flavor == "MYSQL":
    return ("com.mysql.cj.jdbc.Driver",
            "jdbc:mysql://{0}/{1}?useUnicode=true&characterEncoding=UTF8&autoReconnect=true&useAffectedRows=true".format(
              host_port, db_name),
            "jdbc:mysql://{0}/".format(host_port))
  return ("org.postgresql.Driver",
          "jdbc:postgresql://{0}/{1}".format(host_port, db_name),
          "jdbc:postgresql://{0}/postgres".format(host_port))


def optimizers_on_yarn_available(service_names):
  """The Spark optimizer container needs YARN and the Spark3 client."""
  return "YARN" in service_names and "SPARK3" in service_names


# Memory of one optimizing thread: Amoro rewrites data files of ~128 MB with Parquet and ORC writers.
OPTIMIZER_THREAD_MB = 2048
# Memory overhead YARN adds to each Spark executor container (spark.executor.memoryOverhead floor).
SPARK_EXECUTOR_OVERHEAD_MB = 384


def round_down(value, step=256):
  return int(value) // step * step


def server_heap_mb(host_mb):
  """An eighth of the host memory (the server shares its master host), from 1 GB to 8 GB."""
  return max(1024, min(8192, round_down(host_mb // 8)))


def local_optimizer_resources(host_mb, cpus):
  """(memory per thread, threads) of the local optimizer: a quarter of the host memory, at most one
  thread per two cores and 8 threads."""
  budget = host_mb // 4
  memory = OPTIMIZER_THREAD_MB if budget >= OPTIMIZER_THREAD_MB else max(1024, round_down(budget))
  threads = max(1, min(budget // memory, max(1, cpus // 2), 8))
  return memory, threads


def yarn_optimizer_resources(max_allocation_mb, cluster_mb, cluster_vcores):
  """(memory per thread, threads) of the Spark optimizer: one executor (1 core) per thread fitting the
  largest YARN container, threads for a quarter of the YARN memory and cores, from 2 to 32."""
  memory = OPTIMIZER_THREAD_MB
  if 0 < max_allocation_mb < OPTIMIZER_THREAD_MB + SPARK_EXECUTOR_OVERHEAD_MB:
    memory = max(1024, round_down(max_allocation_mb - SPARK_EXECUTOR_OVERHEAD_MB))
  if cluster_mb <= 0:
    return memory, 2
  threads = cluster_mb // 4 // (memory + SPARK_EXECUTOR_OVERHEAD_MB)
  if cluster_vcores > 0:
    threads = min(threads, cluster_vcores // 4)
  return memory, max(2, min(32, threads))


def optimizer_queue_path(queue):
  """Capacity Scheduler path of the optimizer queue (amoro_optimizer_yarn_queue may omit root.)."""
  queue = str(queue or "default").strip() or "default"
  return queue if queue == "root" or queue.startswith("root.") else "root." + queue


def queue_acl_allows(acl, user):
  """Whether a YARN ACL ("users groups", "*" for everyone; YARN's default) names the user."""
  acl = "*" if acl is None else str(acl)
  if acl.strip() == "*":
    return True
  return user in acl.strip().partition(" ")[0].split(",")


def queue_acl_with_user(acl, user):
  """The ACL with the user added to its users (its groups kept)."""
  users, _, groups = str(acl).strip().partition(" ")
  names = [name for name in users.split(",") if name]
  return ",".join(names + [user]) + (" " + groups if groups else "")


def queue_submit_acls(configurations, services, path):
  """(property, ACL) of the submit ACLs of the queue and its parents, the queue first."""
  parts = path.split(".")
  acls = []
  for depth in range(len(parts), 0, -1):
    name = "yarn.scheduler.capacity.{0}.acl_submit_applications".format(".".join(parts[:depth]))
    acls.append((name, get_property(configurations, services, "capacity-scheduler", name)))
  return acls


def amoro_queue_acl_needed(configurations, services):
  """The submit ACL to give the optimizer queue so that the Amoro user can submit its Spark optimizers, or
  None: optimizers not on YARN, Ranger authorizing the queues, or the queue or a parent already allowing it."""
  if str(get_property(configurations, services, "amoro-env", "amoro_optimizer_container") or "local").strip() != "spark":
    return None
  if str(get_property(configurations, services, "ranger-yarn-plugin-properties",
                      "ranger-yarn-plugin-enabled") or "No").strip().lower() == "yes":
    return None
  user = str(get_property(configurations, services, "amoro-env", "amoro_user") or "amoro").strip()
  acls = queue_submit_acls(configurations, services,
                           optimizer_queue_path(get_property(configurations, services, "amoro-env",
                                                             "amoro_optimizer_yarn_queue")))
  if any(queue_acl_allows(acl, user) for _, acl in acls):
    return None
  name, acl = acls[0]
  return name, queue_acl_with_user(acl, user)


def amoro_env_changed(services, name):
  """Whether the operator just changed amoro-env/name (configuration change request)."""
  return any(change.get("type") == "amoro-env" and change.get("name") == name
             for change in services.get("changed-configurations", []))


class AmoroServiceAdvisor(service_advisor.ServiceAdvisor):

  def getServiceComponentLayoutValidations(self, services, hosts):
    items = self.getServiceComponentCardinalityValidations(services, hosts, "AMORO")
    modes = get_amoro_security_modes({}, services)
    if modes and not self.getHostsWithComponent("KNOX", "KNOX_GATEWAY", services, hosts):
      items.append({"type": "host-component", "level": "ERROR", "component-name": "AMORO_SERVER",
                    "message": "Amoro requires a Knox Gateway when {0} is enabled: users reach its dashboard "
                               "through Knox.".format(" and ".join(modes))})
    return items

  def getServiceConfigurationRecommendations(self, configurations, clusterData, services, hosts):
    recommender = AmoroRecommender()
    recommender.recommendAmoroConfigurations(configurations, clusterData, services, hosts)

  def getServiceConfigurationsValidationItems(self, configurations, recommendedDefaults, services, hosts):
    validator = AmoroValidator()
    return validator.validateListOfConfigUsingMethod(configurations, recommendedDefaults, services, hosts, validator.validators)


class AmoroRecommender(service_advisor.ServiceAdvisor):

  def recommendAmoroConfigurations(self, configurations, clusterData, services, hosts):
    self.recommendAmoroDatabaseConfigurations(configurations, services)
    self.recommendAmoroLdapConfigurations(configurations, services)
    self.recommendAmoroOptimizerContainer(configurations, services, hosts)
    self.recommendAmoroResources(configurations, services, hosts)
    self.recommendAmoroQueueAcl(configurations, services)

  def recommendAmoroDatabaseConfigurations(self, configurations, services):
    put_db_props = self.putProperty(configurations, "amoro-db-properties", services)

    def get(name, default):
      return str(get_property(configurations, services, "amoro-db-properties", name) or default).strip() or default

    db_flavor = get("DB_FLAVOR", "POSTGRES").upper()
    default_port = "3306" if db_flavor == "MYSQL" else "5432"
    db_port = get("db_port", default_port)
    # The port follows a change of flavor while it still holds the default of the other one.
    if db_port in ("3306", "5432") and db_port != default_port:
      db_port = default_port
      put_db_props("db_port", db_port)
    driver, url, root_url = jdbc_urls(db_flavor, get("db_host", "localhost"), db_port, get("db_name", "amoro"))
    put_db_props("amoro.jdbc.driver", driver)
    put_db_props("amoro.jdbc.url", url)
    put_db_props("amoro_privelege_user_jdbc_url", root_url)

  def recommendAmoroOptimizerContainer(self, configurations, services, hosts):
    """The optimizers run on YARN when the cluster has YARN and Spark3, through spark-submit --proxy-user
    of the Amoro user (without Kerberos), which the hadoop.proxyuser settings of core-site allow from
    the Amoro host. The container stays an operator setting: no property depends on it."""
    put_env = self.putProperty(configurations, "amoro-env", services)
    on_yarn = optimizers_on_yarn_available(self.get_services_list(services))
    container = str(get_property(configurations, services, "amoro-env", "amoro_optimizer_container") or "local").strip()
    if on_yarn and container == "local" and not amoro_env_changed(services, "amoro_optimizer_container"):
      container = "spark"
    elif not on_yarn:
      container = "local"
    put_env("amoro_optimizer_container", container)
    if container != "spark":
      return
    amoro_user = str(get_property(configurations, services, "amoro-env", "amoro_user") or "amoro").strip() or "amoro"
    amoro_hosts = sorted(host["Hosts"]["host_name"] for host in
                         (self.getHostsWithComponent("AMORO", "AMORO_SERVER", services, hosts) or []))
    put_core = self.putProperty(configurations, "core-site", services)
    put_core("hadoop.proxyuser.{0}.hosts".format(amoro_user), ",".join(amoro_hosts) or "*")
    put_core("hadoop.proxyuser.{0}.users".format(amoro_user), amoro_user)

  def recommendAmoroResources(self, configurations, services, hosts):
    """Heap of the server and resources of the optimizer, from the Amoro host and from YARN when the
    optimizers run on it. Operator settings: a value the operator just changed is kept, and no property
    depends on the host or YARN settings, so the recommendations only fill new installations."""
    put_env = self.putProperty(configurations, "amoro-env", services)

    def put(name, value):
      if not amoro_env_changed(services, name):
        put_env(name, str(value))

    amoro_hosts = self.getHostsWithComponent("AMORO", "AMORO_SERVER", services, hosts) or []
    if amoro_hosts:
      host_mb = int(amoro_hosts[0]["Hosts"].get("total_mem", 0)) // 1024
      cpus = int(amoro_hosts[0]["Hosts"].get("cpu_count", 0))
      if host_mb > 0:
        put("amoro_server_heapsize", server_heap_mb(host_mb))
    container = str(get_property(configurations, services, "amoro-env", "amoro_optimizer_container") or "local").strip()
    if container == "spark":
      def yarn(name):
        try:
          return int(float(str(get_property(configurations, services, "yarn-site", name) or 0)))
        except ValueError:
          return 0
      node_managers = len(self.getHostsWithComponent("YARN", "NODEMANAGER", services, hosts) or [])
      memory, parallelism = yarn_optimizer_resources(
        yarn("yarn.scheduler.maximum-allocation-mb"), node_managers * yarn("yarn.nodemanager.resource.memory-mb"),
        node_managers * yarn("yarn.nodemanager.resource.cpu-vcores"))
    elif amoro_hosts and host_mb > 0:
      memory, parallelism = local_optimizer_resources(host_mb, cpus)
    else:
      return
    put("amoro_optimizer_memory", memory)
    put("amoro_optimizer_parallelism", parallelism)

  def recommendAmoroQueueAcl(self, configurations, services):
    """Without the Ranger YARN plugin the Capacity Scheduler ACLs decide who submits to a queue, and enabling
    Kerberos narrows them to yarn and the smoke user (YARN kerberos.json): the Amoro user is added to the
    submit ACL of its optimizer queue. Also run on the Kerberos recommendations, which override the
    Kerberos descriptor values."""
    if "YARN" not in self.get_services_list(services):
      return
    needed = amoro_queue_acl_needed(configurations, services)
    if needed:
      self.putProperty(configurations, "capacity-scheduler", services)(needed[0], needed[1])

  def recommendAmoroLdapConfigurations(self, configurations, services):
    """The dashboard LDAP login defaults to the directory Ranger synchronizes users from."""
    put_site = self.putProperty(configurations, "amoro-site", services)
    if str(get_property(configurations, services, "amoro-site", "http-server.login-auth-ldap-url") or "").strip():
      return
    ldap_url = str(get_property(configurations, services, "ranger-ugsync-site", "ranger.usersync.ldap.url") or "").strip()
    search_base = str(get_property(configurations, services, "ranger-ugsync-site", "ranger.usersync.ldap.user.searchbase") or "").strip()
    name_attribute = str(get_property(configurations, services, "ranger-ugsync-site", "ranger.usersync.ldap.user.nameattribute") or "").strip()
    sync_source = str(get_property(configurations, services, "ranger-ugsync-site", "ranger.usersync.source.impl.class") or "")
    if not ldap_url or not search_base or "ldap" not in sync_source.lower():
      return
    put_site("http-server.login-auth-ldap-url", ldap_url)
    if not str(get_property(configurations, services, "amoro-site", "http-server.login-auth-ldap-user-pattern") or "").strip():
      # The first search base when Ranger synchronizes several (separated by ";").
      put_site("http-server.login-auth-ldap-user-pattern", "{0}={{0}},{1}".format(
        name_attribute or "uid", search_base.split(";")[0].strip()))


class AmoroValidator(service_advisor.ServiceAdvisor):

  def __init__(self, *args, **kwargs):
    self.as_super = super(AmoroValidator, self)
    self.as_super.__init__(*args, **kwargs)
    self.validators = [
      ("amoro-site", self.validateAmoroSiteProperties),
      ("amoro-env", self.validateAmoroEnvProperties),
      ("amoro-db-properties", self.validateAmoroDbProperties),
    ]

  def validateAmoroSiteProperties(self, properties, recommendedDefaults, configurations, services, hosts):
    items = []
    if not str(properties.get("admin-password", "")).strip():
      items.append({"config-name": "admin-password",
                    "item": self.getErrorItem("The Amoro administrator password is required.")})

    ldap_url = str(properties.get("http-server.login-auth-ldap-url", "")).strip()
    user_pattern = str(properties.get("http-server.login-auth-ldap-user-pattern", "")).strip()
    if ldap_url and "{0}" not in user_pattern:
      items.append({"config-name": "http-server.login-auth-ldap-user-pattern",
                    "item": self.getErrorItem("The LDAP user DN pattern must contain {0}, the login name.")})
    if not ldap_url and str(properties.get("http-server.authorization.enabled", "false")).lower() == "true":
      items.append({"config-name": "http-server.authorization.enabled",
                    "item": self.getWarnItem("Dashboard roles come from LDAP groups: without an LDAP URL, only the "
                                             "built-in administrator can log in and role-based access is off.")})

    modes = get_amoro_security_modes(configurations, services)
    if modes and str(properties.get("http-server.ssl.enabled", "false")).lower() != "true":
      items.append({"config-name": "http-server.ssl.enabled",
                    "item": self.getErrorItem("The Amoro dashboard must use TLS when {0} is enabled: logins and "
                                              "the REST credentials would travel in clear text.".format(" and ".join(modes)))})
    if str(properties.get("http-server.ssl.enabled", "false")).lower() == "true":
      for name in ("http-server.ssl.keystore-path", "http-server.ssl.keystore-password"):
        if not str(properties.get(name, "")).strip():
          items.append({"config-name": name,
                        "item": self.getErrorItem("Required when the dashboard TLS is enabled.")})
    return self.toConfigurationValidationProblems(items, "amoro-site")

  def validateAmoroEnvProperties(self, properties, recommendedDefaults, configurations, services, hosts):
    items = []
    # the truststore defaults to the cluster one; its password is only needed to read a PKCS12 truststore
    if str(get_property(configurations, services, "amoro-site", "http-server.ssl.enabled") or "false").lower() == "true":
      required = ["amoro_truststore_path"]
      if str(properties.get("amoro_truststore_type", "JKS")).strip().upper() == "PKCS12":
        required.append("amoro_truststore_password")
      for name in required:
        if not str(properties.get(name, "")).strip():
          items.append({"config-name": name,
                        "item": self.getErrorItem("Required when the dashboard TLS is enabled.")})
    site = get_property(configurations, services, "amoro-site", "http-server.authorization.enabled")
    ldap_url = str(get_property(configurations, services, "amoro-site", "http-server.login-auth-ldap-url") or "").strip()
    if ldap_url and str(site or "false").lower() == "true" and not str(properties.get("amoro_admin_ldap_group_dn", "")).strip():
      items.append({"config-name": "amoro_admin_ldap_group_dn",
                    "item": self.getWarnItem("No LDAP group is mapped to SERVICE_ADMIN: LDAP users only get the "
                                             "default role, administration needs the built-in administrator.")})
    installed = self.get_services_list(services)
    modes = get_amoro_security_modes(configurations, services)
    if "POLARIS" in installed and "OZONE" in installed and "Kerberos" in modes and "OIDC" not in modes:
      items.append({"config-name": "amoro_oidc_client_id",
                    "item": self.getWarnItem("Kerberos is enabled without OIDC: Amoro authenticates to Polaris with a "
                                             "secret Polaris issues, which Ambari does not manage. Enable OIDC so that "
                                             "Ambari manages the OIDC client of Amoro.")})
    if (str(properties.get("amoro_optimizer_container", "local")).strip() == "spark"
        and not optimizers_on_yarn_available(installed)):
      items.append({"config-name": "amoro_optimizer_container",
                    "item": self.getErrorItem("The Spark optimizer container needs YARN and Spark3 in the cluster.")})
    needed = amoro_queue_acl_needed(dict(configurations, **{"amoro-env": {"properties": properties}}), services)
    if needed and "YARN" in installed:
      items.append({"config-name": "amoro_optimizer_yarn_queue",
                    "item": self.getWarnItem("YARN refuses the Amoro optimizers: the Amoro user is not allowed to "
                                             "submit to this queue. Set {0} to {1}, or authorize the queue with "
                                             "Ranger.".format(needed[0], needed[1]))})
    if str(properties.get("amoro_optimizer_container", "local")).strip() == "spark":
      # one executor per thread: its memory plus the overhead YARN adds must fit the largest container
      try:
        executor_mb = int(str(properties.get("amoro_optimizer_memory", 0))) + SPARK_EXECUTOR_OVERHEAD_MB
        max_allocation_mb = int(float(str(get_property(configurations, services, "yarn-site",
                                                       "yarn.scheduler.maximum-allocation-mb") or 0)))
      except ValueError:
        executor_mb = max_allocation_mb = 0
      if 0 < max_allocation_mb < executor_mb:
        items.append({"config-name": "amoro_optimizer_memory",
                      "item": self.getErrorItem(
                        "An optimizer executor needs {0} MB (memory per thread + {1} MB overhead), more than the "
                        "largest YARN container ({2} MB, yarn.scheduler.maximum-allocation-mb): YARN refuses it. "
                        "Lower the memory per thread to {3} MB or less.".format(
                          executor_mb, SPARK_EXECUTOR_OVERHEAD_MB, max_allocation_mb,
                          max_allocation_mb - SPARK_EXECUTOR_OVERHEAD_MB))})
    for name in ("amoro_optimizer_parallelism", "amoro_optimizer_memory", "amoro_server_heapsize",
                 "amoro_optimizer_spark_driver_memory"):
      value = str(properties.get(name, 1))
      if name == "amoro_server_heapsize":
        # Ambari web saves the *_heapsize properties with an "m" (MB) suffix
        value = re.sub(r"[mM]$", "", value)
      try:
        if int(value) <= 0:
          raise ValueError()
      except ValueError:
        items.append({"config-name": name, "item": self.getErrorItem("Must be a positive integer.")})
    return self.toConfigurationValidationProblems(items, "amoro-env")

  def validateAmoroDbProperties(self, properties, recommendedDefaults, configurations, services, hosts):
    items = []
    db_flavor = str(properties.get("DB_FLAVOR", "POSTGRES")).upper()
    create_db = str(properties.get("create_db_dbuser", "true")).lower() == "true"
    if not str(properties.get("db_password", "")).strip():
      items.append({"config-name": "db_password",
                    "item": self.getErrorItem("The Amoro database password is required.")})
    if create_db and db_flavor != "POSTGRES":
      items.append({"config-name": "create_db_dbuser",
                    "item": self.getErrorItem("Ambari creates the Amoro database on PostgreSQL only: pre-create the "
                                              "{0} database and user, then set this to No.".format(db_flavor))})
    elif create_db:
      for name in ("db_root_user", "db_root_password", "amoro_privelege_user_jdbc_url"):
        if not str(properties.get(name, "")).strip():
          items.append({"config-name": name,
                        "item": self.getErrorItem("Required when Ambari sets up the database and database user.")})
    return self.toConfigurationValidationProblems(items, "amoro-db-properties")
