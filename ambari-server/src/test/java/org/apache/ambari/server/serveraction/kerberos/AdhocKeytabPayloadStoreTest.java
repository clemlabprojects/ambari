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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.security.credential.Credential;
import org.apache.ambari.server.security.credential.GenericKeyCredential;
import org.apache.ambari.server.security.encryption.CredentialStoreService;
import org.apache.ambari.server.security.encryption.CredentialStoreType;
import org.junit.Assert;
import org.junit.Test;

public class AdhocKeytabPayloadStoreTest {

  @Test
  public void testStoreAndConsumePayloadOnce() throws Exception {
    InMemoryCredentialStoreService credentialStoreService = new InMemoryCredentialStoreService();
    AdhocKeytabPayloadStore payloadStore = new AdhocKeytabPayloadStore(credentialStoreService);
    byte[] payloadBytes = "keytab-bytes".getBytes(StandardCharsets.UTF_8);

    String payloadRef = payloadStore.storePayload("c1", payloadBytes);

    Assert.assertTrue(credentialStoreService.containsCredential(
        "c1", AdhocKeytabPayloadStore.aliasFor(payloadRef), CredentialStoreType.TEMPORARY));
    Assert.assertArrayEquals(payloadBytes,
        Base64.getDecoder().decode(payloadStore.consumePayload("c1", payloadRef).orElseThrow()));
    Assert.assertFalse(credentialStoreService.containsCredential(
        "c1", AdhocKeytabPayloadStore.aliasFor(payloadRef), CredentialStoreType.TEMPORARY));
    Assert.assertFalse(payloadStore.consumePayload("c1", payloadRef).isPresent());
  }

  @Test
  public void testReadDoesNotDeleteUntilAck() throws Exception {
    InMemoryCredentialStoreService credentialStoreService = new InMemoryCredentialStoreService();
    AdhocKeytabPayloadStore payloadStore = new AdhocKeytabPayloadStore(credentialStoreService);
    byte[] payloadBytes = "keytab-bytes".getBytes(StandardCharsets.UTF_8);

    String payloadRef = payloadStore.storePayload("c1", payloadBytes);

    Assert.assertArrayEquals(payloadBytes,
        Base64.getDecoder().decode(payloadStore.readPayload("c1", payloadRef).orElseThrow()));
    Assert.assertArrayEquals(payloadBytes,
        Base64.getDecoder().decode(payloadStore.readPayload("c1", payloadRef).orElseThrow()));
    Assert.assertTrue(credentialStoreService.containsCredential(
        "c1", AdhocKeytabPayloadStore.aliasFor(payloadRef), CredentialStoreType.TEMPORARY));

    Assert.assertTrue(payloadStore.ackPayload("c1", payloadRef));
    Assert.assertFalse(credentialStoreService.containsCredential(
        "c1", AdhocKeytabPayloadStore.aliasFor(payloadRef), CredentialStoreType.TEMPORARY));
    Assert.assertFalse(payloadStore.readPayload("c1", payloadRef).isPresent());
    Assert.assertFalse(payloadStore.ackPayload("c1", payloadRef));
  }

  private static final class InMemoryCredentialStoreService implements CredentialStoreService {
    private final Map<String, Credential> credentials = new HashMap<>();

    @Override
    public void setCredential(String clusterName, String alias, Credential credential,
                              CredentialStoreType credentialStoreType) {
      char[] value = credential == null ? null : credential.toValue();
      char[] copy = value == null ? null : java.util.Arrays.copyOf(value, value.length);
      credentials.put(key(clusterName, alias, credentialStoreType), new GenericKeyCredential(copy));
    }

    @Override
    public Credential getCredential(String clusterName, String alias) {
      Credential credential = getCredential(clusterName, alias, CredentialStoreType.TEMPORARY);
      return credential != null ? credential : getCredential(clusterName, alias, CredentialStoreType.PERSISTED);
    }

    @Override
    public Credential getCredential(String clusterName, String alias, CredentialStoreType credentialStoreType) {
      return credentials.get(key(clusterName, alias, credentialStoreType));
    }

    @Override
    public void removeCredential(String clusterName, String alias) {
      removeCredential(clusterName, alias, CredentialStoreType.TEMPORARY);
      removeCredential(clusterName, alias, CredentialStoreType.PERSISTED);
    }

    @Override
    public void removeCredential(String clusterName, String alias, CredentialStoreType credentialStoreType) {
      credentials.remove(key(clusterName, alias, credentialStoreType));
    }

    @Override
    public boolean containsCredential(String clusterName, String alias) {
      return containsCredential(clusterName, alias, CredentialStoreType.TEMPORARY)
          || containsCredential(clusterName, alias, CredentialStoreType.PERSISTED);
    }

    @Override
    public boolean containsCredential(String clusterName, String alias, CredentialStoreType credentialStoreType) {
      return credentials.containsKey(key(clusterName, alias, credentialStoreType));
    }

    @Override
    public CredentialStoreType getCredentialStoreType(String clusterName, String alias) throws AmbariException {
      if (containsCredential(clusterName, alias, CredentialStoreType.TEMPORARY)) {
        return CredentialStoreType.TEMPORARY;
      }
      if (containsCredential(clusterName, alias, CredentialStoreType.PERSISTED)) {
        return CredentialStoreType.PERSISTED;
      }
      throw new AmbariException("Credential not found");
    }

    @Override
    public Map<String, CredentialStoreType> listCredentials(String clusterName) {
      return Collections.emptyMap();
    }

    @Override
    public boolean isInitialized() {
      return true;
    }

    @Override
    public boolean isInitialized(CredentialStoreType credentialStoreType) {
      return true;
    }

    private String key(String clusterName, String alias, CredentialStoreType type) {
      return String.valueOf(type) + ":" + String.valueOf(clusterName) + ":" + String.valueOf(alias);
    }
  }
}
