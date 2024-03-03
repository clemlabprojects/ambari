/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

 module.exports = [
  {
    "name": "hue_admin_password",
    "category": "HUE_AUTHENTICATION",
    "filename": "hue-env.xml",
    "index": 0,
    "serviceName": "HUE"
  },
  {
    "name": "hue_backend",
    "category": "HUE_AUTHENTICATION",
    "filename": "hue-ini-conf.xml",
    "index": 1,
    "displayType": "radio button",
    "options": [
      {
        "displayName": "desktop.auth.backend.AllowAllBackend",
        "hidden": false
      },
      {
        "displayName": "desktop.auth.backend.AllowFirstUserDjangoBackend",
        "hidden": false
      },
      {
        "displayName": "desktop.auth.backend.LdapBackend",
        "hidden": false
      },
      {
        "displayName": "desktop.auth.backend.SpnegoDjangoBackend",
        "hidden": false
      },
      {
        "displayName": "desktop.auth.backend.LdapBackend,desktop.auth.backend.AllowFirstUserDjangoBackend",
        "hidden": false
      },
      {
        "displayName": "desktop.auth.backend.KnoxSpnegoDjangoBackend",
        "hidden": false
      }
    ],
    "radioName": "hue-authentication-method-database",
    "serviceName": "HUE"
  },
  {
    "name": "hue_search_bind_authentication",
    "category": "HUE_AUTHENTICATION",
    "filename": "hue-ini-conf.xml",
    "index": 2,
    "serviceName": "HUE"
  },
  {
    "name": "hue_ldap_url",
    "category": "HUE_AUTHENTICATION",
    "filename": "hue-ini-conf.xml",
    "index": 3,
    "serviceName": "HUE"
  },
  {
    "name": "hue_nt_domain",
    "category": "HUE_AUTHENTICATION",
    "filename": "hue-ini-conf.xml",
    "index": 4,
    "serviceName": "HUE"
  },
  {
    "name": "hue_base_dn",
    "category": "HUE_AUTHENTICATION",
    "filename": "hue-ini-conf.xml",
    "index": 5,
    "serviceName": "HUE"
  },
  {
    "name": "hue_create_users_on_login",
    "category": "HUE_AUTHENTICATION",
    "filename": "hue-ini-conf.xml",
    "index": 6,
    "serviceName": "HUE"
  },
  {
    "name": "hue_bind_dn",
    "category": "HUE_AUTHENTICATION",
    "filename": "hue-ini-conf.xml",
    "index": 6,
    "serviceName": "HUE"
  },
  {
    "name": "hue_bind_password",
    "category": "HUE_AUTHENTICATION",
    "filename": "hue-ini-conf.xml",
    "index": 7,
    "serviceName": "HUE"
  },
  {
    "name": "hue_ssl_enabled",
    "category": "HUE_TLS_SETTINGS",
    "filename": "hue-env.xml",
    "index": 1,
    "serviceName": "HUE"
  },
  {
    "name": "hue_ssl_validate",
    "category": "HUE_TLS_SETTINGS",
    "filename": "hue-ini-conf.xml",
    "index": 2,
    "serviceName": "HUE"
  },
  {
    "name": "hue_ssl_certificate",
    "category": "HUE_TLS_SETTINGS",
    "filename": "hue-ini-conf.xml",
    "index": 3,
    "serviceName": "HUE"
  },
  {
    "name": "hue_ssl_private_key",
    "category": "HUE_TLS_SETTINGS",
    "filename": "hue-ini-conf.xml",
    "index": 4,
    "serviceName": "HUE"
  },
  {
    "name": "hue_ssl_cert_chain",
    "category": "HUE_TLS_SETTINGS",
    "filename": "hue-ini-conf.xml",
    "index": 5,
    "serviceName": "HUE"
  },
  {
    "name": "hue_database",
    "category": "HUE_SERVER",
    "filename": "hue-env.xml",
    "index": 1,
    "serviceName": "HUE"
  },
  {
    "name": "hue_db_name",
    "category": "HUE_SERVER",
    "filename": "hue-ini-conf.xml",
    "index": 2,
    "serviceName": "HUE"
  },
  {
    "name": "hue_db_username",
    "category": "HUE_SERVER",
    "filename": "hue-ini-conf.xml",
    "index": 3,
    "serviceName": "HUE"
  },
  {
    "name": "hue_db_password",
    "category": "HUE_SERVER",
    "filename": "hue-ini-conf.xml",
    "index": 4,
    "serviceName": "HUE"
  },
  {
    "name": "hue.jpa.jdbc.url",
    "category": "HUE_SERVER",
    "filename": "hue-ini-conf.xml",
    "index": 5,
    "serviceName": "HUE"
  },
  {
    "name": "hue.jpa.jdbc.driver",
    "category": "HUE_SERVER",
    "filename": "hue-ini-conf.xml",
    "index": 5,
    "serviceName": "HUE"
  }
]