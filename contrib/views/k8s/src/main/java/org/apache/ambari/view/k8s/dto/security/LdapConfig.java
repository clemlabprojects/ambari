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

package org.apache.ambari.view.k8s.dto.security;

/**
 * LDAP / AD configuration.
 */
public class LdapConfig {
    public String url;
    public String bindDn;
    public String bindPassword;
    public String userDnTemplate;
    public String userSearchFilter;
    public String baseDn;
    public String groupSearchBase;
    public String groupSearchFilter;
    public String groupAuthPattern;
    public String referral;
    public boolean startTls;
    public String adUrl;
    public String adBaseDn;
    public String adBindDn;
    public String adBindPassword;
    public String adUserSearchFilter;
    public String adDomain;
}
