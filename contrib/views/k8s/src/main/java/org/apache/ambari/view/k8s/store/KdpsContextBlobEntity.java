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

package org.apache.ambari.view.k8s.store;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * One part of a long text value of a platform context: a pasted site XML file (core-site, hdfs-site, hive-site),
 * a krb5.conf or a CA certificate chain. Such values are far longer than the 3000 characters the DataStore keeps
 * in one String column, so they are kept apart from {@link KdpsContextEntity#getConfigJson()}, cut into parts of
 * at most {@link #PART_LENGTH} characters. Read and written through {@code ContextService} only.
 *
 * <p>The id of a part is {@code <contextId>|<configKey>|<n>}, {@code n} counting from 0. Part 0 also records how
 * many parts the value has, so the value is read back by id alone.</p>
 *
 * <p>Persistence caveats (Ambari DataStore): the entity is mapped by bean introspection, so it keeps to scalar
 * properties.</p>
 */
@Entity
@Table(name = "k8s_context_blob")
public class KdpsContextBlobEntity {

    /** Longest part, safely under the DataStore's 3000-character String column. */
    public static final int PART_LENGTH = 2900;

    @Id
    @Column(length = 255)
    private String id;

    /** This part of the value. */
    @Column(length = 3000)
    private String value;

    /** On part 0, how many parts the value has; {@code null} on the others. */
    private Integer parts;

    public KdpsContextBlobEntity() {
        // required by the DataStore
    }

    public KdpsContextBlobEntity(String id, String value, Integer parts) {
        this.id = id;
        this.value = value;
        this.parts = parts;
    }

    /**
     * The id of one part of a context's value.
     *
     * @param contextId the context
     * @param configKey the config key of the value, e.g. {@code hdfsSiteXml}
     * @param part      the part, counting from 0
     * @return {@code <contextId>|<configKey>|<part>}
     */
    public static String idOf(String contextId, String configKey, int part) {
        return contextId + "|" + configKey + "|" + part;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public Integer getParts() { return parts; }
    public void setParts(Integer parts) { this.parts = parts; }
}
