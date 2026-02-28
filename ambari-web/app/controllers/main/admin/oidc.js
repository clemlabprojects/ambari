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

function getFilename(config) {
  return config && (Em.get(config, 'filename') || config.filename);
}

App.MainAdminOidcController = App.MainAdminKerberosController.extend({
  name: 'mainAdminOidcController',

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
    this.getDescriptor().then(function (properties) {
      var oidcProperties = (properties || []).filter(function(property) {
        return getFilename(property) === 'oidc-env.xml';
      });
      self.setStepConfigs(oidcProperties);
    }).always(function() {
      self.set('isRecommendedLoaded', true);
    });
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
  }
});
