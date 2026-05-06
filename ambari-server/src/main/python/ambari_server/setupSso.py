#!/usr/bin/env python3

'''
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
'''
import re
import urllib.request, urllib.error, urllib.parse

import sys
from ambari_commons.exceptions import FatalException, NonFatalException
from ambari_commons.logging_utils import get_silent, print_info_msg

from ambari_server.serverConfiguration import get_ambari_properties
from ambari_server.serverUtils import is_server_runing, get_ambari_admin_username_password_pair, \
  get_cluster_name, perform_changes_via_rest_api, get_json_via_rest_api, \
  get_value_from_dictionary, get_boolean_from_dictionary
from ambari_server.setupSecurity import REGEX_TRUE_FALSE
from ambari_server.userInput import get_validated_string_input, get_YN_input, get_multi_line_input

AMBARI_SSO_AUTH_ENABLED = "ambari.sso.authentication.enabled"
SSO_MANAGE_SERVICES = "ambari.sso.manage_services"
SSO_ENABLED_SERVICES = "ambari.sso.enabled_services"
SSO_PROVIDER_URL = "ambari.sso.provider.url"
SSO_CERTIFICATE = "ambari.sso.provider.certificate"
SSO_PROVIDER_ORIGINAL_URL_QUERY_PARAM = "ambari.sso.provider.originalUrlParamName"
JWT_AUDIENCES = "ambari.sso.jwt.audiences"
JWT_COOKIE_NAME = "ambari.sso.jwt.cookieName"

# OIDC browser-flow properties (set by setup-sso-oidc, orthogonal to Knox SSO)
SSO_OIDC_CLIENT_ID = "ambari.sso.oidc.clientId"
SSO_OIDC_CLIENT_SECRET = "ambari.sso.oidc.clientSecret"
SSO_OIDC_CALLBACK_URL = "ambari.sso.oidc.callbackUrl"
SSO_JWT_USERNAME_CLAIM = "ambari.sso.jwt.usernameClaim"
# Dedicated OIDC provider and service-dispatch properties (allow Knox SSO and OIDC SSO to coexist)
SSO_OIDC_PROVIDER_URL = "ambari.sso.oidc.providerUrl"
SSO_OIDC_PROVIDER_CERTIFICATE = "ambari.sso.oidc.providerCertificate"
SSO_OIDC_AUTHENTICATION_ENABLED = "ambari.sso.oidc.authentication.enabled"
SSO_OIDC_MANAGE_SERVICES = "ambari.sso.oidc.manage_services"
SSO_OIDC_ENABLED_SERVICES = "ambari.sso.oidc.enabled_services"

SSO_PROVIDER_ORIGINAL_URL_QUERY_PARAM_DEFAULT = "originalUrl"
SSO_PROVIDER_URL_DEFAULT = "https://knox.example.com:8443/gateway/knoxsso/api/v1/websso"
JWT_COOKIE_NAME_DEFAULT = "hadoop-jwt"
JWT_AUDIENCES_DEFAULT = ""

CERTIFICATE_HEADER = "-----BEGIN CERTIFICATE-----"
CERTIFICATE_FOOTER = "-----END CERTIFICATE-----"

REGEX_ANYTHING = ".*"
REGEX_URL = "http[s]?://(?:[a-zA-Z]|[0-9]|[$-_@.&+]|[!*\(\),]|(?:%[0-9a-fA-F][0-9a-fA-F]))+\S*$"

WILDCARD_FOR_ALL_SERVICES = "*"
SERVICE_NAME_AMBARI = 'AMBARI'

FETCH_SERVICES_FOR_SSO_ENTRYPOINT = "clusters/%s/services?ServiceInfo/sso_integration_supported=true&fields=ServiceInfo/*"
SSO_CONFIG_API_ENTRYPOINT = 'services/AMBARI/components/AMBARI_SERVER/configurations/sso-configuration'


def validate_options(options):
  errors = []
  if options.sso_enabled and not re.match(REGEX_TRUE_FALSE, options.sso_enabled):
    errors.append("--sso-enabled should be to either 'true' or 'false'")

  if options.sso_enabled == 'true':
    if not options.sso_provider_url:
      errors.append("Missing option: --sso-provider-url")
    if not options.sso_public_cert_file:
      errors.append("Missing option: --sso-public-cert-file")
    if options.sso_provider_url and not re.match(REGEX_URL, options.sso_provider_url):
      errors.append("Invalid --sso-provider-url")
    if options.sso_enabled_ambari and not re.match(REGEX_TRUE_FALSE, options.sso_enabled_ambari):
      errors.append("--sso-enabled-ambari should be to either 'true' or 'false'")
    if options.sso_manage_services and not re.match(REGEX_TRUE_FALSE, options.sso_manage_services):
      errors.append("--sso-manage-services should be to either 'true' or 'false'")

  if len(errors) > 0:
    error_msg = "The following errors occurred while processing your request: {0}"
    raise FatalException(1, error_msg.format(str(errors)))


def populate_sso_provider_url(options, properties):
  if not options.sso_provider_url:
      provider_url = get_value_from_dictionary(properties, SSO_PROVIDER_URL, SSO_PROVIDER_URL_DEFAULT)
      provider_url = get_validated_string_input("Provider URL ({0}): ".format(provider_url), provider_url, REGEX_URL,
                                                "Invalid provider URL", False)
  else:
    provider_url = options.sso_provider_url

  properties[SSO_PROVIDER_URL] = provider_url

def populate_sso_public_cert(options, properties):
  if not options.sso_public_cert_file:
    cert = get_value_from_dictionary(properties, SSO_CERTIFICATE)
    get_cert = True if not cert else get_YN_input("The SSO provider's public certificate has already set. Do you want to change it [y/n] (n)? ", False)

    if get_cert:
      cert_string = get_multi_line_input("Public Certificate PEM")
      properties[SSO_CERTIFICATE] = ensure_complete_cert(cert_string) if cert_string else ""
  else:
    cert_path = options.sso_public_cert_file
    with open(cert_path) as cert_file:
      cert_string = cert_file.read()
    properties[SSO_CERTIFICATE] = ensure_complete_cert(cert_string) if cert_string else ""


def populate_jwt_cookie_name(options, properties):
  if not options.sso_jwt_cookie_name and (not options.sso_provider_url or not options.sso_public_cert_file):
    cookie_name = get_value_from_dictionary(properties, JWT_COOKIE_NAME, JWT_COOKIE_NAME_DEFAULT)
    cookie_name = get_validated_string_input("JWT Cookie name ({0}): ".format(cookie_name), cookie_name, REGEX_ANYTHING,
                                         "Invalid cookie name", False)
  else:
    cookie_name = options.sso_jwt_cookie_name if options.sso_jwt_cookie_name else JWT_COOKIE_NAME_DEFAULT

  properties[JWT_COOKIE_NAME] = cookie_name


def populate_jwt_audiences(options, properties):
  if options.sso_jwt_audience_list is None and (not options.sso_provider_url or not options.sso_public_cert_file):
    audiences = get_value_from_dictionary(properties, JWT_AUDIENCES, JWT_AUDIENCES_DEFAULT)
    audiences = get_validated_string_input("JWT audiences list (comma-separated), empty for any ({0}): ".format(audiences), audiences,
                                        REGEX_ANYTHING, "Invalid value", False)
  else:
    audiences = options.sso_jwt_audience_list if options.sso_jwt_audience_list else JWT_AUDIENCES_DEFAULT

  properties[JWT_AUDIENCES] = audiences

def populate_ambari_requires_sso(options, properties):
  if options.sso_enabled_ambari is None:
    enabled = get_boolean_from_dictionary(properties, AMBARI_SSO_AUTH_ENABLED, False)
    enabled = get_YN_input("Use SSO for Ambari [y/n] ({0})? ".format('y' if enabled else 'n'), enabled)
  else:
    enabled = 'true' == options.sso_enabled_ambari

  properties[AMBARI_SSO_AUTH_ENABLED] = 'true' if enabled else 'false'

def eligible(service_info):
  return service_info['sso_integration_supported'] \
         and (not service_info['sso_integration_requires_kerberos'] or service_info['kerberos_enabled'])

def get_eligible_services(properties, admin_login, admin_password, cluster_name):
  print_info_msg("Fetching SSO enabled services")

  safe_cluster_name = urllib.parse.quote(cluster_name)

  response_code, json_data = get_json_via_rest_api(properties, admin_login, admin_password,
                                                   FETCH_SERVICES_FOR_SSO_ENTRYPOINT % safe_cluster_name)

  services = []

  if json_data and 'items' in json_data:
    services = [item['ServiceInfo']['service_name'] for item in json_data['items'] if eligible(item['ServiceInfo'])]

    if len(services) > 0:
      print_info_msg('Found SSO enabled services: %s' % ', '.join(services))
    else:
      print_info_msg('No SSO enabled services were found')

  return services


def populate_service_management(options, properties, ambari_properties, admin_login, admin_password):
  if not options.sso_enabled_services:
    if not options.sso_manage_services:
      manage_services = get_boolean_from_dictionary(properties, SSO_MANAGE_SERVICES, False)
      manage_services = get_YN_input("Manage SSO configurations for eligible services [y/n] ({0})? ".format('y' if manage_services else 'n'), manage_services)
    else:
      manage_services = 'true' == options.sso_manage_services

      if not options.sso_provider_url:
        stored_manage_services = get_boolean_from_dictionary(properties, SSO_MANAGE_SERVICES, False)
        print("Manage SSO configurations for eligible services [y/n] ({0})? {1}"
              .format('y' if stored_manage_services else 'n', 'y' if manage_services else 'n'))

    if manage_services:
      enabled_services = get_value_from_dictionary(properties, SSO_ENABLED_SERVICES, "").upper().split(',')

      all = "*" in enabled_services
      configure_for_all_services = get_YN_input(" Use SSO for all services [y/n] ({0})? ".format('y' if all else 'n'), all)
      if configure_for_all_services:
        services = WILDCARD_FOR_ALL_SERVICES
      else:
        cluster_name = get_cluster_name(ambari_properties, admin_login, admin_password)

        if cluster_name:
          eligible_services = get_eligible_services(ambari_properties, admin_login, admin_password, cluster_name)

          if eligible_services and len(eligible_services) > 0:
            service_list = []

            for service in eligible_services:
              enabled = service.upper() in enabled_services
              question = "   Use SSO for {0} [y/n] ({1})? ".format(service, 'y' if enabled else 'n')
              if get_YN_input(question, enabled):
                service_list.append(service)

            services = ','.join(service_list)
          else:
            print("   There are no eligible services installed.")
            services = ""
        else:
          services = ""
    else:
      services = ""
  else:
    if options.sso_manage_services:
      manage_services = 'true' == options.sso_manage_services
    else:
      manage_services = True

    services = options.sso_enabled_services.upper() if options.sso_enabled_services else ""

  properties[SSO_MANAGE_SERVICES] = 'true' if manage_services else "false"
  properties[SSO_ENABLED_SERVICES] = services


def get_sso_properties(properties, admin_login, admin_password):
  print_info_msg("Fetching SSO configuration from DB")

  try:
    response_code, json_data = get_json_via_rest_api(properties, admin_login, admin_password, SSO_CONFIG_API_ENTRYPOINT)
  except urllib.error.HTTPError as http_error:
    if http_error.code == 404:
      # This means that there is no SSO configuration in the database yet -> we can not fetch the
      # property (but this is NOT an error)
      json_data = None
    else:
      raise http_error

  if json_data and 'Configuration' in json_data and 'properties' in json_data['Configuration']:
    return json_data['Configuration']['properties']
  else:
    return {}


def remove_sso_conf(ambari_properties, admin_login, admin_password):
  perform_changes_via_rest_api(ambari_properties, admin_login, admin_password, SSO_CONFIG_API_ENTRYPOINT, 'DELETE')


def update_sso_conf(ambari_properties, sso_configuration_properties, admin_login, admin_password):
  request_data = {
    "Configuration": {
      "category": "sso-configuration",
      "properties": {
      }
    }
  }
  request_data['Configuration']['properties'] = sso_configuration_properties
  perform_changes_via_rest_api(ambari_properties, admin_login, admin_password, SSO_CONFIG_API_ENTRYPOINT, 'PUT', request_data)


def setup_sso(options):
  print_info_msg("Setup SSO.")

  server_status, pid = is_server_runing()
  if not server_status:
    err = 'Ambari Server is not running.'
    raise FatalException(1, err)

  if not get_silent():
    validate_options(options)

    ambari_properties = get_ambari_properties()

    admin_login, admin_password = get_ambari_admin_username_password_pair(options)
    properties = get_sso_properties(ambari_properties, admin_login, admin_password)

    if not options.sso_enabled:
      ambari_auth_enabled = get_value_from_dictionary(properties, AMBARI_SSO_AUTH_ENABLED)
      manage_services = get_value_from_dictionary(properties, SSO_MANAGE_SERVICES)

      if ambari_auth_enabled or manage_services:
        if (ambari_auth_enabled and 'true' == ambari_auth_enabled) or \
          (manage_services and 'true' == manage_services):
          sso_status = "enabled"
        else:
          sso_status = "disabled"
      else:
        sso_status = "not configured"
      sys.stdout.write("\nSSO is currently %s\n" % sso_status)

      if sso_status == "enabled":
        enable_sso = not get_YN_input("Do you want to disable SSO authentication [y/n] (n)? ", False)
      elif get_YN_input("Do you want to configure SSO authentication [y/n] (y)? ", True):
        enable_sso = True
      else:
        return False
    else:
      enable_sso = options.sso_enabled == 'true'

    if enable_sso:
      populate_sso_provider_url(options, properties)
      populate_sso_public_cert(options, properties)
      populate_ambari_requires_sso(options, properties)
      populate_service_management(options, properties, ambari_properties, admin_login, admin_password)
      populate_jwt_cookie_name(options, properties)
      populate_jwt_audiences(options, properties)

      update_sso_conf(ambari_properties, properties, admin_login, admin_password)
    else:
      remove_sso_conf(ambari_properties, admin_login, admin_password)

  else:
    warning = "setup-sso is not enabled in silent mode."
    raise NonFatalException(warning)
  pass


def populate_oidc_client_id(options, properties):
  if not options.sso_oidc_client_id:
    client_id = get_value_from_dictionary(properties, SSO_OIDC_CLIENT_ID, "ambari")
    client_id = get_validated_string_input(
      "OIDC client ID ({0}): ".format(client_id), client_id, REGEX_ANYTHING,
      "Invalid client ID", False)
  else:
    client_id = options.sso_oidc_client_id
  properties[SSO_OIDC_CLIENT_ID] = client_id


def populate_oidc_client_secret(options, properties):
  if not options.sso_oidc_client_secret:
    existing = get_value_from_dictionary(properties, SSO_OIDC_CLIENT_SECRET, "")
    if existing:
      if not get_YN_input("An OIDC client secret is already set. Do you want to change it [y/n] (n)? ", False):
        return
    secret = get_validated_string_input(
      "OIDC client secret: ", "", REGEX_ANYTHING, "Invalid client secret", True)
  else:
    secret = options.sso_oidc_client_secret
  properties[SSO_OIDC_CLIENT_SECRET] = secret


def populate_oidc_callback_url(options, properties):
  if not options.sso_oidc_callback_url:
    callback_url = get_value_from_dictionary(properties, SSO_OIDC_CALLBACK_URL, "")
    callback_url = get_validated_string_input(
      "OIDC callback URL (e.g. https://ambari-host:8442/oidc/callback) ({0}): ".format(callback_url or ""),
      callback_url, REGEX_URL, "Invalid callback URL", False)
  else:
    callback_url = options.sso_oidc_callback_url
  properties[SSO_OIDC_CALLBACK_URL] = callback_url


def populate_jwt_username_claim(options, properties):
  if options.sso_jwt_username_claim is None:
    claim = get_value_from_dictionary(properties, SSO_JWT_USERNAME_CLAIM, "")
    claim = get_validated_string_input(
      "JWT username claim (empty = Keycloak default: preferred_username then sub) ({0}): ".format(claim or "empty"),
      claim, REGEX_ANYTHING, "Invalid claim name", False)
  else:
    claim = options.sso_jwt_username_claim
  properties[SSO_JWT_USERNAME_CLAIM] = claim


def populate_oidc_provider_url(options, properties):
  """Set ambari.sso.oidc.providerUrl — the Keycloak auth endpoint for the OIDC flow.

  When set this takes precedence over ambari.sso.provider.url so that Knox SSO and OIDC
  SSO can use different providers simultaneously.  Leaving it blank retains backward
  compatibility (the Knox provider.url is used as fallback).
  """
  if not options.sso_oidc_provider_url:
    current = get_value_from_dictionary(properties, SSO_OIDC_PROVIDER_URL, "")
    provider_url = get_validated_string_input(
      "OIDC provider URL (Keycloak auth endpoint, empty = use ambari.sso.provider.url) ({0}): ".format(current or "empty"),
      current, REGEX_ANYTHING, "Invalid URL", False)
  else:
    provider_url = options.sso_oidc_provider_url
  if provider_url:
    properties[SSO_OIDC_PROVIDER_URL] = provider_url
  else:
    properties.pop(SSO_OIDC_PROVIDER_URL, None)


def populate_oidc_provider_certificate(options, properties):
  """Set ambari.sso.oidc.providerCertificate — the Keycloak realm signing cert.

  When set, Ambari accepts JWTs signed by either this cert (OIDC) or the Knox cert
  (ambari.sso.provider.certificate), enabling mixed Knox-SSO / OIDC-SSO deployments.
  When no cert file is provided and no cert is already stored, the key is left unchanged so
  that the Knox provider.certificate continues to serve as the JWT validation cert (backward compat).
  """
  if not options.sso_oidc_public_cert_file:
    cert = get_value_from_dictionary(properties, SSO_OIDC_PROVIDER_CERTIFICATE)
    if not cert:
      # No cert file provided and no existing OIDC cert — skip; JWT validation will
      # fall back to ambari.sso.provider.certificate (backward compatibility).
      return
    get_cert = get_YN_input(
      "An OIDC provider certificate is already set. Do you want to change it [y/n] (n)? ", False)
    if get_cert:
      cert_string = get_multi_line_input("OIDC Provider Certificate PEM")
      properties[SSO_OIDC_PROVIDER_CERTIFICATE] = ensure_complete_cert(cert_string) if cert_string else ""
  else:
    with open(options.sso_oidc_public_cert_file) as cert_file:
      cert_string = cert_file.read()
    properties[SSO_OIDC_PROVIDER_CERTIFICATE] = ensure_complete_cert(cert_string) if cert_string else ""


def populate_oidc_authentication_enabled(options, properties):
  """Set ambari.sso.oidc.authentication.enabled.

  Controls whether the OIDC browser-flow is active for Ambari login independently of the
  Knox SSO authentication.enabled flag.  Defaults to true when clientId/clientSecret are set
  (backward compatibility) but can be set explicitly to 'false' to disable the OIDC browser
  flow while keeping the OIDC credentials for service dispatch.
  """
  if options.sso_oidc_enabled_ambari is None:
    current = get_boolean_from_dictionary(properties, SSO_OIDC_AUTHENTICATION_ENABLED, True)
    enabled = get_YN_input(
      "Enable OIDC browser-flow authentication for Ambari login [y/n] ({0})? ".format('y' if current else 'n'),
      current)
  else:
    enabled = options.sso_oidc_enabled_ambari == 'true'
  properties[SSO_OIDC_AUTHENTICATION_ENABLED] = 'true' if enabled else 'false'


def populate_oidc_service_management(options, properties):
  """Set ambari.sso.oidc.manage_services and ambari.sso.oidc.enabled_services.

  Services listed in enabled_services will receive Keycloak OIDC SSO config instead of Knox
  SSO config, enabling per-service dispatch (e.g. Ranger on OIDC, Hive on Knox).

  Uses an ``is None`` check (not a falsy check) so that ``--sso-oidc-enabled-services=''``
  (explicitly provided as empty) is treated as a non-interactive CLI invocation that clears
  the service list rather than falling through to the interactive prompt.
  """
  if options.sso_oidc_enabled_services is None:
    # Not provided on the CLI — interactive or default path.
    if options.sso_oidc_manage_services is None:
      manage = get_boolean_from_dictionary(properties, SSO_OIDC_MANAGE_SERVICES, False)
      manage = get_YN_input(
        "Manage OIDC SSO configuration for cluster services [y/n] ({0})? ".format('y' if manage else 'n'),
        manage)
    else:
      manage = options.sso_oidc_manage_services == 'true'

    if manage:
      current_services = get_value_from_dictionary(properties, SSO_OIDC_ENABLED_SERVICES, "")
      services = get_validated_string_input(
        "OIDC SSO enabled services (comma-separated or * for all) ({0}): ".format(current_services or "empty"),
        current_services, REGEX_ANYTHING, "Invalid value", False)
    else:
      services = ""
  else:
    # Explicitly provided on the CLI (even if empty string).
    manage = True if not options.sso_oidc_manage_services else options.sso_oidc_manage_services == 'true'
    services = options.sso_oidc_enabled_services.upper() if options.sso_oidc_enabled_services else ""

  properties[SSO_OIDC_MANAGE_SERVICES] = 'true' if manage else 'false'
  properties[SSO_OIDC_ENABLED_SERVICES] = services


def setup_sso_oidc(options):
  print_info_msg("Setup SSO OIDC.")

  server_status, pid = is_server_runing()
  if not server_status:
    raise FatalException(1, 'Ambari Server is not running.')

  if not get_silent():
    ambari_properties = get_ambari_properties()
    admin_login, admin_password = get_ambari_admin_username_password_pair(options)
    # Fetch ALL existing sso-configuration so our PUT preserves Knox SSO settings
    properties = get_sso_properties(ambari_properties, admin_login, admin_password)

    if not options.sso_oidc_enabled:
      current_client_id = get_value_from_dictionary(properties, SSO_OIDC_CLIENT_ID, "")
      if current_client_id:
        sys.stdout.write("\nOIDC browser-flow is currently configured (client ID: %s)\n" % current_client_id)
        enable_oidc = not get_YN_input("Do you want to disable/clear OIDC browser-flow [y/n] (n)? ", False)
      else:
        sys.stdout.write("\nOIDC browser-flow is not configured\n")
        enable_oidc = get_YN_input("Do you want to configure OIDC browser-based SSO flow [y/n] (y)? ", True)
    else:
      enable_oidc = options.sso_oidc_enabled == 'true'

    if enable_oidc:
      populate_oidc_provider_url(options, properties)
      populate_oidc_provider_certificate(options, properties)
      populate_oidc_client_id(options, properties)
      populate_oidc_client_secret(options, properties)
      populate_oidc_callback_url(options, properties)
      populate_oidc_authentication_enabled(options, properties)
      populate_oidc_service_management(options, properties)
      populate_jwt_username_claim(options, properties)
    else:
      for key in [SSO_OIDC_CLIENT_ID, SSO_OIDC_CLIENT_SECRET, SSO_OIDC_CALLBACK_URL,
                  SSO_JWT_USERNAME_CLAIM, SSO_OIDC_PROVIDER_URL, SSO_OIDC_PROVIDER_CERTIFICATE,
                  SSO_OIDC_AUTHENTICATION_ENABLED, SSO_OIDC_MANAGE_SERVICES, SSO_OIDC_ENABLED_SERVICES]:
        properties.pop(key, None)

    update_sso_conf(ambari_properties, properties, admin_login, admin_password)
  else:
    raise NonFatalException("setup-sso-oidc is not enabled in silent mode.")


def ensure_complete_cert(cert_string):
  if cert_string:
    cert_string = cert_string.lstrip().rstrip()

    # Ensure the header and footer are in the string
    if not cert_string.startswith(CERTIFICATE_HEADER):
      cert_string = CERTIFICATE_HEADER + '\n' + cert_string

    if not cert_string.endswith(CERTIFICATE_FOOTER):
      cert_string = cert_string + '\n' + CERTIFICATE_FOOTER

  return cert_string

