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
require('controllers/main/admin/kerberos');
var credentialsUtils = require('utils/credentials');

function getFilename(config) {
  return config && (Em.get(config, 'filename') || config.filename);
}

App.MainAdminOidcController = App.MainAdminKerberosController.extend({
  name: 'mainAdminOidcController',

  oidcCredentialAlias: credentialsUtils.ALIAS.OIDC_CREDENTIALS,

  /**
   * True when oidc-env is present in the cluster config and has a non-empty oidc_admin_url.
   * Drives the Enable vs Disable button visibility in oidc.hbs.
   */
  isOidcEnabled: false,

  /**
   * Flat list of OIDC client descriptors from the COMPOSITE descriptor.
   * Each item: { serviceName, name, clientId, realm, enabled, protocolMappers }
   */
  oidcClientsContent: [],

  /**
   * Launch the Enable OIDC wizard.
   */
  enableOidc: function () {
    App.router.transitionTo('main.admin.adminOidc.adminOidcEnable');
  },

  /**
   * Show confirmation popup before entering the Disable OIDC flow.
   */
  disableOidc: function() {
    var self = this;
    return App.showConfirmationPopup(function() {
      App.router.get('oidcDisableController').setDBProperty('onClosePath', 'main.admin.adminOidc.index');
      App.router.get('oidcDisableController').set('content', Em.Object.create({
        controllerName: 'oidcDisableController'
      }));
      App.router.transitionTo('main.admin.adminOidc.adminOidcDisable');
    }, Em.I18n.t('admin.oidc.disable.confirmation.body'), null,
       Em.I18n.t('admin.oidc.disable.confirmation.header'));
  },

  /**
   * Keep OIDC flow on the OIDC admin page.
   */
  startKerberosWizard: function () {
    var self = this;
    this.checkServiceWarnings().then(function() {
      self.setAddSecurityWizardStatus('RUNNING');
      App.router.get('kerberosWizardController').setDBProperty('onClosePath', 'main.admin.adminOidc.index');
      App.router.transitionTo('adminKerberos.adminAddKerberos');
    });
  },

  /**
   * oidc-env lives in cluster configurations, not the kerberos descriptor.
   * Uses the dedicated ENV descriptor type which reads the current desired oidc-env config
   * server-side and returns it in the standard OidcDescriptor envelope.
   */
  loadStep: function() {
    var self = this;
    var dfd = $.Deferred();
    this.clearStep();
    App.ajax.send({
      name: 'admin.oidc.cluster.env',
      sender: this,
      data: { clusterName: App.get('clusterName') }
    }).then(function(data) {
      var rawProps = (data && data.OidcDescriptor && data.OidcDescriptor.oidc_descriptor) || {};
      self.set('isOidcEnabled', !!rawProps['oidc_admin_url']);
      self.set('oidcEnv', rawProps);
      // the clients table may have loaded first: resolve its ${oidc_realm} now
      if (self.get('oidcDescriptorData')) {
        self._onLoadOidcClientsSuccess(self.get('oidcDescriptorData'));
      }
      var configs = Object.keys(rawProps).map(function(name) {
        return App.ServiceConfigProperty.create({
          name: name,
          value: rawProps[name],
          filename: 'oidc-env.xml',
          displayName: name,
          isEditable: true,
          isRequired: false
        });
      });
      self.setStepConfigs(configs);
      dfd.resolve();
    }, function() {
      self.set('isOidcEnabled', false);
      self.setStepConfigs([]);
      dfd.resolve();
    }).always(function() {
      self.set('isRecommendedLoaded', true);
    });
    return dfd.promise();
  },

  /**
   * Load the COMPOSITE OIDC descriptor and populate oidcClientsContent.
   */
  loadOidcClientsTable: function() {
    var self = this;
    App.ajax.send({
      name: 'admin.oidc.descriptor.composite',
      sender: this,
      data: {
        clusterName: App.get('clusterName')
      },
      success: '_onLoadOidcClientsSuccess',
      error: '_onLoadOidcClientsError'
    });
  },

  _onLoadOidcClientsSuccess: function(data) {
    var self = this;
    this.set('oidcDescriptorData', data);
    var clients = [];
    var descriptor = data && data.OidcDescriptor && data.OidcDescriptor.oidc_descriptor;
    var services = descriptor && descriptor.services;
    var installedServiceNames = App.Service.find().mapProperty('serviceName');
    if (Array.isArray(services)) {
      services
        .filter(function(service) {
          return installedServiceNames.contains(service.name);
        })
        .forEach(function(service) {
          var serviceName = service.name || '';
          var serviceClients = Array.isArray(service.clients) ? service.clients : [];
          serviceClients.forEach(function(client) {
            var rawMappers = Array.isArray(client.protocol_mappers) ? client.protocol_mappers : [];
            var protocolMappers = rawMappers.map(function(mapper) {
              var configEntries = [];
              if (mapper.config && typeof mapper.config === 'object') {
                Object.keys(mapper.config).forEach(function(k) {
                  configEntries.push({ key: k, value: mapper.config[k] });
                });
              }
              return {
                name: mapper.name || '',
                protocol: mapper.protocol || '',
                protocolMapper: mapper.protocolMapper || '',
                configEntries: configEntries
              };
            });
            clients.push({
              serviceName: serviceName,
              name: client.name || '',
              clientId: self._resolveDescriptorVars(client.client_id || ''),
              realm: self._resolveDescriptorVars(client.realm || ''),
              enabled: service.enabled !== false,
              protocolMappers: protocolMappers
            });
          });
        });
    }
    this.set('oidcClientsContent', clients);
  },

  /**
   * Resolves known descriptor template variables against live cluster state.
   * ${cluster_name} is always available; other unrecognised variables are left as-is.
   */
  _resolveDescriptorVars: function(value) {
    return value
      .replace(/\$\{cluster_name\}/g, App.get('clusterName') || '')
      .replace(/\$\{oidc_realm\}/g, this.get('oidcEnv.oidc_realm') || '${oidc_realm}');
  },

  _onLoadOidcClientsError: function() {
    this.set('oidcClientsContent', []);
  },

  createServiceConfig: function(configs) {
    return [App.ServiceConfig.create({
      displayName: Em.I18n.t('common.oidc'),
      name: 'OIDC',
      serviceName: 'OIDC',
      configCategories: [
        App.ServiceConfigCategory.create({
          name: 'OIDC',
          displayName: Em.I18n.t('common.oidc')
        })
      ],
      configs: configs,
      configGroups: [],
      showConfig: true
    })];
  },

  submit: function () {
    var self = this;
    var kerberosDescriptor = this.get('kerberosDescriptor');
    var configs = [];

    this.get('stepConfigs').forEach(function(_stepConfig) {
      configs = configs.concat(_stepConfig.get('configs'));
    });

    configs = configs.filter(function(config) {
      return getFilename(config) === 'oidc-env.xml';
    });

    this.updateKerberosDescriptor(kerberosDescriptor, configs);

    return App.ajax.send({
      name: 'admin.kerberos.cluster.artifact.update',
      sender: self,
      dataType: 'text',
      data: {
        artifactName: 'kerberos_descriptor',
        data: {
          artifact_data: kerberosDescriptor
        }
      },
      success: '_updateConfigs',
      error: 'createKerberosDescriptor'
    });
  },

  showManageOIDCCredentialsPopup: function() {
    return App.showManageOidcCredentialsPopup();
  },

  /**
   * Run OIDC Provisioning in its own progress page: register the Keycloak clients, then restart the services with
   * OIDC clients that need it.
   */
  runOidcProvisioning: function() {
    App.router.transitionTo('main.admin.adminOidc.adminOidcProvision');
  },

  /**
   * Show protocol mapper detail popup for the given client.
   */
  showProtocolMappersPopup: function(client) {
    var mappers = (client && client.protocolMappers) || [];
    var clientName = (client && client.name) || '';
    App.ModalPopup.show({
      header: Em.I18n.t('admin.oidc.clients.mappers.title').format(clientName),
      bodyClass: Em.View.extend({
        templateName: require('templates/main/admin/oidc_protocol_mappers_popup')
      }),
      mappers: mappers,
      primary: false,
      secondary: Em.I18n.t('common.close')
    });
  }
});
