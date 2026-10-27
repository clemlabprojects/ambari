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
require('views/main/admin/oidc/provision_view');

module.exports = Em.Route.extend({
  route: '/provision',

  enter: function(router) {
    App.router.get('updateController').set('isWorking', false);
    router.get('mainController').dataLoading().done(function() {
      App.ModalPopup.show({
        classNames: ['wizard-modal-wrapper', 'provision-oidc-modal'],
        modalDialogClasses: ['modal-xlg'],
        header: Em.I18n.t('admin.oidc.provision.header'),
        bodyClass: App.OidcProvisionView.extend({
          controllerBinding: 'App.router.oidcProvisionController'
        }),
        primary: Em.I18n.t('common.complete'),
        secondary: null,
        disablePrimary: Em.computed.alias('App.router.oidcProvisionController.isSubmitDisabled'),

        onPrimary: function() {
          this.onClose();
        },

        onClose: function() {
          var self = this;
          var controller = router.get('oidcProvisionController');
          if (!controller.get('isSubmitDisabled')) {
            self.proceedOnClose();
            return;
          }
          var provisionTask = controller.get('tasks').findProperty('command', 'provisionOidcClients') || Em.Object.create();
          var isProvisionInProgress = provisionTask.get('status') === 'IN_PROGRESS';
          if (controller.get('tasks').everyProperty('status', 'COMPLETED')) {
            self.proceedOnClose();
            return;
          }
          if (isProvisionInProgress) {
            App.showAlertPopup(
              Em.I18n.t('admin.oidc.provision.inProgress.header'),
              Em.I18n.t('admin.oidc.provision.inProgress.message'));
            return;
          }
          App.showConfirmationPopup(function() {
            self.proceedOnClose();
          }, Em.I18n.t('admin.oidc.provision.onClose'));
        },

        proceedOnClose: function() {
          var self = this;
          var provisionController = router.get('oidcProvisionController');
          provisionController.clearStep();
          provisionController.resetDbNamespace();
          App.router.get('updateController').set('isWorking', true);
          App.clusterStatus.setClusterStatus({
            clusterName: App.get('clusterName'),
            clusterState: 'DEFAULT',
            localdb: App.db.data
          }, {
            alwaysCallback: function() {
              self.hide();
              router.transitionTo('main.admin.adminOidc.index');
              Em.run.next(function() {
                location.reload();
              });
            }
          });
        },

        didInsertElement: function() {
          this._super();
          this.fitHeight();
        }
      });
    });
  },

  unroutePath: function() {
    return false;
  },

  done: function(router, context) {
    var controller = router.get('oidcProvisionController');
    if (!controller.get('isSubmitDisabled')) {
      $(context.currentTarget).parents('#modal').find('.close').trigger('click');
    }
  }
});
