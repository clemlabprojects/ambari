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

package org.apache.ambari.view.k8s.service;

import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.PersistenceException;
import org.apache.ambari.view.k8s.model.ContextDTO;
import org.apache.ambari.view.k8s.model.ContextRequest;
import org.apache.ambari.view.k8s.model.ResolvedContext;
import org.apache.ambari.view.k8s.store.InMemoryDataStore;
import org.apache.ambari.view.k8s.store.KdpsContextBlobEntity;
import org.apache.ambari.view.k8s.store.KdpsContextEntity;
import org.apache.ambari.view.k8s.store.PerUserViewContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;

/**
 * A platform context carries files pasted whole into its form: core-site, hdfs-site and hive-site XML, a krb5.conf
 * and the CA chain of its Ranger. They are far longer than the 3000 characters the DataStore keeps in one column,
 * so the context keeps them apart, in parts, and gives them back whole to the edit form and to every deploy.
 */
class ContextServiceLargeFieldsTest {

    @TempDir
    Path workDir;

    private PerUserViewContext view;
    private LimitedDataStore store;
    private ContextService contexts;

    @BeforeEach
    void setUp() {
        view = new PerUserViewContext("kdps", workDir);
        view.as("admin");
        store = new LimitedDataStore(view.dataStore);
        doReturn(store).when(view.context).getDataStore();
        contexts = new ContextService(view.context);
    }

    @Test
    void aLargeHdfsSiteACoreSiteAKrb5ConfAndACaChainAreSavedAndReadBackWhole() {
        String hdfsSite = siteXml("dfs.namenode.rpc-address.ns1.nn", 15 * 1024);
        String coreSite = siteXml("hadoop.security.auth_to_local", 4 * 1024);
        String caChain = pem("ROOT", 2048) + pem("INTERMEDIATE", 2048);
        String krb5 = "[libdefaults]\n  default_realm = BANK.LOCAL\n  dns_lookup_kdc = false\n";

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("rangerUrl", "https://ranger.bank.local:6182/?a=b&c='d'");
        config.put("realm", "bank.local");
        config.put("hdfsSiteXml", hdfsSite);
        config.put("coreSiteXml", coreSite);
        config.put("caCert", caChain);
        config.put("krb5Conf", krb5);
        KdpsContextEntity saved = contexts.save(context("ext", config));

        // The row itself stays small, and is written without HTML escapes.
        KdpsContextEntity row = view.dataStore.find(KdpsContextEntity.class, "ext");
        assertTrue(row.getConfigJson().length() <= 3000, row.getConfigJson());
        assertFalse(row.getConfigJson().contains("hdfsSiteXml"));
        assertTrue(row.getConfigJson().contains("?a=b&c='d'"), row.getConfigJson());

        // The edit form and the API get every field back as it was entered.
        for (KdpsContextEntity read : List.of(saved, contexts.get("ext"))) {
            Map<String, Object> back = ContextDTO.fromEntity(read).config;
            assertEquals(hdfsSite, back.get("hdfsSiteXml"));
            assertEquals(coreSite, back.get("coreSiteXml"));
            assertEquals(caChain, back.get("caCert"));
            assertEquals(krb5, back.get("krb5Conf"));
        }
        ContextDTO listed = contexts.list(null).stream().filter(c -> "ext".equals(c.getId()))
                .map(ContextDTO::fromEntity).findFirst().orElseThrow();
        assertEquals(hdfsSite, listed.config.get("hdfsSiteXml"));

        // A deploy gets the Hadoop client configuration, the krb5.conf and the Ranger CA chain.
        Map<String, String> files = contexts.downloadHadoopClientConfig("ext", List.of("core-site", "hdfs-site"));
        assertEquals(hdfsSite, files.get("hdfs-site.xml"));
        assertEquals(coreSite, files.get("core-site.xml"));
        ResolvedContext rc = contexts.resolve("ext", null, null);
        assertEquals(caChain, rc.getResolvedFields().get("ranger.caCert"));
        assertEquals(krb5, rc.getResolvedFields().get("kerberos.krb5Conf"));
        assertEquals("BANK.LOCAL", rc.getKerberosRealm(), "the realm is spelt as the krb5.conf declares it");
    }

    @Test
    void savingAShorterFileOrNoneRemovesThePartsLeftByTheLongerOne() {
        contexts.save(context("ext", Map.of("hdfsSiteXml", siteXml("a", 15 * 1024), "caCert", pem("ROOT", 4096))));
        int partsBefore = store.blobs().size();
        assertTrue(partsBefore > 6, "the files were cut in parts: " + partsBefore);

        String shorter = siteXml("b", 3500);
        contexts.save(context("ext", Map.of("hdfsSiteXml", shorter)));

        assertEquals(2, store.blobs().size(), "two parts of the shorter hdfs-site, and no CA any more");
        assertEquals(shorter, ContextDTO.fromEntity(contexts.get("ext")).config.get("hdfsSiteXml"));
        assertNull(ContextDTO.fromEntity(contexts.get("ext")).config.get("caCert"));
    }

    @Test
    void deletingAContextDeletesItsFiles() {
        contexts.save(context("ext", Map.of("hdfsSiteXml", siteXml("a", 9000))));
        contexts.save(context("other", Map.of("hdfsSiteXml", siteXml("b", 100))));

        contexts.delete("ext");

        assertNull(contexts.get("ext"));
        assertEquals(1, store.blobs().size(), "only the other context's file is left");
        assertTrue(store.blobs().iterator().next().getId().startsWith("other|"));
    }

    @Test
    void aContextSavedByAnEarlierVersionWithItsFileInTheRowIsStillRead() {
        KdpsContextEntity legacy = new KdpsContextEntity();
        legacy.setId("old");
        legacy.setName("old");
        legacy.setKind(ContextService.KIND_EXTERNAL);
        // Earlier versions escaped '<', '>' and '=' as Gson does by default.
        legacy.setConfigJson("{\"coreSiteXml\":\"\\u003cconfiguration\\u003e\\u003c/configuration\\u003e\","
                + "\"realm\":\"BANK.LOCAL\"}");
        view.dataStore.store(legacy);

        assertEquals("<configuration></configuration>",
                contexts.downloadHadoopClientConfig("old", List.of("core-site")).get("core-site.xml"));
        assertEquals("<configuration></configuration>",
                ContextDTO.fromEntity(contexts.get("old")).config.get("coreSiteXml"));
    }

    @Test
    void aFileTooLongIsRefusedByItsNameAndNothingIsSaved() {
        String tooLong = "x".repeat(ContextService.MAX_LARGE_TEXT_LENGTH + 1);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> contexts.save(context("ext", Map.of("hdfsSiteXml", tooLong))));

        assertTrue(e.getMessage().contains("hdfs-site"), e.getMessage());
        assertNull(view.dataStore.find(KdpsContextEntity.class, "ext"));
        assertTrue(store.blobs().isEmpty());
    }

    @Test
    void aConfigTooLargeForItsRowIsRefusedNamingTheLongestField() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> contexts.save(context("ext", Map.of("atlasUrl", "https://" + "a".repeat(3200)))));

        assertTrue(e.getMessage().contains("atlasUrl"), e.getMessage());
        assertNull(view.dataStore.find(KdpsContextEntity.class, "ext"));
    }
    @Test
    void aStoreThatCannotHoldPastedFilesStillSavesAndDeletesContextsWithoutOne() {
        store.refuseBlobs = true;

        contexts.save(context("ext", Map.of("realm", "BANK.LOCAL")));
        assertNotNull(contexts.get("ext"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> contexts.save(context("ext", Map.of("hdfsSiteXml", siteXml("a", 100)))));
        assertTrue(e.getMessage().contains("hdfs-site"), e.getMessage());
        assertTrue(e.getMessage().contains("cannot store pasted files"), e.getMessage());

        contexts.delete("ext");
        assertNull(contexts.get("ext"));
    }

    // ----- helpers -----

    private static ContextRequest context(String id, Map<String, Object> config) {
        ContextRequest r = new ContextRequest();
        r.id = id;
        r.name = id;
        r.kind = ContextService.KIND_EXTERNAL;
        r.config = new LinkedHashMap<>(config);
        return r;
    }

    /** A site XML file of about {@code size} characters. */
    private static String siteXml(String name, int size) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?>\n<configuration>\n");
        for (int i = 0; xml.length() < size; i++) {
            xml.append("  <property><name>").append(name).append('.').append(i)
                    .append("</name><value>host-").append(i).append(".bank.local:8020</value></property>\n");
        }
        return xml.append("</configuration>\n").toString();
    }

    /** A PEM certificate block of about {@code size} characters. */
    private static String pem(String name, int size) {
        StringBuilder b = new StringBuilder("-----BEGIN CERTIFICATE-----\n");
        while (b.length() < size) {
            b.append((name + "MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw").substring(0, 64)).append('\n');
        }
        return b.append("-----END CERTIFICATE-----\n").toString();
    }

    /**
     * The test DataStore, refusing a String property longer than Ambari's DataStore keeps
     * ({@code DataStoreImpl.MAX_ENTITY_STRING_FIELD_LENGTH}).
     */
    private static final class LimitedDataStore implements DataStore {
        private final InMemoryDataStore delegate;
        /** As Ambari's store does for an entity class the view instance was registered without. */
        boolean refuseBlobs;

        LimitedDataStore(InMemoryDataStore delegate) {
            this.delegate = delegate;
        }

        Collection<KdpsContextBlobEntity> blobs() {
            return delegate.findAll(KdpsContextBlobEntity.class, null);
        }

        @Override
        public void store(Object entity) throws PersistenceException {
            refuseUnregistered(entity.getClass());
            try {
                for (PropertyDescriptor p : Introspector.getBeanInfo(entity.getClass()).getPropertyDescriptors()) {
                    if (p.getReadMethod() != null && p.getPropertyType() == String.class) {
                        Object v = p.getReadMethod().invoke(entity);
                        if (v != null && ((String) v).length() > 3000) {
                            throw new PersistenceException("The value of " + p.getName() + " is too long");
                        }
                    }
                }
            } catch (PersistenceException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            assertNotNull(entity);
            delegate.store(entity);
        }

        private void refuseUnregistered(Class<?> clazz) throws PersistenceException {
            if (refuseBlobs && clazz == KdpsContextBlobEntity.class) {
                throw new PersistenceException("The class " + clazz.getName() + " is not registered as an entity.");
            }
        }

        @Override
        public void remove(Object entity) {
            delegate.remove(entity);
        }

        @Override
        public <T> T find(Class<T> clazz, Object primaryKey) throws PersistenceException {
            refuseUnregistered(clazz);
            return delegate.find(clazz, primaryKey);
        }

        @Override
        public <T> Collection<T> findAll(Class<T> clazz, String whereClause) {
            return delegate.findAll(clazz, whereClause);
        }
    }
}
