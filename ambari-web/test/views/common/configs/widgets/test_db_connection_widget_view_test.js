/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
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

describe('App.TestDbConnectionWidgetView', function () {
  var view;

  beforeEach(function () {
    var configs = [
      ['db_name', 'polaris_test'], ['db_user', 'polaris'], ['db_password', 'test-secret'],
      ['quarkus.datasource.jdbc.driver', 'org.postgresql.Driver'],
      ['quarkus.datasource.jdbc.url', 'jdbc:postgresql://database.example.com:5544/polaris_test']
    ].map(function (entry) {
      return Em.Object.create({name: entry[0], value: entry[1],
        filename: entry[0].indexOf('quarkus.') === 0 ? 'polaris-application-properties.xml' : 'polaris-db-properties.xml'});
    });
    view = App.TestDbConnectionWidgetView.create({
      config: Em.Object.create({serviceName: 'POLARIS'}),
      parentView: Em.View.create({content: {serviceName: 'POLARIS'}, service: {serviceName: 'POLARIS'}}),
      controller: Em.Object.create({stepConfigs: [Em.Object.create({serviceName: 'POLARIS', configs: configs})]}),
      db_type: {value: 'POSTGRES'},
      masterHostName: {value: ['polaris1.example.com', 'polaris2.example.com']},
      db_connection_url: configs.findProperty('name', 'quarkus.datasource.jdbc.url'),
      user_name: configs.findProperty('name', 'db_user'),
      user_passwd: configs.findProperty('name', 'db_password'),
      ambariProperties: {}
    });
  });

  afterEach(function () {
    view.get('parentView').destroy();
    view.destroy();
  });

  it('records the Polaris database connection without using another service DB_FLAVOR', function () {
    expect(view.get('preparedDBProperties')).to.eql({
      'quarkus.datasource.jdbc.url': 'jdbc:postgresql://database.example.com:5544/polaris_test',
      db_user: 'polaris',
      db_password: 'test-secret'
    });
  });

  it('checks PostgreSQL from every assigned Polaris server before cluster creation', function () {
    sinon.stub(App.Service, 'find').returns(Em.Object.create({isLoaded: false}));
    sinon.stub(App.ajax, 'send');
    try {
      view.createCustomAction();
      var request = App.ajax.send.firstCall.args[0];
      expect(request.name).to.equal('custom_action.create');
      expect(request.data.filteredHosts).to.eql(['polaris1.example.com', 'polaris2.example.com']);
      expect(request.data.requestInfo.parameters.db_name).to.equal('postgres');
      expect(request.data.requestInfo.parameters.db_connection_url).to.equal(view.get('db_connection_url.value'));
      expect(request.data.requestInfo.parameters.user_name).to.equal('polaris');
    } finally {
      App.Service.find.restore();
      App.ajax.send.restore();
    }
  });

  it('resolves the virtual Polaris source host property from wizard host assignments', function () {
    var required = {
      'jdbc.driver.url': 'polaris-application-properties/quarkus.datasource.jdbc.url',
      'db.type': 'polaris-db-properties/DB_FLAVOR',
      'db.connection.source.host': 'polaris-site/polaris_server_hosts',
      'db.connection.user': 'polaris-db-properties/db_user',
      'db.connection.password': 'polaris-db-properties/db_password'
    };
    view.get('controller.stepConfigs')[0].get('configs').pushObject(Em.Object.create({
      name: 'DB_FLAVOR', value: 'POSTGRES', filename: 'polaris-db-properties.xml'
    }));
    view.get('config').set('stackConfigProperty', {widget: {'required-properties': required}});
    view.get('controller').setProperties({
      wizardController: {content: {masterComponentHosts: [], slaveComponentHosts: []}},
      getComponentHostValue: function () { return ['polaris1.example.com', 'polaris2.example.com']; }
    });
    sinon.stub(App.StackServiceComponent, 'find').returns(Em.Object.create({
      componentName: 'POLARIS_SERVER', displayName: 'Polaris Server', isMultipleAllowed: true
    }));
    sinon.stub(view, 'getAmbariProperties');
    try {
      view.didInsertElement();
      expect(view.get('masterHostName.value')).to.eql(['polaris1.example.com', 'polaris2.example.com']);
      expect(view.get('user_name.value')).to.equal('polaris');
      expect(view.get('db_type.value')).to.equal('POSTGRES');
    } finally {
      view.getAmbariProperties.restore();
      App.StackServiceComponent.find.restore();
    }
  });
});
