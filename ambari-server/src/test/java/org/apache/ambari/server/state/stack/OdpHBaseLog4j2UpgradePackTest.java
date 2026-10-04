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
package org.apache.ambari.server.state.stack;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Cross-stack upgrades merge new config types automatically; same-stack upgrades
 * need an explicit creation task for HBase's new Log4j2 configuration.
 */
@Category({ category.StackUpgradeTest.class })
public class OdpHBaseLog4j2UpgradePackTest {
  private static final String STACK_ROOT = "src/main/resources/stacks/ODP/";
  private static final String CONFIG_ID = "odp_1_3_2_0_create_hbase_log4j2_config";
  private static final String[] PACKS = {
      "upgrade-odp-1.3.xml", "nonrolling-upgrade-odp-1.3.xml"
  };

  @Test
  public void testCrossStackRollingDoesNotUseCreateAndConfigure() throws Exception {
    assertCrossStackPack("upgrade-odp-1.3.xml");
  }

  @Test
  public void testCrossStackExpressDoesNotUseCreateAndConfigure() throws Exception {
    assertCrossStackPack("nonrolling-upgrade-odp-1.3.xml");
  }

  @Test
  public void testSameStackPacksRetainHBaseLog4j2Creation() throws Exception {
    for (String pack : PACKS) {
      Document document = parse("1.3/upgrades/" + pack);
      NodeList tasks = select(document, "//task[@id='" + CONFIG_ID + "']");
      assertEquals(pack, 1, tasks.getLength());
      assertEquals(pack, "create_and_configure", ((Element) tasks.item(0))
          .getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type"));
    }

    Document definitions = parse("1.3/upgrades/config-upgrade.xml");
    NodeList types = select(definitions, "//definition[@id='" + CONFIG_ID + "']/type");
    assertEquals(1, types.getLength());
    assertEquals("hbase-log4j2", types.item(0).getTextContent());
  }

  @Test
  public void testTargetStackProvidesHBaseLog4j2Defaults() throws Exception {
    Document defaults = parse("1.3/services/HBASE/configuration/hbase-log4j2.xml");
    NodeList content = select(defaults, "/configuration/property[name='content']/value");
    assertEquals(1, content.getLength());
    assertTrue(content.item(0).getTextContent().contains("rootLogger ="));
  }

  private void assertCrossStackPack(String pack) throws Exception {
    Document document = parse("1.2/upgrades/" + pack);
    assertEquals("ODP-1.3", document.getElementsByTagName("target-stack").item(0).getTextContent());
    NodeList tasks = document.getElementsByTagName("task");
    for (int i = 0; i < tasks.getLength(); i++) {
      Element task = (Element) tasks.item(i);
      assertFalse(pack + ": create_and_configure rejects cross-stack upgrades (" + task.getAttribute("id") + ")",
          "create_and_configure".equals(task.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type")));
    }
  }

  private Document parse(String relativePath) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    return factory.newDocumentBuilder().parse(new File(STACK_ROOT + relativePath));
  }

  private NodeList select(Document document, String expression) throws Exception {
    return (NodeList) XPathFactory.newInstance().newXPath().evaluate(expression, document, XPathConstants.NODESET);
  }
}
