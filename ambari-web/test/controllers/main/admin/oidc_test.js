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
require('controllers/main/admin/oidc');

describe('App.MainAdminOidcController', function () {

  var controller;

  beforeEach(function () {
    controller = App.MainAdminOidcController.create({});
  });

  /**
   * Ambari cannot regenerate the client secret of a provisioned OIDC client: a "rotate" request only
   * re-runs provisioning, which reads the current secret back unchanged. The OIDC admin page must not
   * offer an action that operators would take for a credential rotation.
   */
  describe('client secret rotation is not offered', function () {

    it('the controller has no rotate action', function () {
      expect(controller.rotateOidcSecrets).to.be.undefined;
      expect(controller._runRotateRequest).to.be.undefined;
    });

    it('no rotate request is defined', function () {
      expect(App.ajax.fakeGetUrl('admin.oidc.rotate.secrets')).to.be.undefined;
    });

    it('no rotate label or popup text is defined', function () {
      expect(Em.I18n.translations['admin.oidc.button.rotateSecrets']).to.be.undefined;
      expect(Em.I18n.translations['admin.oidc.rotate.popup.header']).to.be.undefined;
      expect(Em.I18n.translations['admin.oidc.rotate.popup.body']).to.be.undefined;
    });
  });

  describe('OIDC admin page actions', function () {

    var view;
    var actionNames;
    var servicesConfigView;
    var enableToggleKerberos;

    beforeEach(function () {
      // Render the page as an authorized user on a secured cluster, the case in which every
      // management button is shown, and record the action of each button.
      servicesConfigView = App.ServicesConfigView;
      App.ServicesConfigView = Em.View.extend({
        template: Em.Handlebars.compile('')
      });
      enableToggleKerberos = App.get('supports.enableToggleKerberos');
      App.set('supports.enableToggleKerberos', true);
      sinon.stub(App, 'isAuthorized').returns(true);
      sinon.spy(Em.Handlebars.ActionHelper, 'registerAction');

      view = Em.View.create({
        templateName: require('templates/main/admin/oidc'),
        controller: Em.Object.create({
          dataIsLoaded: true,
          securityEnabled: true,
          isOidcEnabled: true,
          isKerberosButtonsDisabled: false,
          isEditMode: false,
          oidcClientsContent: []
        })
      });
      Em.run(function () {
        view.appendTo('#wrapper');
      });
      actionNames = Em.Handlebars.ActionHelper.registerAction.args.map(function (args) {
        return args[0];
      });
    });

    afterEach(function () {
      Em.run(function () {
        view.destroy();
      });
      Em.Handlebars.ActionHelper.registerAction.restore();
      App.isAuthorized.restore();
      App.set('supports.enableToggleKerberos', enableToggleKerberos);
      App.ServicesConfigView = servicesConfigView;
    });

    it('shows the provisioning and credential actions', function () {
      expect(actionNames).to.include('runOidcProvisioning');
      expect(actionNames).to.include('showManageOIDCCredentialsPopup');
    });

    it('shows no rotate action', function () {
      expect(actionNames).to.not.include('rotateOidcSecrets');
    });
  });
});
