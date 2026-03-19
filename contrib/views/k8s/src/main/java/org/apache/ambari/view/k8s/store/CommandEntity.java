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
import javax.persistence.Lob;
import javax.persistence.Table;
import java.util.Map;

@Entity
@Table(name = "k8s_commands")
public class CommandEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 128)
    private String viewInstance;

    @Column(length = 64)
    private String CommandStatusId; // if Command StatusId is null then it means we are in the root command

    @Column(length = 32, nullable = false)
    private String type;             // enum name

    @Column(length = 512)
    private String title;

    @Lob
    private String paramsJson;

    @Lob
    private String childListJson; // contains a list of child Commands; if empty we are in the end of the list not the root

    public String getId() {
        return id;
    }
    public void setId(String id) {
        this.id = id;
    }
    public String getViewInstance() {
        return viewInstance;
    }
    public void setViewInstance(String viewInstance) {
        this.viewInstance = viewInstance;
    }
    public String getType(){
        return this.type;
    }
    public void setType(String type){
        this.type = type;
    }
    public void setParamsJson(String params){
        this.paramsJson = params;
    }
    public String getCommandStatusId(){
        return this.CommandStatusId;
    }
    public void setCommandStatusId(String statusId){
        this.CommandStatusId = statusId;
    }
    public void setTitle(String title){
        this.title = title;
    }
    public void setChildListJson(String childListJson){
        this.childListJson = childListJson;
    }
    public String getChildListJson(){
        return this.childListJson;
    }
    public String getParamsJson(){
        return this.paramsJson;
    }
    public String getTitle(){
        return this.title;
    }

}