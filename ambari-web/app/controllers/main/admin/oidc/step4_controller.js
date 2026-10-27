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

var App = require('app');

/**
 * Install and Provision: installs the OIDC Clients, then registers the Keycloak clients of the services that declare
 * an oidc.json descriptor. Each task shows its background operation, as the Enable Kerberos wizard does.
 */
App.OidcEnableWizardStep4Controller = App.OidcProgressPageController.extend({

  name: 'oidcEnableWizardStep4Controller',

  commands: ['installOidcClients', 'provisionOidcClients'],

  installOidcClients: function () {
    var self = this;
    // nothing to install when every OIDC Client is installed already (the service update would start no request)
    App.ajax.send({
      name: 'common.service.info',
      sender: this,
      data: {
        clusterName: App.get('clusterName'),
        serviceName: 'OIDC'
      },
      error: 'onTaskError'
    }).done(function (data) {
      if (Em.get(data || {}, 'ServiceInfo.state') === 'INSTALLED' || Em.get(data || {}, 'ServiceInfo.state') === 'STARTED') {
        self.onTaskCompleted();
        return;
      }
      App.ajax.send({
        name: 'common.service.update',
        sender: self,
        data: {
          clusterName: App.get('clusterName'),
          serviceName: 'OIDC',
          context: Em.I18n.t('requestInfo.oidcService'),
          ServiceInfo: {
            state: 'INSTALLED'
          }
        },
        success: 'startPolling',
        error: 'onTaskError'
      });
    });
  },

  provisionOidcClients: function () {
    App.ajax.send({
      name: 'admin.configure.oidc.only',
      sender: this,
      data: {
        clusterName: App.get('clusterName'),
        data: {
          Clusters: {
            security_type: App.get('isKerberosEnabled') ? 'KERBEROS' : 'NONE'
          }
        }
      },
      success: 'startPolling',
      error: 'onTaskError'
    });
  }
});
