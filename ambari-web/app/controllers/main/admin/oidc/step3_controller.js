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

App.OidcEnableWizardStep3Controller = Em.Controller.extend({

  name: 'oidcEnableWizardStep3Controller',

  /**
   * Set by connectOutlet in the routes file.
   * @type {App.OidcEnableWizardController}
   */
  wizardController: null,

  restartServices: Em.computed.alias('wizardController.content.restartServices'),

  isSubmitDisabled: false,

  /**
   * Orchestrate OIDC enablement:
   *   1. Save oidc-env config to the cluster.
   *   2. Store Keycloak admin credentials in the credential store.
   *   3. Trigger a provisioning request and navigate away on success.
   */
  enableOidc: function () {
    var content = this.get('wizardController.content');
    var clusterName = App.get('clusterName');
    this.set('isSubmitDisabled', true);

    App.ajax.send({
      name: 'admin.save_configs',
      sender: this,
      data: {
        clusterName: clusterName,
        siteName: 'oidc-env',
        properties: {
          oidc_provider: 'keycloak',
          oidc_admin_url: content.get('adminUrl'),
          oidc_admin_realm: content.get('adminRealm') || 'master',
          oidc_admin_client_id: content.get('adminClientId') || 'admin-cli',
          oidc_admin_client_secret: content.get('adminClientSecret') || '',
          oidc_realm: content.get('realm'),
          oidc_issuer_url: content.get('issuerUrl') || '',
          oidc_verify_tls: String(content.get('verifyTls') !== false)
        }
      }
    }).done(function () {
      this._storeCredentials(content, clusterName);
    }.bind(this)).fail(function () {
      this.set('isSubmitDisabled', false);
    }.bind(this));
  },

  /**
   * Store Keycloak admin username/password in the cluster credential store.
   * Proceeds to provisioning regardless of the credential store outcome so
   * that clusters without a credential store configured are not blocked.
   *
   * @param {Em.Object} content
   * @param {string} clusterName
   */
  _storeCredentials: function (content, clusterName) {
    var resource = credentialsUtils.createCredentialResource(
      content.get('adminUsername'),
      content.get('adminPassword'),
      'temporary'
    );
    credentialsUtils.createOrUpdateCredentials(
      clusterName,
      credentialsUtils.ALIAS.OIDC_CREDENTIALS,
      resource
    ).always(function () {
      this._runProvisioning(clusterName);
    }.bind(this));
  },

  /**
   * PUT a cluster-level security update to trigger Ambari OIDC provisioning.
   * On success, show background operations and close the wizard.
   *
   * @param {string} clusterName
   */
  _runProvisioning: function (clusterName) {
    var securityType = App.router.get('mainAdminKerberosController.securityEnabled') ? 'KERBEROS' : 'NONE';
    App.ajax.send({
      name: 'admin.configure.oidc.only',
      sender: this,
      data: {
        clusterName: clusterName,
        data: {
          Clusters: {
            security_type: securityType
          }
        }
      }
    }).done(function () {
      App.router.get('backgroundOperationsController').showPopup();
      var controller = this.get('wizardController');
      controller.resetOnClose(controller, 'main.admin.adminOidc.index');
    }.bind(this)).fail(function () {
      this.set('isSubmitDisabled', false);
    }.bind(this));
  }
});
