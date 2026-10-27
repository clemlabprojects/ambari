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
 * Read/write alias: Em.computed.alias of ember_computed.js is read-only, so values set by a bound field
 * would never reach the wizard content.
 */
function contentProperty(path) {
  return function (key, value) {
    if (arguments.length > 1) {
      this.set(path, value);
    }
    return this.get(path);
  }.property(path);
}
var credentialsUtils = require('utils/credentials');

App.OidcEnableWizardStep3Controller = Em.Controller.extend(App.InstallComponent, {

  name: 'oidcEnableWizardStep3Controller',

  /**
   * Set by connectOutlet in the routes file.
   * @type {App.OidcEnableWizardController}
   */
  wizardController: null,

  isSubmitDisabled: false,

  /**
   * Issuer URL shown in the review: the one entered, or the default Ambari uses when it is left blank.
   * @type {string}
   */
  effectiveIssuerUrl: function () {
    var content = this.get('wizardController.content');
    var issuerUrl = (content && content.get('issuerUrl') || '').trim();
    if (issuerUrl) {
      return issuerUrl;
    }
    var adminUrl = (content && content.get('adminUrl') || '').trim().replace(/\/+$/, '');
    return Em.I18n.t('admin.oidc.wizard.step3.issuerUrl.default').format(adminUrl + '/realms/' + (content && content.get('realm') || ''));
  }.property('wizardController.content.issuerUrl', 'wizardController.content.adminUrl', 'wizardController.content.realm'),

  /**
   * Prepare OIDC enablement, then hand over to the progress steps:
   *   1. Add the OIDC service (OIDC Client on every host) when it is not in the cluster yet.
   *   2. Save oidc-env config to the cluster.
   *   3. Store Keycloak admin credentials in the credential store.
   * Step 4 installs the OIDC Clients and provisions the Keycloak clients, step 5 restarts the services.
   */
  enableOidc: function () {
    this.set('isSubmitDisabled', true);
    this._ensureOidcService().done(this._saveOidcEnv.bind(this)).fail(function () {
      this.set('isSubmitDisabled', false);
    }.bind(this));
  },

  /**
   * Adds the OIDC service with an OIDC Client on every host, as the Enable Kerberos wizard does when its OIDC scope is
   * selected (the install runs in step 4). Resolves at once when the service is already in the cluster.
   *
   * @returns {$.Deferred}
   */
  _ensureOidcService: function () {
    var dfd = $.Deferred();
    var self = this;
    // ask the server, not the client models: after a failed attempt the service may exist already
    App.ajax.send({
      name: 'common.service.info',
      sender: this,
      data: {
        clusterName: App.get('clusterName'),
        serviceName: 'OIDC'
      },
      error: '_onOidcServiceInfoError'
    }).done(function () {
      dfd.resolve();
    }).fail(function (request) {
      if (request && request.status === 404) {
        self._addOidcService().done(dfd.resolve).fail(dfd.reject);
      } else {
        dfd.reject();
      }
    });
    return dfd.promise();
  },

  /**
   * A missing OIDC service is the expected answer of common.service.info, not an error to show.
   */
  _onOidcServiceInfoError: Em.K,

  _addOidcService: function () {
    var dfd = $.Deferred();
    var self = this;
    App.ajax.send({
      name: 'wizard.step8.create_selected_services',
      sender: this,
      data: {
        data: '{"ServiceInfo": { "service_name": "OIDC"}}',
        cluster: App.get('clusterName')
      }
    }).done(function () {
      self.updateAndCreateServiceComponent('OIDC_CLIENT').done(function () {
        self._createOidcClientsOnAllHosts().done(dfd.resolve).fail(dfd.reject);
      }).fail(dfd.reject);
    }).fail(dfd.reject);
    return dfd.promise();
  },

  _createOidcClientsOnAllHosts: function () {
    var queryStr = App.get('allHostNames').map(function (hostName) {
      return 'Hosts/host_name=' + hostName;
    }).join('|');
    return App.ajax.send({
      name: 'wizard.step8.register_host_to_component',
      sender: this,
      data: {
        cluster: App.get('clusterName'),
        data: JSON.stringify({
          RequestInfo: {query: queryStr},
          Body: {host_components: [{HostRoles: {component_name: 'OIDC_CLIENT'}}]}
        })
      }
    });
  },

  _saveOidcEnv: function () {
    var content = this.get('wizardController.content');
    var clusterName = App.get('clusterName');

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
   *
   * The credential goes to the persisted store whenever the server has one: Ambari reads it
   * again for every service installed or removed later, and the Kubernetes view can only read
   * the persisted store. Without a persisted store the credential is kept temporarily and the
   * user is warned that it expires. Provisioning is not started when the credential could not
   * be stored, since it would fail without it.
   *
   * @param {Em.Object} content
   * @param {string} clusterName
   */
  _storeCredentials: function (content, clusterName) {
    var self = this;
    this._resolveStoreType(clusterName).done(function (storeType) {
      var resource = credentialsUtils.createCredentialResource(
        content.get('adminUsername'),
        content.get('adminPassword'),
        storeType
      );
      credentialsUtils.createOrUpdateCredentials(
        clusterName,
        credentialsUtils.ALIAS.OIDC_CREDENTIALS,
        resource
      ).done(function (stored, result) {
        if (!stored) {
          self._onStoreCredentialsFailed(result);
        } else if (storeType === credentialsUtils.STORE_TYPES.PERSISTENT) {
          self._goToProgress();
        } else {
          self._showTemporaryStoreWarning(function () {
            self._goToProgress();
          });
        }
      }).fail(function (error) {
        self._onStoreCredentialsFailed(error);
      });
    });
  },

  /**
   * Resolve the store type for the OIDC admin credential: <code>persisted</code> when the server
   * has a persistent credential store, <code>temporary</code> otherwise (also when the store
   * information cannot be read).
   *
   * @param {string} clusterName
   * @returns {$.Deferred} promise resolved with the store type
   */
  _resolveStoreType: function (clusterName) {
    var dfd = $.Deferred();
    credentialsUtils.isStorePersisted(clusterName).done(function (persisted) {
      dfd.resolve(persisted === true ? credentialsUtils.STORE_TYPES.PERSISTENT : credentialsUtils.STORE_TYPES.TEMPORARY);
    }).fail(function () {
      dfd.resolve(credentialsUtils.STORE_TYPES.TEMPORARY);
    });
    return dfd.promise();
  },

  /**
   * Warn that a temporary credential expires, then continue once the user acknowledges it.
   *
   * @param {Function} callback
   */
  _showTemporaryStoreWarning: function (callback) {
    App.showAlertPopup(
      Em.I18n.t('admin.oidc.credentials.temporary.header'),
      Em.I18n.t('admin.oidc.credentials.temporary.body'),
      callback
    );
  },

  /**
   * Show why the credential could not be stored and let the user try again.
   *
   * @param {*} error error returned by the credential store request
   */
  _onStoreCredentialsFailed: function (error) {
    var body = Em.I18n.t('admin.oidc.credentials.store.error.body');
    if (typeof error === 'string' && error) {
      body += ' ' + Em.I18n.t('admin.oidc.credentials.store.error.reason').format(error);
    }
    this.set('isSubmitDisabled', false);
    App.showAlertPopup(Em.I18n.t('admin.oidc.credentials.store.error.header'), body);
  },

  _goToProgress: function () {
    this.set('isSubmitDisabled', false);
    App.router.send('next');
  },

  // Back button of the template
  back: function () {
    App.router.send('back');
  }
});
