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
    if get_amoro_security_modes(configurations, services):
      self.putProperty(configurations, "amoro-site", services)("http-server.ssl.enabled", "true")

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
    site = get_property(configurations, services, "amoro-site", "http-server.authorization.enabled")
    ldap_url = str(get_property(configurations, services, "amoro-site", "http-server.login-auth-ldap-url") or "").strip()
    if ldap_url and str(site or "false").lower() == "true" and not str(properties.get("amoro_admin_ldap_group_dn", "")).strip():
      items.append({"config-name": "amoro_admin_ldap_group_dn",
                    "item": self.getWarnItem("No LDAP group is mapped to SERVICE_ADMIN: LDAP users only get the "
                                             "default role, administration needs the built-in administrator.")})
    for name in ("amoro_optimizer_parallelism", "amoro_optimizer_memory", "amoro_server_heapsize"):
      try:
        if int(properties.get(name, 1)) <= 0:
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
