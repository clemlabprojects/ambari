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

package org.apache.ambari.server.serveraction.kerberos;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.function.Consumer;

import org.apache.directory.server.kerberos.shared.keytab.Keytab;
import org.apache.directory.server.kerberos.shared.keytab.KeytabEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates or reuses a principal through an opened {@link KerberosOperationHandler} and returns its keytab: the work
 * of an ad-hoc keytab, for a cluster's KDC ({@link GenerateAdhocKeytabServerAction}) or a KDC no cluster manages
 * (the KDPS platform contexts).
 * <p/>
 * An existing principal keeps its key when the KDC can export it (MIT {@code xst -norandkey}, IPA
 * {@code ipa-getkeytab -r}). Otherwise its key is reset only when the caller allows it: resetting the key of a
 * principal someone else uses (a Hive or HDFS account of a CDP cluster in its Active Directory) would break them.
 */
public final class AdhocKeytabGenerator {

  private static final Logger LOG = LoggerFactory.getLogger(AdhocKeytabGenerator.class);

  /** A keytab issued for a principal. */
  public static final class Result {
    private final int kvno;
    private final boolean created;
    private final byte[] keytab;

    Result(int kvno, boolean created, byte[] keytab) {
      this.kvno = kvno;
      this.created = created;
      this.keytab = keytab;
    }

    public int getKvno() {
      return kvno;
    }

    /** Whether the principal was created for this keytab. */
    public boolean isCreated() {
      return created;
    }

    public byte[] getKeytab() {
      return keytab;
    }

    public String getSha256() {
      return sha256Hex(keytab);
    }
  }

  /** An existing principal whose key cannot be exported, and may not be reset. */
  public static final class ExistingPrincipalException extends KerberosOperationException {
    ExistingPrincipalException(String message) {
      super(message);
    }
  }

  private AdhocKeytabGenerator() {
  }

  /**
   * Issues the keytab of a principal.
   *
   * @param handler          a handler opened on the KDC
   * @param principal        the principal, {@code name@REALM} or {@code name/host@REALM}
   * @param mayResetExisting whether the key of an existing principal may be reset when the KDC cannot export it
   * @param out              progress lines, for the operation's log
   * @throws ExistingPrincipalException when the principal exists, its key cannot be exported and may not be reset
   */
  public static Result issue(KerberosOperationHandler handler, String principal, boolean mayResetExisting,
                             Consumer<String> out) throws Exception {
    return issue(handler, principal, existing -> mayResetExisting, false, null, out);
  }

  /**
   * Issues the keytab of a principal whose account carries who created it ({@link KdcAccountDescriptions}): a new
   * account is given {@code owner} as its description, and the key of an existing one is only reset when its
   * description is {@code owner}, so an account someone else created keeps working.
   *
   * @param owner  the description telling the accounts of the caller, such as {@code Created by KDPS for namespace ns}
   * @param newKey whether an existing principal gets a new key even when its current one could be exported
   * @throws ExistingPrincipalException when the principal exists and its key may not be reset, or cannot be exported
   */
  public static Result issueOwned(KerberosOperationHandler handler, String principal, String owner, boolean newKey,
                                  Consumer<String> out) throws Exception {
    return issue(handler, principal, existing -> owner.equals(descriptionOf(handler, existing)), newKey, owner, out);
  }

  private interface ResetCheck {
    boolean mayReset(String principal) throws Exception;
  }

  private static Result issue(KerberosOperationHandler handler, String principal, ResetCheck mayReset, boolean newKey,
                              String owner, Consumer<String> out) throws Exception {
    // KDCs that need host objects (FreeIPA) get one; a no-op for MIT and AD.
    String host = hostOf(principal);
    if (host != null) {
      LOG.info("Ensuring host object exists for '{}'", host);
      handler.ensureHostExists(host);
      out.accept("Ensured host object exists for '" + host + "' (if required by KDC).");
    } else {
      LOG.info("No host component parsed from principal '{}'; skipping ensureHostExists", principal);
    }

    // Service principals have a slash.
    boolean isService = principal.contains("/");
    boolean exists = handler.principalExists(principal, isService);
    LOG.info("Principal '{}' existence check: exists={}, isService={}", principal, exists, isService);

    File tmpKeytab = File.createTempFile("adhoc_", ".keytab");
    try {
      Integer kvno;
      if (exists) {
        // The current key is exported without rekeying when the KDC allows it: rotating on each issuance desynced
        // the keytabs KDPS had put in Secrets from the KDC ("Preauthentication failed").
        Integer noRekeyKvno = null;
        Exception noRekeyErr = null;
        if (!newKey) {
          try {
            out.accept("Principal exists; extracting its CURRENT key WITHOUT rekey...");
            noRekeyKvno = handler.createKeytabFileForExistingPrincipal(principal, null, tmpKeytab);
          } catch (Exception e) {
            // Not on every KDC: AD cannot export an existing key at all, and FreeIPA's `ipa-getkeytab -r` needs a
            // retrieve-keytab ACL the administrator may not hold.
            noRekeyErr = e;
          }
        }
        if (noRekeyKvno != null) {
          kvno = noRekeyKvno;
        } else {
          String why = newKey ? "a new key was asked for" : "its key cannot be exported without resetting it ("
              + trimMsg(noRekeyErr) + ")";
          if (!mayReset.mayReset(principal)) {
            throw new ExistingPrincipalException(String.format("Principal '%s' already exists and %s, but it was not "
                + "created for this request%s. It was not changed: whoever uses it keeps working. Give its keytab as an "
                + "existing Secret, or have its account removed so that a new one is created.", principal, why,
                owner == null ? "" : " (its account's description is not '" + owner + "')"));
          }
          out.accept("Resetting the key of " + principal + ": " + why);
          LOG.info("Resetting the key of principal '{}': {}", principal, why);
          String password = generateSecurePassword(32);
          kvno = keyVersion(handler, principal, handler.setPrincipalPassword(principal, password, isService));
          handler.createKeytabFile(principal, password, kvno, tmpKeytab);
        }
      } else {
        out.accept("Principal does not exist; creating...");
        String password = generateSecurePassword(32);
        kvno = handler.createPrincipal(principal, password, isService);
        if (owner != null && handler instanceof KdcAccountDescriptions
            && !owner.equals(descriptionOf(handler, principal))) {
          try {
            ((KdcAccountDescriptions) handler).setAccountDescription(principal, owner);
          } catch (KerberosOperationException e) {
            // Unmarked, the account could never get a key again: it is removed, so that a next request creates it.
            LOG.warn("Removing principal '{}': its account could not be marked as created by the caller", principal);
            handler.removePrincipal(principal, isService);
            throw e;
          }
        }
        kvno = keyVersion(handler, principal, kvno);
        // A no-rekey export of the new key is right for randkey KDCs, where the password is not the key; else the
        // handler's own keytab creation (MIT string2key, IPA ipa-getkeytab, AD).
        Integer extracted = null;
        try {
          extracted = handler.createKeytabFileForExistingPrincipal(principal, null, tmpKeytab);
        } catch (Exception noRekeyErr) {
          LOG.info("No-rekey export of freshly-created principal '{}' unavailable ({}); using normal keytab creation",
              principal, trimMsg(noRekeyErr));
        }
        if (extracted != null) {
          kvno = extracted;
        } else {
          handler.createKeytabFile(principal, password, kvno, tmpKeytab);
        }
      }
      byte[] keytab = Files.readAllBytes(tmpKeytab.toPath());
      if (kvno == null || kvno == 0) {
        // FreeIPA numbers the key when the keytab is written: the keytab tells it.
        Integer written = keytabKeyVersion(tmpKeytab);
        if (written != null) {
          kvno = written;
        }
      }
      LOG.info("Principal '{}' processed with kvno={} (created={}), keytab of {} bytes", principal, kvno, !exists,
          keytab.length);
      return new Result(kvno == null ? 0 : kvno, !exists, keytab);
    } finally {
      try {
        Files.deleteIfExists(tmpKeytab.toPath());
      } catch (Exception ignore) {
        LOG.warn("Failed to delete temp keytab file '{}': {}", tmpKeytab.getAbsolutePath(), ignore.getMessage());
      }
    }
  }

  /** The description of an existing principal's account, or null when the KDC keeps none. */
  private static String descriptionOf(KerberosOperationHandler handler, String principal)
      throws KerberosOperationException {
    return handler instanceof KdcAccountDescriptions
        ? ((KdcAccountDescriptions) handler).getAccountDescription(principal) : null;
  }

  /**
   * The key version a keytab entry must carry: the one Active Directory holds for the account, which its handler does
   * not report (it answers 0, and a keytab numbered 0 is refused by services checking the version).
   */
  private static Integer keyVersion(KerberosOperationHandler handler, String principal, Integer reported)
      throws KerberosOperationException {
    if ((reported == null || reported == 0) && handler instanceof ADKerberosOperationHandler) {
      Integer held = ((ADKerberosOperationHandler) handler).getKeyVersionNumber(principal);
      if (held != null) {
        return held;
      }
    }
    return reported;
  }

  /** The highest key version of a keytab file, or null when it cannot be read. */
  static Integer keytabKeyVersion(File keytabFile) {
    try {
      Keytab keytab = Keytab.read(keytabFile);
      Integer max = null;
      if (keytab != null && keytab.getEntries() != null) {
        for (KeytabEntry entry : keytab.getEntries()) {
          int version = entry.getKeyVersion() & 0xff;
          if (max == null || version > max) {
            max = version;
          }
        }
      }
      return max;
    } catch (Exception e) {
      return null;
    }
  }

  /** The host of {@code name/host@REALM}, or null. */
  static String hostOf(String principal) {
    int slash = principal.indexOf('/');
    int at = principal.indexOf('@');
    if (slash > 0 && at > slash + 1) {
      String host = principal.substring(slash + 1, at).trim();
      return host.isEmpty() ? null : host;
    }
    return null;
  }

  static String trimMsg(Throwable t) {
    String m = (t == null) ? "" : (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
    m = m.replaceAll("\\s+", " ").trim();
    return (m.length() > 160) ? (m.substring(0, 160) + "…") : m;
  }

  private static String generateSecurePassword(int len) {
    final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_=+[]{}";
    SecureRandom rnd = new SecureRandom();
    StringBuilder sb = new StringBuilder(len);
    for (int i = 0; i < len; i++) {
      sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
    }
    return sb.toString();
  }

  static String sha256Hex(byte[] payload) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(payload);
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 digest is not available", e);
    }
  }
}
