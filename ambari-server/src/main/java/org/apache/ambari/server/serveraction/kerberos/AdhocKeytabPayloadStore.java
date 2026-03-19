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

package org.apache.ambari.server.serveraction.kerberos;

import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.security.credential.Credential;
import org.apache.ambari.server.security.credential.GenericKeyCredential;
import org.apache.ambari.server.security.encryption.CredentialStoreService;
import org.apache.ambari.server.security.encryption.CredentialStoreType;
import org.apache.commons.lang.StringUtils;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Stores ad-hoc keytab payloads in Ambari's temporary credential store so they
 * can be consumed once without persisting raw keytab bytes in request history.
 */
@Singleton
public class AdhocKeytabPayloadStore {

  private static final String ALIAS_PREFIX = "adhoc.keytab.payload.";

  private final CredentialStoreService credentialStoreService;

  @Inject
  public AdhocKeytabPayloadStore(CredentialStoreService credentialStoreService) {
    this.credentialStoreService = credentialStoreService;
  }

  public synchronized String storePayload(String clusterName, byte[] payloadBytes) throws AmbariException {
    if (StringUtils.isBlank(clusterName)) {
      throw new IllegalArgumentException("clusterName cannot be null or blank");
    }
    if ((payloadBytes == null) || (payloadBytes.length == 0)) {
      throw new IllegalArgumentException("payloadBytes cannot be null or empty");
    }

    String payloadRef = UUID.randomUUID().toString();
    String payloadBase64 = Base64.getEncoder().encodeToString(payloadBytes);
    credentialStoreService.setCredential(clusterName, aliasFor(payloadRef),
        new GenericKeyCredential(payloadBase64.toCharArray()), CredentialStoreType.TEMPORARY);
    return payloadRef;
  }

  public synchronized Optional<String> readPayload(String clusterName, String payloadRef) throws AmbariException {
    if (StringUtils.isBlank(clusterName) || StringUtils.isBlank(payloadRef)) {
      return Optional.empty();
    }

    Credential credential = credentialStoreService.getCredential(clusterName, aliasFor(payloadRef), CredentialStoreType.TEMPORARY);
    if (credential == null) {
      return Optional.empty();
    }

    char[] value = credential.toValue();
    return Optional.of(value == null ? "" : new String(value));
  }

  public synchronized boolean ackPayload(String clusterName, String payloadRef) throws AmbariException {
    if (StringUtils.isBlank(clusterName) || StringUtils.isBlank(payloadRef)) {
      return false;
    }

    String alias = aliasFor(payloadRef);
    boolean present = credentialStoreService.getCredential(clusterName, alias, CredentialStoreType.TEMPORARY) != null;
    if (present) {
      credentialStoreService.removeCredential(clusterName, alias, CredentialStoreType.TEMPORARY);
    }
    return present;
  }

  public synchronized Optional<String> consumePayload(String clusterName, String payloadRef) throws AmbariException {
    Optional<String> payload = readPayload(clusterName, payloadRef);
    if (payload.isPresent()) {
      ackPayload(clusterName, payloadRef);
    }
    return payload;
  }

  static String aliasFor(String payloadRef) {
    return ALIAS_PREFIX + StringUtils.defaultString(payloadRef).toLowerCase(Locale.ROOT);
  }
}
