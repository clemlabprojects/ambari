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

import org.apache.ambari.view.k8s.utils.AmbariPath;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class MasterKeyServiceImpl implements MasterKeyService {
  private static final Logger LOG = LoggerFactory.getLogger(MasterKeyServiceImpl.class);

  public static final String MASTER_KEY_ENV_PROP = "AMBARI_SECURITY_MASTER_KEY";
  private static final String MASTER_PASSPHRASE = "masterpassphrase";
  private static final String MASTER_PERSISTENCE_TAG_PREFIX = "#1.0# ";
  public static final String MASTER_KEY_LOCATION = "security.master.key.location";
  public static final String MASTER_KEY_DEFAULT_LOCATION = "/var/lib/ambari-server/keys/master";

  private static final AESEncryptor aes = new AESEncryptor(MASTER_PASSPHRASE);

  private char[] master = null;

  /**
   * Constructs a new MasterKeyServiceImpl using a master key read from a file.
   *
   * @param masterKeyFile the location of the master key file
   */
  public MasterKeyServiceImpl(File masterKeyFile) {
    if (masterKeyFile == null) {
      throw new IllegalArgumentException("Master Key location not provided.");
    }

    if (masterKeyFile.exists()) {
      if (isMasterKeyFile(masterKeyFile)) {
        try {
          initializeFromFile(masterKeyFile);
        } catch (Exception e) {
          LOG.error(String.format("Cannot initialize master key from %s: %s", masterKeyFile.getAbsolutePath(), e.getLocalizedMessage()), e);
        }
      } else {
        LOG.error(String.format("The file at %s is not a master ket file", masterKeyFile.getAbsolutePath()));
      }
    } else {
      LOG.error(String.format("Cannot open master key file, %s", masterKeyFile.getAbsolutePath()));
    }
  }

  /**
   * Constructs a new MasterKeyServiceImpl using the master key found in the environment.
   */
  public MasterKeyServiceImpl() {
    String key = readMasterKey();
    if (key != null) {
      master = key.toCharArray();
    }
  }

  @Override
  public boolean isMasterKeyInitialized() {
    return master != null;
  }

  @Override
  public char[] getMasterSecret() {
    return master;
  }


  /**
   * Determines if the specified file is a "master key" file by checking the file header to see if it
   * matches an expected value.
   * <p/>
   * The "master key" file is expected to have a header (or first line) that starts with "#1.0#". If it,
   * it is assumed to be a "master key" file, otherwise it is assumed to not be.
   *
   * @param file the file to test
   * @return true if the file is identitified as "master key" file; otherwise false
   */
  private static boolean isMasterKeyFile(File file) {
    FileReader reader = null;

    try {
      reader = new FileReader(file);
      char[] buffer = new char[MASTER_PERSISTENCE_TAG_PREFIX.length()];
      return (reader.read(buffer) == buffer.length) && Arrays.equals(buffer, MASTER_PERSISTENCE_TAG_PREFIX.toCharArray());
    } catch (Exception e) {
      // Ignore, assume the file is not a master key file...
    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (IOException e) {
          // Ignore...
        }
      }
    }

    return false;
  }



  private String readMasterKey() {
    String key = null;
    Map<String, String> envVariables = System.getenv();
    if (envVariables != null && !envVariables.isEmpty()) {
      key = envVariables.get(MASTER_KEY_ENV_PROP);
      if (key == null || key.isEmpty()) {
        String keyPath = envVariables.get(MASTER_KEY_LOCATION);
        if (keyPath != null && !keyPath.isEmpty()) {
          LOG.info("Read successfully master file: {} from env", keyPath);
        }else{
          keyPath = MASTER_KEY_DEFAULT_LOCATION;
          LOG.info("Read successfully master file: {} from default location", keyPath);
        }
        File keyFile = new File(keyPath);
        if (keyFile.exists()) {
          try {
            initializeFromFile(keyFile);
            if (master != null) {
              key = new String(master);
            }
          } catch (Exception e) {
            LOG.error("Cannot read master key from file: " + keyPath);
            e.printStackTrace();
          }
        }
      }
    }
    return key;
  }

  private void initializeFromFile(File masterFile) throws Exception {
    try {
      List<String> lines = FileUtils.readLines(masterFile, "UTF8");
      String tag = lines.get(0);
      LOG.info("Loading from persistent master: " + tag);
      String line = new String(Base64.decodeBase64(lines.get(1)));
      String[] parts = line.split("::");
      master = new String(aes.decrypt(Base64.decodeBase64(parts[0]),
          Base64.decodeBase64(parts[1]), Base64.decodeBase64(parts[2])),
          "UTF8").toCharArray();
    } catch (Exception e) {
      e.printStackTrace();
      throw e;
    }
  }
}
