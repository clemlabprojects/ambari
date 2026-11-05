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

package org.apache.ambari.view.k8s.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The kinit Ambari server runs to check a stored keytab against a platform context's KDC: the context's krb5.conf and
 * principal are typed in the view, the kinit runs as Ambari server.
 */
class CommandServiceKinitTest {

    @Test
    void theContextsKrb5ConfLosesWhatReadsOtherFilesOrLoadsCode() {
        String conf = String.join("\n",
                "includedir /etc/krb5.conf.d/",
                "include /home/someone/evil.conf",
                "[libdefaults]",
                "  default_realm = CORP.EXAMPLE.COM",
                "  plugin_base_dir = /tmp/evil",
                "[realms]",
                "  CORP.EXAMPLE.COM = {",
                "    kdc = dc01.corp.example.com",
                "  }",
                "[plugins]",
                "  ccselect = {",
                "    module = evil:/tmp/evil.so",
                "  }",
                "[domain_realm]",
                "  .corp.example.com = CORP.EXAMPLE.COM");

        String kept = CommandService.krb5ConfForKinit(conf);

        assertFalse(kept.contains("include"), kept);
        assertFalse(kept.contains("evil"), kept);
        assertFalse(kept.contains("[plugins]"), kept);
        assertTrue(kept.contains("default_realm = CORP.EXAMPLE.COM"), kept);
        assertTrue(kept.contains("kdc = dc01.corp.example.com"), kept);
        assertTrue(kept.contains(".corp.example.com = CORP.EXAMPLE.COM"), kept);
    }

    @Test
    void aPrincipalKinitWouldTakeForAnOptionIsNotChecked() {
        assertNull(CommandService.kinitWithKeytab("[libdefaults]\n", new byte[]{5, 2}, "-V@CORP.EXAMPLE.COM"));
        assertNull(CommandService.kinitWithKeytab("[libdefaults]\n", new byte[]{5, 2}, "superset kdc@CORP.EXAMPLE.COM"));
        assertTrue(CommandService.KINIT_PRINCIPAL.matcher("superset-dashboard-bi@CORP.EXAMPLE.COM").matches());
        assertTrue(CommandService.KINIT_PRINCIPAL.matcher("HTTP/trino.bi.svc@CORP.EXAMPLE.COM").matches());
    }
}
