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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OIDC configuration.
 */
public class OidcConfig {
    /** "internal" = Ambari auto-registers in Keycloak per service; "external" = admin-supplied credentials. */
    public String source = "internal";
    public String issuerUrl;
    public String clientId;
    public String clientSecret;
    /**
     * External source only: the audience the provider puts in the access tokens it issues for this client, which a
     * service checking the audience of the JWTs it accepts (Trino) then requires. Blank means the client id; set it
     * for a provider that issues another audience, such as an application ID URI ({@code api://...}).
     */
    public String audience;
    public String scopes;
    public String redirectUri;
    public String userClaim;
    public String groupsClaim;
    public Boolean skipTlsVerify;
    public String caSecret;
    /**
     * Email domain the IdP issues for human users (e.g. "example.com").
     * Bootstrapped from {@code oidc-env/oidc_principal_domain}. Consumed by services
     * (e.g. OpenMetadata) that need to bootstrap admin user records with matching
     * email addresses to avoid "Account already exists" on first OIDC login.
     */
    public String principalDomain;
    /**
     * On a read of the profiles, whether a client secret is stored: the secret itself is never sent back (see
     * {@code SecurityProfileService#loadMaskedProfiles}). Never stored with the profile.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Boolean clientSecretSet;
}
