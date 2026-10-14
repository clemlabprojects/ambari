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

import org.apache.ambari.view.ViewContext;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * The Ambari master key, found where Ambari itself finds it: the {@code AMBARI_SECURITY_MASTER_KEY} environment
 * variable (set when the key is not persisted), else the {@code master} file that {@code ambari-server setup-security}
 * persists in the directory named by {@code security.master.key.location}, or else by {@code security.server.keys_dir}
 * ({@code /var/lib/ambari-server/keys} when neither is set) in {@code ambari.properties}.
 *
 * <p>Building one never fails: when no key is found, {@link #isMasterKeyInitialized()} is {@code false} and the
 * caller decides what that means ({@link EncryptionService} refuses to encrypt).
 */
public class MasterKeyServiceImpl implements MasterKeyService {
  private static final Logger LOG = LoggerFactory.getLogger(MasterKeyServiceImpl.class);

  public static final String MASTER_KEY_ENV_PROP = "AMBARI_SECURITY_MASTER_KEY";
  private static final String MASTER_PASSPHRASE = "masterpassphrase";
  private static final String MASTER_PERSISTENCE_TAG_PREFIX = "#1.0# ";
  /** Ambari property: the directory of the persisted master key file. */
  public static final String MASTER_KEY_LOCATION = "security.master.key.location";
  /** Ambari property: the directory of Ambari's keys, where the master key file is when the property above is unset. */
  public static final String SERVER_KEYS_DIR = "security.server.keys_dir";
  public static final String MASTER_KEY_FILE_NAME = "master";
  public static final String MASTER_KEY_DEFAULT_LOCATION = "/var/lib/ambari-server/keys/master";
  /** Environment variable Ambari's scripts use for a configuration directory other than the default one. */
  static final String AMBARI_CONF_DIR_ENV = "AMBARI_CONF_DIR";
  static final String AMBARI_CONF_DEFAULT_DIR = "/etc/ambari-server/conf";

  private static final AESEncryptor aes = new AESEncryptor(MASTER_PASSPHRASE);

  private char[] master = null;
  /** Where the key was looked for: the environment variable, or the key file. */
  private String source;

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
   * Finds the master key of the Ambari server a view instance runs in, reading the key locations from its Ambari
   * properties.
   *
   * @param viewContext the view instance
   */
  public MasterKeyServiceImpl(ViewContext viewContext) {
    this(System.getenv(), viewContext::getAmbariProperty);
  }

  /**
   * Finds the master key of this Ambari server, reading the key locations from its {@code ambari.properties} file,
   * for code that has no view instance at hand.
   */
  public MasterKeyServiceImpl() {
    this(System.getenv(), ambariPropertiesFile(System.getenv()));
  }

  /**
   * Finds the master key in the given environment and Ambari properties.
   *
   * @param env            the environment variables
   * @param ambariProperty the Ambari property of a name, or {@code null} when it is not set
   */
  MasterKeyServiceImpl(Map<String, String> env, Function<String, String> ambariProperty) {
    String key = env == null ? null : env.get(MASTER_KEY_ENV_PROP);
    if (key != null && !key.isEmpty()) {
      master = key.toCharArray();
      source = "the " + MASTER_KEY_ENV_PROP + " environment variable";
      return;
    }
    File keyFile = keyFile(ambariProperty);
    source = keyFile.getAbsolutePath();
    if (!keyFile.exists()) {
      LOG.debug("No Ambari master key: {} is not set and {} does not exist", MASTER_KEY_ENV_PROP, source);
      return;
    }
    if (!isMasterKeyFile(keyFile)) {
      LOG.error("The file at {} is not a master key file", source);
      return;
    }
    try {
      initializeFromFile(keyFile);
      LOG.debug("Read the Ambari master key from {}", source);
    } catch (Exception e) {
      LOG.error("Cannot read the Ambari master key from {}: {}", source, e.toString());
      master = null;
    }
  }

  @Override
  public boolean isMasterKeyInitialized() {
    return master != null;
  }

  /** A copy of the key, which the caller may wipe once used. */
  @Override
  public char[] getMasterSecret() {
    return master == null ? null : master.clone();
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



  /**
   * Where the key was looked for: "the AMBARI_SECURITY_MASTER_KEY environment variable", or the path of the key file.
   * {@code null} for a key read from a file given to the constructor.
   */
  public String getSource() {
    return source;
  }

  /**
   * The master key file, as Ambari locates it: {@code master} in the directory named by
   * {@code security.master.key.location}, else by {@code security.server.keys_dir}, else in
   * {@code /var/lib/ambari-server/keys}. A {@code security.master.key.location} naming the file itself is accepted.
   *
   * @param ambariProperty the Ambari property of a name, or {@code null} when it is not set
   * @return the file, whether it exists or not
   */
  static File keyFile(Function<String, String> ambariProperty) {
    String location = property(ambariProperty, MASTER_KEY_LOCATION);
    if (location != null) {
      File named = new File(location);
      return named.isFile() ? named : new File(named, MASTER_KEY_FILE_NAME);
    }
    String keysDir = property(ambariProperty, SERVER_KEYS_DIR);
    return keysDir != null ? new File(keysDir, MASTER_KEY_FILE_NAME) : new File(MASTER_KEY_DEFAULT_LOCATION);
  }

  private static String property(Function<String, String> ambariProperty, String name) {
    String value;
    try {
      value = ambariProperty == null ? null : ambariProperty.apply(name);
    } catch (RuntimeException e) {
      LOG.debug("Cannot read the Ambari property {}: {}", name, e.toString());
      return null;
    }
    return value == null || value.isBlank() ? null : value.trim();
  }

  /**
   * The properties of {@code ambari.properties}, in {@code $AMBARI_CONF_DIR} or {@code /etc/ambari-server/conf}; none
   * when the file cannot be read.
   */
  static Function<String, String> ambariPropertiesFile(Map<String, String> env) {
    String confDir = env == null ? null : env.get(AMBARI_CONF_DIR_ENV);
    Path file = Paths.get(confDir == null || confDir.isBlank() ? AMBARI_CONF_DEFAULT_DIR : confDir, "ambari.properties");
    Properties properties = new Properties();
    if (Files.isReadable(file)) {
      try (Reader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
        properties.load(reader);
      } catch (IOException | RuntimeException e) {
        LOG.warn("Cannot read {}: {}", file, e.toString());
      }
    }
    return properties::getProperty;
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
      LOG.debug("Cannot decode the master key file {}", masterFile, e);
      throw e;
    }
  }
}
