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
package org.apache.ambari.view.k8s.migration;

import java.io.File;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.ambari.view.k8s.store.KdpsSettingEntity;
import org.apache.ambari.view.migration.ViewDataMigrator;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the view-version migration contract in view.xml. Ambari only runs KdpsDataMigrator when the new
 * view declares a different data-version than the previous one; bumping {@code <version>} without
 * {@code <data-version>} silently brings back Ambari's generic copy. Tying data-version to the last number
 * of the version makes forgetting it fail the build.
 */
public class ViewXmlMigrationContractTest {

    @Test
    void dataVersionFollowsTheViewVersionAndTheMigratorIsDeclared() throws Exception {
        File viewXml = new File("src/main/resources/view.xml");
        assertTrue(viewXml.isFile(), "view.xml not found from " + new File(".").getAbsolutePath());
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(viewXml);

        String version = text(doc, "version");
        String dataVersion = text(doc, "data-version");
        String migrator = text(doc, "data-migrator-class");

        String[] parts = version.split("\\.");
        assertEquals(parts[parts.length - 1], dataVersion,
                "view.xml <data-version> must equal the last number of <version> " + version
                        + " so Ambari runs KdpsDataMigrator on the next view version change");
        Class<?> migratorClass = Class.forName(migrator);
        assertTrue(ViewDataMigrator.class.isAssignableFrom(migratorClass), migrator + " is not a ViewDataMigrator");
        assertEquals(KdpsDataMigrator.class, migratorClass);
    }

    @Test
    void theUiFallbackApiPathUsesTheCurrentViewVersion() throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File("src/main/resources/view.xml"));
        String version = text(doc, "version");
        String client = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/resources/ui/src/api/client.ts")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(client.contains("/api/v1/views/K8S-VIEW/versions/" + version + "/instances/"),
                "ui/src/api/client.ts fallback API path must use the view version " + version);
    }

    @Test
    void theSharedInstanceSettingsArePersisted() throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File("src/main/resources/view.xml"));
        NodeList entities = doc.getElementsByTagName("entity");
        String idProperty = null;
        for (int i = 0; i < entities.getLength(); i++) {
            Element entity = (Element) entities.item(i);
            if (KdpsSettingEntity.class.getName().equals(childText(entity, "class"))) {
                idProperty = childText(entity, "id-property");
            }
        }
        assertEquals("id", idProperty, "view.xml <persistence> must declare " + KdpsSettingEntity.class.getName()
                + ": without it the settings shared by all users cannot be saved");
    }

    @Test
    void theJarVersionFollowsTheViewVersion() throws Exception {
        Document viewXml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File("src/main/resources/view.xml"));
        String version = text(viewXml, "version");
        Document pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File("pom.xml"));
        assertEquals(version, childText(pom.getDocumentElement(), "version"), "pom.xml <version> must equal view.xml <version>");

        // The server package ships k8s-view-${kdps.view.version}.jar: a stale value ships no KDPS view at all.
        File serverPom = new File("../../../ambari-server/pom.xml");
        if (serverPom.isFile()) {
            Document server = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(serverPom);
            assertEquals(version, text(server, "kdps.view.version"),
                    "ambari-server/pom.xml <kdps.view.version> must equal view.xml <version>");
        }
    }

    /** The text of the direct child {@code tag} of {@code parent}, or null. */
    private static String childText(Element parent, String tag) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element && tag.equals(child.getNodeName())) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }

    private static String text(Document doc, String tag) {
        assertEquals(1, doc.getElementsByTagName(tag).getLength(), "exactly one <" + tag + "> expected");
        return doc.getElementsByTagName(tag).item(0).getTextContent().trim();
    }
}
