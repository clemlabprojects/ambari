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
require('controllers/wizard');

App.OidcEnableWizardController = App.WizardController.extend({

  name: 'oidcEnableWizardController',

  totalSteps: 3,

  /**
   * @type {string}
   */
  displayName: Em.I18n.t('admin.oidc.wizard.header'),

  content: Em.Object.create({
    controllerName: 'oidcEnableWizardController',
    adminUrl: '',
    adminRealm: 'master',
    adminClientId: 'admin-cli',
    adminClientSecret: '',
    adminUsername: '',
    adminPassword: '',
    realm: '',
    issuerUrl: '',
    verifyTls: true,
    restartServices: false
  }),

  /**
   * Set current step.
   * @param {string} currentStep
   * @param {boolean} completed
   * @param {boolean} skipStateSave
   */
  setCurrentStep: function (currentStep, completed, skipStateSave) {
    this._super(currentStep, completed);
  },

  setStepsEnable: function () {
    for (var i = 1; i <= this.get('totalSteps'); i++) {
      var step = this.get('isStepDisabled').findProperty('step', i);
      if (i <= this.get('currentStep') && App.get('router.clusterController.isLoaded')) {
        step.set('value', false);
      } else {
        step.set('value', i !== this.get('currentStep'));
      }
    }
  }.observes('currentStep', 'App.router.clusterController.isLoaded'),

  loadMap: {
    '1': [],
    '2': [],
    '3': []
  }
});
