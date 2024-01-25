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
package org.apache.ambari.server.upgrade;

import java.sql.SQLException;

import org.apache.ambari.server.AmbariException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Injector;



/**
 * The {@link UpgradeCatalog278} upgrades Ambari from 2.7.6 to 2.7.8.
 */
public class UpgradeCatalog278 extends AbstractUpgradeCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(UpgradeCatalog278.class);

    @Inject
    public UpgradeCatalog278(Injector injector) {
        super(injector);
    }

    @Override
    public String getSourceVersion() {
        return "2.7.6";
    }

    @Override
    public String getTargetVersion() {
        return "2.7.8";
    }

    @Override
    protected void executeDDLUpdates() throws AmbariException, SQLException {
        // no actions needed
    }

    @Override
    protected void executePreDMLUpdates() throws AmbariException, SQLException {
    }

    @Override
    protected void executeDMLUpdates() throws AmbariException, SQLException {
    }


}
