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

module.exports = App.WizardRoute.extend({
  route: '/enable',
  breadcrumbs: null,

  enter: function (router) {
    router.get('mainController').dataLoading().done(function () {
      var oidcEnableWizardController = router.get('oidcEnableWizardController');
      App.router.get('updateController').set('isWorking', false);
      var popup = App.ModalPopup.show({
        classNames: ['wizard-modal-wrapper'],
        modalDialogClasses: ['modal-xlg'],
        header: Em.I18n.t('admin.oidc.wizard.header'),
        bodyClass: App.OidcEnableWizardView.extend({
          controller: oidcEnableWizardController
        }),
        primary: Em.I18n.t('form.cancel'),
        showFooter: false,
        secondary: null,

        onClose: function () {
          oidcEnableWizardController.resetOnClose(oidcEnableWizardController, 'main.admin.adminOidc.index');
        },

        didInsertElement: function () {
          this._super();
          this.fitHeight();
        }
      });
      oidcEnableWizardController.set('popup', popup);
      Em.run.next(function () {
        App.router.get('wizardWatcherController').setUser(oidcEnableWizardController.get('name'));
        router.transitionTo('step' + oidcEnableWizardController.get('currentStep'));
      });
    });
  },

  step1: App.StepRoute.extend({
    route: '/step1',

    connectOutlets: function (router) {
      var controller = router.get('oidcEnableWizardController');
      controller.dataLoading().done(function () {
        controller.setCurrentStep('1');
        controller.loadAllPriorSteps().done(function () {
          controller.connectOutlet('oidcEnableWizardStep1', controller.get('content'));
        });
      });
    },

    unroutePath: function () {
      return false;
    },

    nextTransition: function (router) {
      router.transitionTo('step2');
    }
  }),

  step2: App.StepRoute.extend({
    route: '/step2',

    connectOutlets: function (router) {
      var controller = router.get('oidcEnableWizardController');
      controller.dataLoading().done(function () {
        controller.setCurrentStep('2');
        controller.loadAllPriorSteps().done(function () {
          var step2Controller = router.get('oidcEnableWizardStep2Controller');
          step2Controller.set('wizardController', controller);
          controller.connectOutlet('oidcEnableWizardStep2', controller.get('content'));
        });
      });
    },

    unroutePath: function () {
      return false;
    },

    backTransition: function (router) {
      router.transitionTo('step1');
    },

    nextTransition: function (router) {
      router.transitionTo('step3');
    }
  }),

  step3: App.StepRoute.extend({
    route: '/step3',

    connectOutlets: function (router) {
      var controller = router.get('oidcEnableWizardController');
      controller.dataLoading().done(function () {
        controller.setCurrentStep('3');
        controller.loadAllPriorSteps().done(function () {
          var step3Controller = router.get('oidcEnableWizardStep3Controller');
          step3Controller.set('wizardController', controller);
          controller.connectOutlet('oidcEnableWizardStep3', controller.get('content'));
        });
      });
    },

    unroutePath: function () {
      return false;
    },

    backTransition: function (router) {
      router.transitionTo('step2');
    }
  })
});
