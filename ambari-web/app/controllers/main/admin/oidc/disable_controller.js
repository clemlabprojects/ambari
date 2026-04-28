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
 * Drives the OIDC un-provisioning flow:
 *   1. Stop all affected services (those that have OIDC clients).
 *   2. Delete OIDC clients from the external provider via ConfigureOidcServerAction(DELETE).
 *   3. Start all services again.
 *
 * This mirrors the Kerberos disable controller pattern but is lighter: we do not need
 * ZooKeeper-first sequencing, and we do not delete an OIDC service component.
 */
App.OidcDisableController = App.KerberosProgressPageController.extend(App.WizardEnableDone, {

  name: 'oidcDisableController',
  clusterDeployState: 'DEFAULT',
  commands: ['stopAllServices', 'deleteOidcClients', 'startAllServices'],

  tasksMessagesPrefix: 'admin.oidc.disable.step',

  loadStep: function() {
    this.set('content.controllerName', 'oidcDisableController');
    this.loadTasksStatuses();
    this.loadTasksRequestIds();
    this.loadRequestIds();
    this._super();
  },

  stopAllServices: function() {
    this.stopServices([], false);
  },

  deleteOidcClients: function() {
    return App.ajax.send({
      name: 'admin.oidc.deprovision',
      sender: this,
      data: {
        clusterName: App.get('clusterName'),
        data: {
          Clusters: {
            security_type: App.router.get('mainAdminKerberosController.securityEnabled') ? 'KERBEROS' : 'NONE'
          }
        }
      },
      success: 'startPolling',
      error: 'onTaskError'
    });
  },

  startAllServices: function() {
    this.startServices(true);
  }

});
