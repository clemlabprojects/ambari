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

require('controllers/main/service/info/configs');

App.MainAdminServiceAccountsController = App.MainServiceInfoConfigsController.extend({
  name: 'mainAdminServiceAccountsController',
  users: null,
  serviceConfigTags: [],
  content: Em.Object.create({
    serviceName: 'MISC'
  }),
  loadUsers: function () {
    this.set('selectedService', this.get('content.serviceName') ? this.get('content.serviceName') : "MISC");
    this.loadServiceConfig();
  },
  loadServiceConfig: function () {
    App.router.get('configurationController').getCurrentConfigsBySites().done((serverConfigs) => {
      this.createConfigObject(serverConfigs);
    });
  },

  /**
   * Generate configuration object that will be rendered
   *
   * @param {Object[]} serverConfigs
   */
  createConfigObject: function(serverConfigs) {
    var configs = [];
    serverConfigs.forEach(function(configObject) {
      configs = configs.concat(App.config.getConfigsFromJSON(configObject, true));
    });
    var miscConfigs = configs.filterProperty('displayType', 'user').filterProperty('category', 'Users and Groups');
    miscConfigs.forEach(function (config) {
      config.setProperties({
        isVisible: true,
        savedValue: config.get('value'),
        type: App.config.getConfigTagFromFileName(config.get('filename'))
      });
    });
    var clusterEnv = (serverConfigs.findProperty('type', 'cluster-env') || {}).properties || {};
    this.setProperties({
      serverConfigs: serverConfigs,
      users: miscConfigs,
      manageGroupMemberships: clusterEnv.ignore_groupsusers_create !== 'true',
      manageUids: clusterEnv.override_uid === 'true',
      savedManageGroupMemberships: clusterEnv.ignore_groupsusers_create !== 'true',
      savedManageUids: clusterEnv.override_uid === 'true',
      isEditing: false,
      dataIsLoaded: true
    });
  },

  /**
   * Configurations as loaded from the server, kept to save a type with all its properties.
   * @type {Object[]}
   */
  serverConfigs: [],

  isEditing: false,

  isSaving: false,

  isReadOnly: Em.computed.not('isEditing'),

  /**
   * Inverted <code>cluster-env/ignore_groupsusers_create</code>, as in the install wizard.
   * @type {boolean}
   */
  manageGroupMemberships: true,

  /**
   * <code>cluster-env/override_uid</code>
   * @type {boolean}
   */
  manageUids: true,

  savedManageGroupMemberships: true,

  savedManageUids: true,

  changedUsers: function () {
    return (this.get('users') || []).filter(function (user) {
      return String(user.get('value')).trim() !== String(user.get('savedValue'));
    });
  }.property('users.@each.value'),

  hasChanges: function () {
    return this.get('changedUsers.length') > 0
      || this.get('manageGroupMemberships') !== this.get('savedManageGroupMemberships')
      || this.get('manageUids') !== this.get('savedManageUids');
  }.property('changedUsers.length', 'manageGroupMemberships', 'manageUids',
    'savedManageGroupMemberships', 'savedManageUids'),

  hasInvalidValue: function () {
    return (this.get('users') || []).some(function (user) {
      return !/^[a-z_][a-z0-9_.-]*\$?$/i.test(String(user.get('value')).trim());
    });
  }.property('users.@each.value'),

  isSaveDisabled: function () {
    return !this.get('hasChanges') || this.get('hasInvalidValue') || this.get('isSaving');
  }.property('hasChanges', 'hasInvalidValue', 'isSaving'),

  edit: function () {
    this.set('isEditing', true);
  },

  cancel: function () {
    (this.get('users') || []).forEach(function (user) {
      user.set('value', user.get('savedValue'));
    });
    this.setProperties({
      manageGroupMemberships: this.get('savedManageGroupMemberships'),
      manageUids: this.get('savedManageUids'),
      isEditing: false
    });
  },

  /**
   * Asks for confirmation, listing what changes and what it implies, then saves.
   */
  save: function () {
    var self = this;
    var changes = this.get('changedUsers').map(function (user) {
      return Em.I18n.t('admin.misc.save.change').format(
        user.get('displayName'), user.get('savedValue'), String(user.get('value')).trim());
    });
    if (this.get('manageGroupMemberships') !== this.get('savedManageGroupMemberships')) {
      changes.push(Em.I18n.t('admin.misc.manageGroupMemberships') + ': ' +
        Em.I18n.t(this.get('manageGroupMemberships') ? 'common.enabled' : 'common.disabled'));
    }
    if (this.get('manageUids') !== this.get('savedManageUids')) {
      changes.push(Em.I18n.t('admin.misc.manageUids') + ': ' +
        Em.I18n.t(this.get('manageUids') ? 'common.enabled' : 'common.disabled'));
    }
    var body = '<p>' + Em.I18n.t('admin.misc.save.body') + '</p><ul><li>' +
      changes.map(Handlebars.Utils.escapeExpression).join('</li><li>') + '</li></ul>';
    if (this.get('changedUsers.length')) {
      body += Em.I18n.t('admin.misc.save.userWarning');
    }
    App.showConfirmationPopup(function () {
      self.saveChanges();
    }, body, null, Em.I18n.t('admin.misc.save.header'), Em.I18n.t('common.save'),
      this.get('changedUsers.length') ? 'warning' : 'success');
  },

  /**
   * Saves every changed configuration type with all its current properties, in one request.
   */
  saveChanges: function () {
    var self = this;
    var byType = {};
    var serverConfigs = this.get('serverConfigs') || [];
    var propertiesOf = function (type) {
      if (!byType[type]) {
        var current = serverConfigs.findProperty('type', type) || {};
        byType[type] = {
          type: type,
          properties: $.extend({}, current.properties),
          properties_attributes: $.extend(true, {}, current.properties_attributes),
          service_config_version_note: Em.I18n.t('admin.misc.save.note')
        };
      }
      return byType[type].properties;
    };
    this.get('changedUsers').forEach(function (user) {
      propertiesOf(user.get('type'))[user.get('name')] = String(user.get('value')).trim();
    });
    if (this.get('manageGroupMemberships') !== this.get('savedManageGroupMemberships')) {
      propertiesOf('cluster-env').ignore_groupsusers_create = String(!this.get('manageGroupMemberships'));
    }
    if (this.get('manageUids') !== this.get('savedManageUids')) {
      propertiesOf('cluster-env').override_uid = String(this.get('manageUids'));
    }
    var desiredConfigs = Object.keys(byType).map(function (type) {
      return JSON.stringify({Clusters: {desired_config: [byType[type]]}});
    });
    this.set('isSaving', true);
    App.ajax.send({
      name: 'common.across.services.configurations',
      sender: this,
      data: {
        data: '[' + desiredConfigs.join(',') + ']'
      }
    }).done(function () {
      // the configurations are read through the cached tags: refresh them to show what was saved
      App.router.get('configurationController').updateConfigTags().always(function () {
        self.loadServiceConfig();
      });
    }).always(function () {
      self.set('isSaving', false);
    });
  },

  /**
   * sort miscellaneous configs by specific order
   * @param sortOrder
   * @param arrayToSort
   * @return {Array}
   */
  sortByOrder: function (sortOrder, arrayToSort) {
    var sorted = [];
    if (sortOrder && sortOrder.length > 0) {
      sortOrder.forEach(function (name) {
        var user = arrayToSort.findProperty('name', name);
        if (user) {
          sorted.push({
            isVisible: user.get('isVisible'),
            displayName: user.get('displayName'),
            value: user.get('value')
          });
        }
      });
      return sorted;
    } else {
      return arrayToSort;
    }
  }
});
