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
var credentialsUtils = require('utils/credentials');

describe('App.OidcEnableWizardStep3Controller', function () {
  var controller;
  var content;

  beforeEach(function () {
    content = Em.Object.create({
      adminUsername: 'admin',
      adminPassword: 'secret'
    });
    controller = App.OidcEnableWizardStep3Controller.create({
      wizardController: Em.Object.create({content: content}),
      isSubmitDisabled: true
    });
  });

  describe('#_storeCredentials()', function () {
    var storePersisted;
    var storeResult;

    beforeEach(function () {
      storePersisted = $.Deferred();
      storeResult = $.Deferred();
      sinon.stub(credentialsUtils, 'isStorePersisted').returns(storePersisted.promise());
      sinon.stub(credentialsUtils, 'createOrUpdateCredentials').returns(storeResult.promise());
      sinon.stub(App, 'showAlertPopup');
      sinon.stub(controller, '_runProvisioning');
    });

    afterEach(function () {
      credentialsUtils.isStorePersisted.restore();
      credentialsUtils.createOrUpdateCredentials.restore();
      App.showAlertPopup.restore();
      controller._runProvisioning.restore();
    });

    describe('persistent credential store available', function () {

      beforeEach(function () {
        controller._storeCredentials(content, 'c1');
        storePersisted.resolve(true);
        storeResult.resolve(true);
      });

      it('checks the store type of the right cluster', function () {
        expect(credentialsUtils.isStorePersisted.calledWith('c1')).to.be.true;
      });

      it('stores the credential as persisted', function () {
        expect(credentialsUtils.createOrUpdateCredentials.getCall(0).args).to.eql([
          'c1',
          'oidc.admin.credential',
          {principal: 'admin', key: 'secret', type: 'persisted'}
        ]);
      });

      it('shows no warning', function () {
        expect(App.showAlertPopup.called).to.be.false;
      });

      it('starts provisioning', function () {
        expect(controller._runProvisioning.calledWith('c1')).to.be.true;
      });
    });

    describe('only the temporary credential store available', function () {

      beforeEach(function () {
        controller._storeCredentials(content, 'c1');
        storePersisted.resolve(false);
        storeResult.resolve(true);
      });

      it('stores the credential as temporary', function () {
        expect(credentialsUtils.createOrUpdateCredentials.getCall(0).args[2].type).to.equal('temporary');
      });

      it('warns that the credential is temporary', function () {
        var args = App.showAlertPopup.getCall(0).args;
        expect(args[0]).to.equal(Em.I18n.t('admin.oidc.credentials.temporary.header'));
        expect(args[1]).to.equal(Em.I18n.t('admin.oidc.credentials.temporary.body'));
      });

      it('waits for the warning to be acknowledged before provisioning', function () {
        expect(controller._runProvisioning.called).to.be.false;
        App.showAlertPopup.getCall(0).args[2]();
        expect(controller._runProvisioning.calledWith('c1')).to.be.true;
      });
    });

    describe('credential store information cannot be read', function () {

      beforeEach(function () {
        controller._storeCredentials(content, 'c1');
        storePersisted.reject();
        storeResult.resolve(true);
      });

      it('falls back to the temporary store and warns', function () {
        expect(credentialsUtils.createOrUpdateCredentials.getCall(0).args[2].type).to.equal('temporary');
        expect(App.showAlertPopup.getCall(0).args[0]).to.equal(Em.I18n.t('admin.oidc.credentials.temporary.header'));
      });
    });

    describe('credential store write fails', function () {

      beforeEach(function () {
        controller._storeCredentials(content, 'c1');
        storePersisted.resolve(true);
        storeResult.resolve(false, 'Internal Server Error');
      });

      it('does not start provisioning', function () {
        expect(controller._runProvisioning.called).to.be.false;
      });

      it('shows the store error', function () {
        var args = App.showAlertPopup.getCall(0).args;
        expect(args[0]).to.equal(Em.I18n.t('admin.oidc.credentials.store.error.header'));
        expect(args[1]).to.equal(Em.I18n.t('admin.oidc.credentials.store.error.body') + ' ' +
          Em.I18n.t('admin.oidc.credentials.store.error.reason').format('Internal Server Error'));
      });

      it('lets the user try again', function () {
        expect(controller.get('isSubmitDisabled')).to.be.false;
      });
    });

    describe('credential store request is rejected', function () {

      beforeEach(function () {
        controller._storeCredentials(content, 'c1');
        storePersisted.resolve(true);
        storeResult.reject();
      });

      it('does not start provisioning and shows the store error', function () {
        expect(controller._runProvisioning.called).to.be.false;
        expect(App.showAlertPopup.getCall(0).args).to.eql([
          Em.I18n.t('admin.oidc.credentials.store.error.header'),
          Em.I18n.t('admin.oidc.credentials.store.error.body')
        ]);
        expect(controller.get('isSubmitDisabled')).to.be.false;
      });
    });
  });
});
