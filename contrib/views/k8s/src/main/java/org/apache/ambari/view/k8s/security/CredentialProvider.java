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
package org.apache.ambari.view.k8s.security;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ambari.view.k8s.security.AmbariException;
import org.apache.ambari.view.k8s.security.Credential;
import org.apache.ambari.view.k8s.security.GenericKeyCredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CredentialProvider {

  private static final Logger LOG = LoggerFactory.getLogger(CredentialProvider.class);
  public static final Pattern PASSWORD_ALIAS_PATTERN = Pattern.compile("\\$\\{alias=[\\w\\.]+\\}");

  protected char[] chars = {'a', 'b', 'c', 'd', 'e', 'f', 'g',
      'h', 'j', 'k', 'm', 'n', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w',
      'x', 'y', 'z', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K',
      'M', 'N', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
      '2', '3', '4', '5', '6', '7', '8', '9'};

  private CredentialStore keystoreService;

  public CredentialProvider(CredentialStore keystoreService){
    this.keystoreService = keystoreService;
  }

  public void addAliasToCredentialStore(String alias, String passwordString)
      throws AmbariException {
    if (alias == null || alias.isEmpty()) {
      throw new IllegalArgumentException("Alias cannot be null or empty.");
    }
    if (passwordString == null || passwordString.isEmpty()) {
      throw new IllegalArgumentException("Empty or null password not allowed.");
    }
    keystoreService.addCredential(alias, new GenericKeyCredential(passwordString.toCharArray()));
  }


  public static boolean isAliasString(String aliasStr) {
    if (aliasStr == null || aliasStr.isEmpty()) {
      return false;
    }
    Matcher matcher = PASSWORD_ALIAS_PATTERN.matcher(aliasStr);
    return matcher.matches();
  }

  public static String getAliasFromString(String strPasswd) {
    return strPasswd.substring(strPasswd.indexOf("=") + 1, strPasswd.length() - 1);
  }

  protected CredentialStore getKeystoreService() {
    return keystoreService;
  }

}