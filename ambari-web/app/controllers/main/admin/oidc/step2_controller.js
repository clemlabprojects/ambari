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

App.OidcEnableWizardStep2Controller = Em.Controller.extend({

  name: 'oidcEnableWizardStep2Controller',

  /**
   * Set by connectOutlet in the routes file.
   * @type {App.OidcEnableWizardController}
   */
  wizardController: null,

  // Two-way bindings to wizard content
  adminUrl: contentProperty('wizardController.content.adminUrl'),
  adminRealm: contentProperty('wizardController.content.adminRealm'),
  adminClientId: contentProperty('wizardController.content.adminClientId'),
  adminClientSecret: contentProperty('wizardController.content.adminClientSecret'),
  adminUsername: contentProperty('wizardController.content.adminUsername'),
  adminPassword: contentProperty('wizardController.content.adminPassword'),
  realm: contentProperty('wizardController.content.realm'),
  issuerUrl: contentProperty('wizardController.content.issuerUrl'),
  verifyTls: contentProperty('wizardController.content.verifyTls'),

  /**
   * Issuer URL Ambari uses when the field is left blank: {admin URL}/realms/{target realm}
   * @type {string}
   */
  issuerUrlPlaceholder: function () {
    var adminUrl = (this.get('adminUrl') || '').trim().replace(/\/+$/, '');
    var realm = (this.get('realm') || '').trim();
    var defaultUrl = (adminUrl || Em.I18n.t('admin.oidc.wizard.step2.issuerUrl.placeholder.adminUrl')) + '/realms/' +
      (realm || Em.I18n.t('admin.oidc.wizard.step2.issuerUrl.placeholder.realm'));
    return Em.I18n.t('admin.oidc.wizard.step2.issuerUrl.placeholder').format(defaultUrl);
  }.property('adminUrl', 'realm'),

  /**
   * Whether the admin credential will survive Ambari restarts; drives the hint shown under it.
   * @type {boolean}
   */
  isCredentialStorePersisted: Em.computed.alias('App.isCredentialStorePersistent'),

  /**
   * Test connection status: null | 'testing' | 'success' | 'error'
   * @type {string|null}
   */
  testStatus: null,

  /**
   * Message returned from a test connection attempt.
   * @type {string}
   */
  testMessage: '',

  isTestSuccess: Em.computed.equal('testStatus', 'success'),
  isTestError: Em.computed.equal('testStatus', 'error'),
  isTesting: Em.computed.equal('testStatus', 'testing'),

  /**
   * Disable the Next button until the minimum required fields are filled.
   * @type {boolean}
   */
  isSubmitDisabled: function () {
    return !this.get('adminUrl') || !this.get('realm') ||
           !this.get('adminUsername') || !this.get('adminPassword');
  }.property('adminUrl', 'realm', 'adminUsername', 'adminPassword'),

  /**
   * Send a test-connection request to the Ambari server using the current
   * Keycloak admin credentials.
   */
  testConnection: function () {
    this.set('testStatus', 'testing');
    this.set('testMessage', '');
    App.ajax.send({
      name: 'admin.oidc.test',
      sender: this,
      data: {
        clusterName: App.get('clusterName'),
        data: {
          admin_url: this.get('adminUrl'),
          admin_realm: this.get('adminRealm') || 'master',
          admin_client_id: this.get('adminClientId') || 'admin-cli',
          admin_client_secret: this.get('adminClientSecret') || '',
          admin_username: this.get('adminUsername'),
          admin_password: this.get('adminPassword'),
          realm: this.get('realm'),
          verify_tls: this.get('verifyTls') !== false
        }
      },
      success: '_onTestSuccess',
      error: '_onTestError'
    });
  },

  _onTestSuccess: function (data) {
    this.set('testStatus', data && data.status === 'OK' ? 'success' : 'error');
    this.set('testMessage', (data && data.message) || '');
  },

  _onTestError: function (xhr) {
    this.set('testStatus', 'error');
    var msg = '';
    try {
      msg = JSON.parse(xhr.responseText).message;
    } catch (e) {}
    this.set('testMessage', msg || Em.I18n.t('admin.oidc.wizard.step2.testError'));
  },

  submit: function () {
    if (!this.get('isSubmitDisabled')) {
      App.router.send('next');
    }
  },

  // Back/Next buttons of the template
  next: function () {
    this.submit();
  },

  back: function () {
    App.router.send('back');
  }
});
