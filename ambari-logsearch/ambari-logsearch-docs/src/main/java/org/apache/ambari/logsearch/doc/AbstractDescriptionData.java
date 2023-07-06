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
package org.apache.ambari.logsearch.doc;

public abstract class AbstractDescriptionData {

  private final String description;
  private final String defaultValue;
  private final String examples;

  public AbstractDescriptionData(String description, String defaultStr, String[] examplesArr) {
    this.description = description;
    examples = generateExamplesString(examplesArr);
    defaultValue = generateDefaultValue(defaultStr);
  }

  protected String generateDefaultValue(String defaultValue) {
    if (defaultValue == null || defaultValue.length() == 0) {
      return "`EMPTY`";
    } else {
      return defaultValue;
    }
  }

  protected String generateExamplesString(String[] examples) {
    if (examples == null) {
      return "";
    } else {
      final StringBuilder stringBuilder = new StringBuilder();
      if(examples.length > 0){
        stringBuilder.append("<ul>");
        for( String example : examples){
          stringBuilder.append("<li>").append("`").append(example).append("`").append("</li>");
        }
        stringBuilder.append("</ul>");
      }
      return stringBuilder.toString();
    }
  }

  public String getDescription() {
    return description;
  }

  public String getDefaultValue() {
    return defaultValue;
  }

  public String getExamples() {
    return examples;
  }
}
