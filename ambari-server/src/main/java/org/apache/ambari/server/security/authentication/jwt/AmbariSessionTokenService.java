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
package org.apache.ambari.server.security.authentication.jwt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * AMBARI-433.  Mints and verifies the Ambari-issued session JWT that backs the
 * {@code hadoop-jwt} cookie after a successful OIDC handshake.
 *
 * <h3>Why a server-issued session token?</h3>
 * Pre-AMBARI-433, {@code OidcCallbackFilter} placed the raw Keycloak access token into the
 * session cookie.  Access tokens are short-lived by design (Keycloak default: 5 min); once
 * expired, the cookie was rejected and the user was bounced through Keycloak again every
 * few minutes.  This service decouples the two concerns:
 * <ul>
 *   <li>The Keycloak access token authoritatively proves identity at login (and is
 *       discarded immediately after).</li>
 *   <li>The Ambari session JWT, signed with an Ambari-controlled HMAC key, backs the
 *       browser session for a configurable (default 8 h) period.</li>
 * </ul>
 *
 * <h3>Token shape</h3>
 * Standard JWS with {@code alg=HS256}.  Claims:
 * <pre>
 *   iss                = "ambari"                    (distinguishes from Keycloak-issued JWTs)
 *   sub                = &lt;username&gt;
 *   preferred_username = &lt;username&gt;             (so {@code resolveUsername} keeps working)
 *   iat                = epoch seconds at mint
 *   exp                = iat + lifespanSeconds
 *   jti                = UUID                        (uniqueness; useful for audit/revocation)
 * </pre>
 *
 * <h3>Signing-key derivation</h3>
 * If the operator sets {@code ambari.sso.session.signing.key}, that value is used as-is
 * (UTF-8 bytes).  Otherwise the key is derived from {@code ambari.sso.oidc.clientSecret}
 * via HKDF-SHA256 (RFC 5869) with the fixed info string {@code "ambari-session-jwt-v1"}.
 * HKDF gives us a key that is:
 * <ul>
 *   <li>at least 32 bytes (required by HS256/MACSigner);</li>
 *   <li>cryptographically independent of the OIDC state-token signing key (which
 *       uses a different info string), so leaking one does not leak the other.</li>
 * </ul>
 *
 * <h3>Threading</h3>
 * Stateless and immutable.  All public methods are safe to call concurrently.
 */
public final class AmbariSessionTokenService {

  private static final Logger LOG = LoggerFactory.getLogger(AmbariSessionTokenService.class);

  /** Distinguishes our tokens from upstream Keycloak/Knox JWTs at verification time. */
  public static final String ISSUER = "ambari";

  /** Standard JWT claim names that {@link #mint(String, long, byte[], String)} always writes. */
  private static final Set<String> RESERVED_USERNAME_CLAIMS = Collections.unmodifiableSet(
      new HashSet<>(java.util.Arrays.asList("sub", "preferred_username")));

  /**
   * Claims this service controls itself.  Caller-supplied entries with these names are silently
   * ignored to prevent the additional-claims map from clobbering issuer / lifespan / token-ID
   * fields and producing tokens that look valid but defeat the security model.
   */
  private static final Set<String> SERVICE_CONTROLLED_CLAIMS = Collections.unmodifiableSet(
      new HashSet<>(java.util.Arrays.asList("iss", "iat", "exp", "jti", "sub", "preferred_username")));

  /** HKDF info string for session-token key derivation.  Do not reuse for any other purpose. */
  static final String HKDF_INFO = "ambari-session-jwt-v1";

  /** Minimum HMAC key length in bytes — HS256 requires at least 256 bits. */
  static final int MIN_HMAC_KEY_BYTES = 32;

  private AmbariSessionTokenService() {
    // utility class — no instantiation
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Mint
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Mints a fresh HS256-signed session JWT for {@code username}, with only the standard
   * service-controlled claims.  Equivalent to {@link #mint(String, long, byte[], Map)
   * mint(username, lifespanSeconds, signingKey, Collections.emptyMap())}.
   */
  public static String mint(String username, long lifespanSeconds, byte[] signingKey)
      throws JOSEException {
    return mint(username, lifespanSeconds, signingKey, Collections.<String, Object>emptyMap());
  }

  /**
   * Mints a fresh HS256-signed session JWT for {@code username}, propagating arbitrary
   * upstream claims selected by the caller.
   *
   * <p>The session token <em>always</em> carries the service-controlled claims:
   * <ul>
   *   <li>{@code iss = "ambari"}</li>
   *   <li>{@code sub = username}</li>
   *   <li>{@code preferred_username = username}</li>
   *   <li>{@code iat}, {@code exp}, {@code jti}</li>
   * </ul>
   *
   * <p>{@code additionalClaims} entries whose key collides with a service-controlled claim
   * ({@link #SERVICE_CONTROLLED_CLAIMS}) are silently ignored — this prevents a caller from
   * forging an alternate issuer, extending the lifespan, or relabelling the subject.
   * All other entries are stamped verbatim.  Typical usage from the OIDC callback:
   * <pre>
   *   Map&lt;String, Object&gt; extras = new LinkedHashMap&lt;&gt;();
   *   extras.put(props.getOidcGroupsClaim(), upstreamClaims.getClaim(props.getOidcGroupsClaim()));
   *   if (!"sub".equals(c) &amp;&amp; !"preferred_username".equals(c)) extras.put(c, username);
   *   mint(username, ttl, key, extras);
   * </pre>
   *
   * <p><b>Security note:</b> the additional claims become trusted server-internal state
   * (the token is signed and therefore tamper-evident).  Only forward claims the
   * downstream code is designed to consume — do <em>not</em> blindly mirror every claim
   * from the upstream access token, since that would let any Keycloak misconfiguration
   * push attacker-controlled data into Ambari's authentication path.
   *
   * @param username          the authenticated user's name
   * @param lifespanSeconds   cookie / token lifespan; must be {@code > 0}
   * @param signingKey        HMAC key bytes (must be at least {@value #MIN_HMAC_KEY_BYTES})
   * @param additionalClaims  caller-supplied extra claims to stamp; may be {@code null} or
   *                          empty.  Entries with reserved names are dropped (with a log).
   * @return the serialized JWT string suitable for use as a cookie value
   * @throws IllegalArgumentException if {@code username} is empty or {@code lifespanSeconds <= 0}
   *                                  or {@code signingKey} is too short
   * @throws JOSEException            if HMAC signing fails (should not happen with valid input)
   */
  public static String mint(String username, long lifespanSeconds, byte[] signingKey,
                            Map<String, ?> additionalClaims) throws JOSEException {
    if (StringUtils.isEmpty(username)) {
      throw new IllegalArgumentException("Session token requires a non-empty username");
    }
    if (lifespanSeconds <= 0L) {
      throw new IllegalArgumentException(
          "Session token lifespan must be > 0 (got " + lifespanSeconds + ")");
    }
    if (signingKey == null || signingKey.length < MIN_HMAC_KEY_BYTES) {
      throw new IllegalArgumentException(
          "Session token signing key must be at least " + MIN_HMAC_KEY_BYTES + " bytes");
    }

    long nowMillis = System.currentTimeMillis();
    Date issuedAt  = new Date(nowMillis);
    Date expiresAt = new Date(nowMillis + lifespanSeconds * 1000L);

    JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .subject(username)
        .claim("preferred_username", username)
        .issueTime(issuedAt)
        .expirationTime(expiresAt)
        .jwtID(UUID.randomUUID().toString());

    if (additionalClaims != null) {
      for (Map.Entry<String, ?> e : additionalClaims.entrySet()) {
        String name = e.getKey();
        if (StringUtils.isEmpty(name)) {
          continue;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
          // Whitespace-only keys would otherwise collapse to "" after trim and stamp a
          // claim under the empty name — silently swallow them instead.
          continue;
        }
        if (SERVICE_CONTROLLED_CLAIMS.contains(trimmed)) {
          LOG.debug("Ignoring additional claim '{}' — name is service-controlled", trimmed);
          continue;
        }
        Object value = e.getValue();
        if (value == null) {
          continue;
        }
        builder = builder.claim(trimmed, value);
      }
    }

    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), builder.build());
    jwt.sign(new MACSigner(signingKey));
    return jwt.serialize();
  }

  /**
   * Back-compat overload preserved so callers that only need to ensure the operator-configured
   * username claim is present can use a one-line call.  Equivalent to:
   * <pre>
   *   Map&lt;String, Object&gt; extras = configuredUsernameClaim is reserved/empty
   *                                   ? emptyMap()
   *                                   : singletonMap(configuredUsernameClaim, username);
   *   mint(username, lifespanSeconds, signingKey, extras);
   * </pre>
   *
   * <p>New call sites should prefer the {@link #mint(String, long, byte[], Map)} form so
   * other operator-configured claims (groups, email, etc.) can be propagated in the same
   * call.
   */
  public static String mint(String username, long lifespanSeconds, byte[] signingKey,
                            String configuredUsernameClaim) throws JOSEException {
    Map<String, Object> extras = Collections.emptyMap();
    if (!StringUtils.isEmpty(configuredUsernameClaim)) {
      String trimmed = configuredUsernameClaim.trim();
      if (!RESERVED_USERNAME_CLAIMS.contains(trimmed)) {
        extras = Collections.<String, Object>singletonMap(trimmed, username);
      }
    }
    return mint(username, lifespanSeconds, signingKey, extras);
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Username resolution (shared between OidcCallbackFilter and
  // AmbariJwtAuthenticationFilter so the two stay in lock-step)
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Resolves the Ambari username from a JWT according to the operator-configured policy.
   *
   * <p>Algorithm — matches the historical {@code AmbariJwtAuthenticationFilter.resolveUsername}
   * behavior so existing deployments are unaffected:
   * <ol>
   *   <li>If {@code ambari.sso.jwt.usernameClaim} is configured (non-empty), use that claim
   *       <em>without</em> fallback.  Returning {@code null} when the claim is missing surfaces
   *       a misconfiguration cleanly rather than silently authenticating as the wrong user.</li>
   *   <li>Otherwise, try {@code preferred_username} (Keycloak default for username).</li>
   *   <li>Otherwise, fall back to {@code sub} (Knox SSO legacy / OIDC subject identifier).</li>
   * </ol>
   *
   * <p>Used at two points in the request lifecycle:
   * <ul>
   *   <li>Login-time: extracting identity from the upstream Keycloak access token to mint
   *       a session JWT.</li>
   *   <li>Request-time: extracting identity from the session JWT for each authenticated
   *       request.</li>
   * </ul>
   *
   * @param jwtToken  the parsed JWT (signature verification is NOT performed by this method)
   * @param props     the JWT/OIDC configuration; may be {@code null} (treated as default config)
   * @return the resolved username, or {@code null} if none of the inspected claims is present
   * @throws ParseException if the JWT's claims set is malformed
   */
  public static String resolveUsername(SignedJWT jwtToken, JwtAuthenticationProperties props)
      throws ParseException {
    if (jwtToken == null) {
      return null;
    }
    String configuredClaim = (props == null) ? "" : props.getJwtUsernameClaim();
    JWTClaimsSet claims = jwtToken.getJWTClaimsSet();
    if (!StringUtils.isEmpty(configuredClaim)) {
      return claims.getStringClaim(configuredClaim);
    }
    String preferredUsername = claims.getStringClaim("preferred_username");
    return !StringUtils.isEmpty(preferredUsername) ? preferredUsername : claims.getSubject();
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Verify
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Verifies that {@code jwtToken} is a well-formed, unexpired, HS256-signed Ambari session
   * token signed by the provided key.
   *
   * <p>Callers must already have determined that the token was issued by us (via
   * {@link #isAmbariSessionToken(SignedJWT)}); this method does NOT re-check the issuer to
   * keep the failure modes precise (signature vs. expiration vs. issuer mismatch are all
   * logged distinctly).
   *
   * @param jwtToken   the parsed JWT
   * @param signingKey the HMAC key the token should verify against
   * @return {@code true} if signature is valid, algorithm is HS256, and {@code exp} is in
   *         the future; {@code false} otherwise (with a warn-level log)
   */
  public static boolean verify(SignedJWT jwtToken, byte[] signingKey) {
    if (jwtToken == null) {
      return false;
    }
    if (signingKey == null || signingKey.length < MIN_HMAC_KEY_BYTES) {
      LOG.warn("Session token verification skipped — signing key absent or too short");
      return false;
    }

    JWSAlgorithm alg = jwtToken.getHeader().getAlgorithm();
    if (!JWSAlgorithm.HS256.equals(alg)) {
      // Strict alg pinning prevents 'alg=none' downgrade attacks and accidental RS256 cross-talk.
      LOG.warn("Rejecting session token: unexpected algorithm {} (expected HS256)", alg);
      return false;
    }

    try {
      JWSVerifier verifier = new MACVerifier(signingKey);
      if (!jwtToken.verify(verifier)) {
        LOG.warn("Session token HMAC signature did not verify");
        return false;
      }
    } catch (JOSEException e) {
      LOG.warn("Session token signature verification failed: {}", e.getMessage());
      return false;
    }

    Date expiresAt;
    try {
      expiresAt = jwtToken.getJWTClaimsSet().getExpirationTime();
    } catch (ParseException e) {
      LOG.warn("Session token claims unparseable: {}", e.getMessage());
      return false;
    }
    if (expiresAt == null) {
      LOG.warn("Session token has no exp claim — rejecting");
      return false;
    }
    if (!new Date().before(expiresAt)) {
      LOG.info("Session token has expired (exp={})", expiresAt);
      return false;
    }
    return true;
  }

  /**
   * Cheap pre-check: is this token plausibly one of ours?  Used by the authentication filter
   * to decide whether to route through HMAC verification or the legacy RSA path.  Returning
   * {@code true} does not imply the signature is valid — only that the issuer claim and
   * algorithm match our format.
   *
   * @param jwtToken the parsed JWT
   * @return {@code true} if {@code iss=ambari} AND {@code alg=HS256}
   */
  public static boolean isAmbariSessionToken(SignedJWT jwtToken) {
    if (jwtToken == null) {
      return false;
    }
    if (!JWSAlgorithm.HS256.equals(jwtToken.getHeader().getAlgorithm())) {
      return false;
    }
    try {
      return ISSUER.equals(jwtToken.getJWTClaimsSet().getIssuer());
    } catch (ParseException e) {
      return false;
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Signing key resolution
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Resolves the effective HMAC signing key from the configured properties.
   *
   * <p>Lookup order:
   * <ol>
   *   <li>{@code ambari.sso.session.signing.key} — if non-empty, used as raw UTF-8 bytes
   *       (must be at least 32 chars).</li>
   *   <li>HKDF-derived from {@code ambari.sso.oidc.clientSecret} with info string
   *       {@value #HKDF_INFO}.  Requires a non-empty client secret.</li>
   * </ol>
   *
   * @param props the JWT/OIDC configuration
   * @return key bytes (always ≥ 32 bytes), or {@code null} if no source is available
   */
  public static byte[] resolveSigningKey(JwtAuthenticationProperties props) {
    if (props == null) {
      return null;
    }
    String explicit = props.getSessionSigningKey();
    if (!StringUtils.isEmpty(explicit)) {
      byte[] bytes = explicit.getBytes(StandardCharsets.UTF_8);
      if (bytes.length < MIN_HMAC_KEY_BYTES) {
        LOG.warn("ambari.sso.session.signing.key is configured but too short ({} bytes); "
            + "minimum is {} bytes.  Falling back to HKDF derivation from clientSecret.",
            bytes.length, MIN_HMAC_KEY_BYTES);
      } else {
        return bytes;
      }
    }

    String clientSecret = props.getOidcClientSecret();
    if (StringUtils.isEmpty(clientSecret)) {
      LOG.warn("Cannot derive session-token signing key: neither ambari.sso.session.signing.key "
          + "nor ambari.sso.oidc.clientSecret is set");
      return null;
    }
    try {
      return hkdfSha256(clientSecret.getBytes(StandardCharsets.UTF_8), null,
          HKDF_INFO.getBytes(StandardCharsets.UTF_8), MIN_HMAC_KEY_BYTES);
    } catch (NoSuchAlgorithmException e) {
      // HmacSHA256 is part of the JRE since Java 1.4 — should never happen.
      throw new IllegalStateException("HmacSHA256 unavailable on this JVM", e);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // HKDF-SHA256 (RFC 5869) — minimal local implementation
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Compact HKDF-SHA256 implementation.  Not exposed publicly — internal use only.  We
   * inline it (rather than pulling in a new dependency) because we need exactly one call
   * site and the algorithm fits in &lt; 40 lines.
   *
   * @param ikm    input keying material
   * @param salt   optional salt; {@code null} means an all-zero salt of {@code HashLen} bytes
   * @param info   context/application-specific info; SHOULD be unique per derived key
   * @param length number of output bytes desired (max 255 * HashLen = 8160)
   * @return derived key bytes of the requested length
   */
  static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length)
      throws NoSuchAlgorithmException {
    final int hashLen = 32; // SHA-256
    if (length > 255 * hashLen) {
      throw new IllegalArgumentException("HKDF length too large");
    }

    byte[] saltBytes = (salt == null || salt.length == 0) ? new byte[hashLen] : salt;
    byte[] prk = hmacSha256(saltBytes, ikm);

    // Expand: T(N) = HMAC(PRK, T(N-1) || info || N)
    byte[] result = new byte[length];
    byte[] previous = new byte[0];
    int offset = 0;
    int counter = 1;
    while (offset < length) {
      byte[] block = new byte[previous.length + (info == null ? 0 : info.length) + 1];
      System.arraycopy(previous, 0, block, 0, previous.length);
      if (info != null) {
        System.arraycopy(info, 0, block, previous.length, info.length);
      }
      block[block.length - 1] = (byte) counter;
      previous = hmacSha256(prk, block);
      int copyLen = Math.min(previous.length, length - offset);
      System.arraycopy(previous, 0, result, offset, copyLen);
      offset += copyLen;
      counter++;
    }
    return result;
  }

  private static byte[] hmacSha256(byte[] key, byte[] data) throws NoSuchAlgorithmException {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (java.security.InvalidKeyException e) {
      // SecretKeySpec with non-empty bytes never throws InvalidKeyException in practice.
      throw new IllegalStateException("HMAC init failed", e);
    }
  }

  /**
   * Constant-time byte-array equality.  Provided for callers that need to compare HMACs
   * or token-derived values without leaking timing information.  We don't strictly need it
   * inside this class (Nimbus does its own verification), but it's a useful primitive to
   * expose to {@link OidcCallbackFilter} too.
   */
  public static boolean constantTimeEquals(byte[] a, byte[] b) {
    return MessageDigest.isEqual(a, b);
  }
}
