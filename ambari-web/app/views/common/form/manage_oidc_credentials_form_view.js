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
require('views/common/form/manage_credentials_form_view');

App.ManageOidcCredentialsFormView = App.ManageCredentialsFormView.extend({
  templateName: require('templates/common/form/manage_oidc_credentials_form'),
  viewName: 'manageOidcCredentialsForm',

  formHeader: Em.computed.ifThenElse(
    'isRemovable',
    Em.I18n.t('admin.oidc.credentials.form.header.stored'),
    Em.I18n.t('admin.oidc.credentials.form.header.not.stored')
  ),

  prepareContent: function() {
    var self = this;
    credentialsUtils.credentials(App.get('clusterName'), function(credentials) {
      Em.run.next(function() {
        self.set(
          'isRemovable',
          credentialsUtils.isCredentialsPersistedByAlias(credentials, credentialsUtils.ALIAS.OIDC_CREDENTIALS)
        );
        self.set('isRemoveDisabled', !self.get('isRemovable'));
      });
    });
  },

  saveOIDCCredentials: function () {
    var self = this;
    var dfd = $.Deferred();

    this.setInProgress(true);
    credentialsUtils.createOrUpdateCredentials(
      App.get('clusterName'),
      credentialsUtils.ALIAS.OIDC_CREDENTIALS,
      credentialsUtils.createCredentialResource(this.get('principal'), this.get('password'), this.get('storageType')))
      .always(function() {
        self.setInProgress(false);
        self.prepareContent();
        self.set('actionStatus', Em.I18n.t('common.success'));
        self.get('parentView').set('isCredentialsSaved', true);
        dfd.resolve();
      });
    return dfd.promise();
  },

  removeOIDCCredentials: function() {
    var t = Em.I18n.t;
    var self = this;
    var dfd = $.Deferred();
    this.set('actionStatus', false);
    var popup = App.showConfirmationPopup(
      function() {
        self.setInProgress(true);
        credentialsUtils.removeCredentials(App.get('clusterName'), credentialsUtils.ALIAS.OIDC_CREDENTIALS)
          .always(function() {
            self.setInProgress(false);
            self.prepareContent();
            self.set('actionStatus', Em.I18n.t('common.success'));
            self.get('parentView').set('isCredentialsRemoved', true);
            dfd.resolve();
          });
      }, t('admin.oidc.credentials.remove.confirmation.body'),
      function () {},
      null,
      t('yes'));
    popup.set('secondary', t('no'));
    return {
      deferred: dfd,
      popup: popup
    };
  }
});
