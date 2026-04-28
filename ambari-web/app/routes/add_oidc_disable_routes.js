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
require('views/main/admin/oidc/disable_view');

module.exports = Em.Route.extend({
  route: '/disable',

  enter: function(router) {
    App.router.get('updateController').set('isWorking', false);
    router.get('mainController').dataLoading().done(function() {
      App.ModalPopup.show({
        classNames: ['wizard-modal-wrapper', 'disable-oidc-modal'],
        modalDialogClasses: ['modal-xlg'],
        header: Em.I18n.t('admin.oidc.disable.header'),
        bodyClass: App.OidcDisableView.extend({
          controllerBinding: 'App.router.oidcDisableController'
        }),
        primary: Em.I18n.t('common.complete'),
        secondary: null,
        disablePrimary: Em.computed.alias('App.router.oidcDisableController.isSubmitDisabled'),

        onPrimary: function() {
          this.onClose();
        },

        onClose: function() {
          var self = this;
          var controller = router.get('oidcDisableController');
          if (!controller.get('isSubmitDisabled')) {
            self.proceedOnClose();
            return;
          }
          var deleteTask = controller.get('tasks').findProperty('command', 'deleteOidcClients') || Em.Object.create();
          var isDeleteInProgress = deleteTask.get('status') === 'IN_PROGRESS';
          if (controller.get('tasks').everyProperty('status', 'COMPLETED')) {
            self.proceedOnClose();
            return;
          }
          if (isDeleteInProgress) {
            App.showAlertPopup(
              Em.I18n.t('admin.oidc.disable.inProgress.header'),
              Em.I18n.t('admin.oidc.disable.inProgress.message'));
            return;
          }
          App.showConfirmationPopup(function() {
            self.proceedOnClose();
          }, Em.I18n.t('admin.oidc.disable.onClose'));
        },

        proceedOnClose: function() {
          var self = this;
          var disableController = router.get('oidcDisableController');
          disableController.clearStep();
          disableController.resetDbNamespace();
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
    var controller = router.get('oidcDisableController');
    if (!controller.get('isSubmitDisabled')) {
      $(context.currentTarget).parents('#modal').find('.close').trigger('click');
    }
  }
});
