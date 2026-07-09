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
 * Global security configuration profile.
 */
public class SecurityConfigDTO {
    public String mode; // none | ldap | ad | oidc
    public LdapConfig ldap;
    public OidcConfig oidc;
    public TlsConfig tls;
    public java.util.Map<String,Object> extraProperties;
    /**
     * Non-null when this profile was auto-derived from a KDPS platform context (its value is the
     * context id). KDPS owns the lifecycle of such profiles — it upserts one when a context that
     * carries OIDC is created/updated and prunes it when the context is deleted or loses OIDC —
     * so operator-authored profiles (this field null) are never touched by the reconcile.
     */
    public String derivedFromContext;
    /** Human-readable label shown in the Step-1 picker; for a derived profile this is the context name. */
    public String displayName;
}
