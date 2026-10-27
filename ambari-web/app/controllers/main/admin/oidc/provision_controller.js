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
require('controllers/main/admin/kerberos/progress_controller');

/**
 * Run OIDC Provisioning: registers the Keycloak clients again, then restarts the services with OIDC clients that need
 * it, on a progress page like Disable OIDC.
 */
App.OidcProvisionController = App.KerberosProgressPageController.extend(App.WizardEnableDone, App.OidcServicesMixin, {

  name: 'oidcProvisionController',
  clusterDeployState: 'DEFAULT',
  commands: ['provisionOidcClients', 'restartOidcServices'],

  tasksMessagesPrefix: 'admin.oidc.provision.step',

  loadStep: function () {
    this.set('content.controllerName', 'oidcProvisionController');
    this.loadTasksStatuses();
    this.loadTasksRequestIds();
    this.loadRequestIds();
    this._super();
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
