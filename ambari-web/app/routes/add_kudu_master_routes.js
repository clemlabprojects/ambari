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
  route: '/highAvailability/Kudu/addMaster',

  breadcrumbs: {
    label: Em.I18n.t('admin.addKuduMaster.wizard.header')
  },

  enter: function (router) {
    var addKuduMasterWizardController = router.get('addKuduMasterWizardController');
    addKuduMasterWizardController.dataLoading().done(function () {
      App.router.set('mainServiceItemController.content', App.Service.find().findProperty('serviceName', 'KUDU'));
      App.router.get('updateController').set('isWorking', false);
      var popup = App.ModalPopup.show({
        classNames: ['wizard-modal-wrapper'],
        modalDialogClasses: ['modal-xlg'],
        header: Em.I18n.t('admin.addKuduMaster.wizard.header'),
        bodyClass: App.AddKuduMasterWizardView.extend({
          controller: addKuduMasterWizardController
        }),
        primary: Em.I18n.t('form.cancel'),
        showFooter: false,
        secondary: null,

        onClose: function () {
          var wizardController = router.get('addKuduMasterWizardController');
          var currentStep = wizardController.get('currentStep');
          if (parseInt(currentStep) === 4) {
            App.showConfirmationPopup(function () {
              wizardController.resetOnClose(wizardController, 'main.services.index');
            }, Em.I18n.t('admin.addKuduMaster.closePopup'));
          } else {
            wizardController.resetOnClose(wizardController, 'main.services.index');
          }
        },
        didInsertElement: function () {
          this._super();
          this.fitHeight();
        }
      });
      addKuduMasterWizardController.set('popup', popup);
      var currentClusterStatus = App.clusterStatus.get('value');
      if (currentClusterStatus) {
        switch (currentClusterStatus.clusterState) {
          case 'ADD_KUDU_MASTER':
            addKuduMasterWizardController.setCurrentStep(currentClusterStatus.localdb.AddKuduMasterWizard.currentStep);
            break;
          default:
            addKuduMasterWizardController.setCurrentStep(App.router.get('addKuduMasterWizardController.currentStep'));
            break;
        }
      }
      Em.run.next(function () {
        App.router.get('wizardWatcherController').setUser(addKuduMasterWizardController.get('name'));
        router.transitionTo('step' + addKuduMasterWizardController.get('currentStep'));
      });
    });
  },

  step1: Em.Route.extend({
    route: '/step1',
    connectOutlets: function (router) {
      var controller = router.get('addKuduMasterWizardController');
      controller.dataLoading().done(function () {
        controller.setCurrentStep('1');
        controller.connectOutlet('addKuduMasterWizardStep1', controller.get('content'));
      });
    },
    unroutePath: function () {
      return false;
    },
    next: function (router) {
      var controller = router.get('addKuduMasterWizardController');
      controller.setDBProperty('kuduHosts', undefined);
      controller.clearMasterComponentHosts();
      router.transitionTo('step2');
    }
  }),

  step2: Em.Route.extend({
    route: '/step2',
    connectOutlets: function (router) {
      var controller = router.get('addKuduMasterWizardController');
      controller.dataLoading().done(function () {
        controller.setCurrentStep('2');
        controller.loadAllPriorSteps();
        controller.connectOutlet('addKuduMasterWizardStep2', controller.get('content'));
      });
    },
    unroutePath: function () {
      return false;
    },
    next: function (router) {
      var wizardController = router.get('addKuduMasterWizardController');
      var stepController = router.get('addKuduMasterWizardStep2Controller');
      var servicesMasters = stepController.get('servicesMasters').filterProperty('component_name', 'KUDU_MASTER');
      var existingMasters = servicesMasters.filterProperty('isInstalled', true).mapProperty('selectedHost').uniq();
      var newMaster = servicesMasters.findProperty('isInstalled', false);

      if (!newMaster || !newMaster.get('selectedHost')) {
        App.showAlertPopup(Em.I18n.t('common.error'), Em.I18n.t('admin.addKuduMaster.wizard.step2.error.noHost'));
        return;
      }

      wizardController.saveKuduHosts({
        existingKuduMasters: existingMasters,
        newKuduMaster: newMaster.get('selectedHost')
      });
      wizardController.saveMasterComponentHosts(stepController);
      router.transitionTo('step3');
    },
    back: function (router) {
      router.transitionTo('step1');
    }
  }),

  step3: Em.Route.extend({
    route: '/step3',
    connectOutlets: function (router) {
      var controller = router.get('addKuduMasterWizardController');
      controller.dataLoading().done(function () {
        controller.setCurrentStep('3');
        controller.loadAllPriorSteps();
        controller.connectOutlet('addKuduMasterWizardStep3', controller.get('content'));
      });
    },
    unroutePath: function () {
      return false;
    },
    next: function (router) {
      router.transitionTo('step4');
    },
    back: Em.Router.transitionTo('step2')
  }),

  step4: Em.Route.extend({
    route: '/step4',
    connectOutlets: function (router) {
      var controller = router.get('addKuduMasterWizardController');
      controller.dataLoading().done(function () {
        controller.setCurrentStep('4');
        controller.setLowerStepsDisable(4);
        controller.loadAllPriorSteps();
        controller.connectOutlet('addKuduMasterWizardStep4', controller.get('content'));
      });
    },
    unroutePath: function (router, path) {
      if (router.get('addKuduMasterWizardController').get('isFinished')) {
        this._super(router, path);
      } else {
        return false;
      }
    },
    next: function (router) {
      var controller = router.get('addKuduMasterWizardController');
      controller.resetOnClose(controller, 'main.services.index');
    }
  })

});
