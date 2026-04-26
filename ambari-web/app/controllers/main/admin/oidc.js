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
   * Flat list of OIDC client descriptors from the COMPOSITE descriptor.
   * Each item: { serviceName, name, clientId, realm, enabled, protocolMappers }
   */
  oidcClientsContent: [],

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
   * OIDC page only edits oidc-env properties.
   */
  loadStep: function() {
    var self = this;
    this.clearStep();
    return this.getDescriptor().then(function (properties) {
      var oidcProperties = (properties || []).filter(function(property) {
        return getFilename(property) === 'oidc-env.xml';
      });
      self.setStepConfigs(oidcProperties);
    }).always(function() {
      self.set('isRecommendedLoaded', true);
    });
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
    var clients = [];
    var descriptor = data && data.OidcDescriptor && data.OidcDescriptor.oidc_descriptor;
    var services = descriptor && descriptor.services;
    if (Array.isArray(services)) {
      services.forEach(function(service) {
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
            clientId: client.client_id || '',
            realm: client.realm || '',
            enabled: service.enabled !== false,
            protocolMappers: protocolMappers
          });
        });
      });
    }
    this.set('oidcClientsContent', clients);
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

  runOidcProvisioning: function() {
    var securityType = this.get('securityEnabled') ? 'KERBEROS' : 'NONE';
    var self = this;

    return App.ajax.send({
      name: 'admin.configure.oidc.only',
      sender: this,
      data: {
        clusterName: App.get('clusterName'),
        data: {
          Clusters: {
            security_type: securityType
          }
        }
      }
    }).done(function() {
      App.router.get('backgroundOperationsController').showPopup();
      self.loadStep();
    });
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
