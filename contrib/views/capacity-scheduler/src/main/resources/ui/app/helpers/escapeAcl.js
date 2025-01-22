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


Ember.Handlebars.helper('escapeACL', function(value) {
  var output = '';

  value = Handlebars.Utils.escapeExpression(value || '');

  if (value.trim() == '') {
    output = '<span class="label label-danger"> <i class="fa fa-ban fa-fw"></i>  Nobody </span> ';
  } else if (value.trim() == '*') {
    output = '<label class="label label-success"> <i class="fa fa-asterisk fa-fw"></i> Anyone</label>';
  } else {
    var ug = value.split(' ');
    var users = ug[0].split(',')||[];
    var groups = (ug.length == 2)?ug[1].split(',')||[]:[];

    output += ' <span class="users"> ';

    users.forEach(function (user) {
      output += (user)?'<span class="label label-primary"><i class="fa fa-user fa-fw"></i> '+ user +'</span> ':'';
    });

    output += ' </span> <span class="groups"> ';

    groups.forEach(function (group) {
      output += (group)?'<span class="label label-primary"><i class="fa fa-users fa-fw"></i> '+ group +'</span> ':'';
    });

    output += ' </span> ';
  }
  return new Ember.Handlebars.SafeString(output);
});
