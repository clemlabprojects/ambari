/*
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

package org.apache.ambari.server.serveraction.oidc;

import org.apache.ambari.server.security.credential.PrincipalKeyCredential;
import org.apache.ambari.server.state.oidc.OidcClientDescriptor;

/**
 * Placeholder for Okta OIDC provider support.
 * <p>
 * All operations throw {@link OidcOperationException} until a full implementation is provided.
 * </p>
 */
public class OktaOidcOperationHandler implements OidcOperationHandler {

  private static final String NOT_SUPPORTED =
      "Okta OIDC provider is not yet supported. Contribute an implementation via the Ambari community.";

  @Override
  public void open(PrincipalKeyCredential administratorCredential,
                   OidcProviderConfiguration configuration) throws OidcOperationException {
    throw new OidcOperationException(NOT_SUPPORTED);
  }

  @Override
  public OidcClientResult ensureClient(OidcClientDescriptor descriptor,
                                       String realm) throws OidcOperationException {
    throw new OidcOperationException(NOT_SUPPORTED);
  }

  @Override
  public void deleteClient(OidcClientDescriptor descriptor,
                           String realm) throws OidcOperationException {
    throw new OidcOperationException(NOT_SUPPORTED);
  }

  @Override
  public void close() throws OidcOperationException {
  }
}
