/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ambari.logsearch.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.swagger.jaxrs.config.BeanConfig;
import io.swagger.models.Swagger;
import io.swagger.models.auth.BasicAuthDefinition;
import io.swagger.util.Yaml;

@Named
public class ApiDocStorage {

  private static final Logger logger = LogManager.getLogger(ApiDocStorage.class);

  private final Map<String, Object> swaggerMap = new ConcurrentHashMap<>();

  @Inject
  private BeanConfig beanConfig;

  @PostConstruct
  private void postConstruct() {
    Thread loadApiDocThread = new Thread("load_swagger_api_doc") {
      @Override
      public void run() {
        logger.info("Start thread to scan REST API doc from endpoints.");
        Swagger swagger = beanConfig.getSwagger();
        swagger.addSecurityDefinition("basicAuth", new BasicAuthDefinition());
        beanConfig.configure(swagger);
        beanConfig.scanAndRead();
        setSwagger(swagger);
        try {
          String yaml = Yaml.mapper().writeValueAsString(swagger);
          StringBuilder b = new StringBuilder();
          String[] parts = yaml.split("\n");
          for (String part : parts) {
            b.append(part);
            b.append("\n");
          }
          setSwaggerYaml(b.toString());
        } catch (Exception e) {
          e.printStackTrace();
        }
        logger.info("Scanning REST API endpoints and generating docs has been successful.");
      }
    };
    loadApiDocThread.setDaemon(true);
    loadApiDocThread.start();
  }

  public Swagger getSwagger() {
    return (Swagger) swaggerMap.get("swaggerObject");
  }

  public void setSwagger(final Swagger swagger) {
    swaggerMap.put("swaggerObject", swagger);
  }

  public void setSwaggerYaml(final String swaggerYaml) {
    swaggerMap.put("swaggerYaml", swaggerYaml);
  }

  public String getSwaggerYaml() {
    return (String) swaggerMap.get("swaggerYaml");
  }

}
