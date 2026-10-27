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
var credentialsUtils = require('utils/credentials');
require('controllers/main/admin/kerberos/progress_controller');

/**
 * Drives the OIDC un-provisioning flow:
 *   1. Stop the services that have OIDC clients.
 *   2. Delete OIDC clients from the external provider via ConfigureOidcServerAction(DELETE), which also reverts
 *      the configuration provisioning set.
 *   3. Remove the OIDC service (oidc-env, OIDC Clients) and the Keycloak admin credential, as disabling Kerberos
 *      removes the KERBEROS service: a later enable starts clean, e.g. against another Keycloak.
 *   4. Start those services again.
 *
 * This mirrors the Kerberos disable controller pattern but is lighter: we do not need
 * ZooKeeper-first sequencing, and we do not delete an OIDC service component.
 */
App.OidcDisableController = App.KerberosProgressPageController.extend(App.WizardEnableDone, App.OidcServicesMixin, {

  name: 'oidcDisableController',
  clusterDeployState: 'DEFAULT',
  commands: ['stopOidcServices', 'deleteOidcClients', 'deleteOidcService', 'startOidcServices'],

  tasksMessagesPrefix: 'admin.oidc.disable.step',

  loadStep: function() {
    this.set('content.controllerName', 'oidcDisableController');
    this.loadTasksStatuses();
    this.loadTasksRequestIds();
    this.loadRequestIds();
    this._super();
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

  deleteOidcService: function() {
    var self = this;
    var clusterName = App.get('clusterName');
    // both deletes answer with an empty body, which jQuery reports as a failure: judge them by status
    var statusOf = function(first, second, third) {
      return (third && third.status !== undefined) ? third.status : (first && first.status);
    };
    var isDone = function(status) {
      return (status >= 200 && status < 300) || status === 404;
    };
    App.ajax.send({
      name: 'credentials.delete',
      sender: this,
      data: {
        clusterName: clusterName,
        alias: credentialsUtils.ALIAS.OIDC_CREDENTIALS
      },
      error: '_onOidcDeleteError'
    }).always(function(first, second, third) {
      if (!isDone(statusOf(first, second, third))) {
        self.onTaskError();
        return;
      }
      App.ajax.send({
        name: 'common.delete.service',
        sender: self,
        data: {
          clusterName: clusterName,
          serviceName: 'OIDC'
        },
        error: '_onOidcDeleteError'
      }).always(function(first, second, third) {
        if (isDone(statusOf(first, second, third))) {
          self.onTaskCompleted();
        } else {
          self.onTaskError();
        }
      });
    });
  },

  _onOidcDeleteError: Em.K

});
