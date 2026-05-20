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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.Test;

/**
 * Unit tests for {@link AmbariSessionTokenService}.  Covers the mint/verify happy path, all
 * documented failure modes (wrong key, expired token, wrong issuer, wrong algorithm), the
 * HKDF key-derivation fallback, and a regression test for the constant-time comparison.
 */
public class AmbariSessionTokenServiceTest {

  // 32 bytes — exactly meets MIN_HMAC_KEY_BYTES.
  private static final byte[] KEY_32      = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private static final byte[] KEY_32_ALT  = "ffeeddccbbaa99887766554433221100".getBytes(StandardCharsets.UTF_8);
  private static final byte[] KEY_TOO_SHORT = "tooshort".getBytes(StandardCharsets.UTF_8);

  // ─────────────────────────────────────────────────────────────────────────
  // mint(...)
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  public void mint_producesParseableHs256JwtWithExpectedClaims() throws Exception {
    long before = System.currentTimeMillis() / 1000L;
    String token = AmbariSessionTokenService.mint("alice", 3600L, KEY_32);
    long after = System.currentTimeMillis() / 1000L;

    SignedJWT parsed = SignedJWT.parse(token);
    assertEquals(JWSAlgorithm.HS256, parsed.getHeader().getAlgorithm());

    JWTClaimsSet claims = parsed.getJWTClaimsSet();
    assertEquals(AmbariSessionTokenService.ISSUER, claims.getIssuer());
    assertEquals("alice", claims.getSubject());
    assertEquals("alice", claims.getStringClaim("preferred_username"));
    assertNotNull("jti must be set", claims.getJWTID());

    long iat = claims.getIssueTime().getTime() / 1000L;
    long exp = claims.getExpirationTime().getTime() / 1000L;
    assertTrue("iat must be ~now", iat >= before && iat <= after);
    assertEquals("exp must be iat+lifespan", iat + 3600L, exp);
  }

  @Test(expected = IllegalArgumentException.class)
  public void mint_rejectsEmptyUsername() throws Exception {
    AmbariSessionTokenService.mint("", 3600L, KEY_32);
  }

  @Test(expected = IllegalArgumentException.class)
  public void mint_rejectsNullUsername() throws Exception {
    AmbariSessionTokenService.mint(null, 3600L, KEY_32);
  }

  @Test(expected = IllegalArgumentException.class)
  public void mint_rejectsNonPositiveLifespan() throws Exception {
    AmbariSessionTokenService.mint("alice", 0L, KEY_32);
  }

  @Test(expected = IllegalArgumentException.class)
  public void mint_rejectsTooShortSigningKey() throws Exception {
    AmbariSessionTokenService.mint("alice", 3600L, KEY_TOO_SHORT);
  }

  @Test(expected = IllegalArgumentException.class)
  public void mint_rejectsNullSigningKey() throws Exception {
    AmbariSessionTokenService.mint("alice", 3600L, null);
  }

  // ─────────────────────────────────────────────────────────────────────────
  // verify(...) happy path + signature/expiry failure modes
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  public void verify_returnsTrueForFreshTokenWithMatchingKey() throws Exception {
    String token = AmbariSessionTokenService.mint("alice", 3600L, KEY_32);
    SignedJWT parsed = SignedJWT.parse(token);
    assertTrue(AmbariSessionTokenService.verify(parsed, KEY_32));
  }

  @Test
  public void verify_returnsFalseForWrongKey() throws Exception {
    String token = AmbariSessionTokenService.mint("alice", 3600L, KEY_32);
    SignedJWT parsed = SignedJWT.parse(token);
    assertFalse(AmbariSessionTokenService.verify(parsed, KEY_32_ALT));
  }

  @Test
  public void verify_returnsFalseForTamperedSignature() throws Exception {
    String token = AmbariSessionTokenService.mint("alice", 3600L, KEY_32);
    // Flip the last character of the signature — base64url table is small enough that a
    // single-char swap reliably keeps it parseable but breaks the HMAC.
    String tampered = token.substring(0, token.length() - 1)
        + (token.charAt(token.length() - 1) == 'A' ? 'B' : 'A');
    SignedJWT parsed = SignedJWT.parse(tampered);
    assertFalse(AmbariSessionTokenService.verify(parsed, KEY_32));
  }

  @Test
  public void verify_returnsFalseForExpiredToken() throws Exception {
    // Manually craft an HS256 token whose exp is already in the past.
    long pastSec = (System.currentTimeMillis() / 1000L) - 60L;
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer(AmbariSessionTokenService.ISSUER)
        .subject("alice")
        .issueTime(new Date(pastSec * 1000L - 10_000L))
        .expirationTime(new Date(pastSec * 1000L))
        .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    jwt.sign(new MACSigner(KEY_32));

    assertFalse("Expired token must fail verify()",
        AmbariSessionTokenService.verify(jwt, KEY_32));
  }

  @Test
  public void verify_returnsFalseForTokenWithoutExpClaim() throws Exception {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer(AmbariSessionTokenService.ISSUER)
        .subject("alice")
        .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    jwt.sign(new MACSigner(KEY_32));

    assertFalse(AmbariSessionTokenService.verify(jwt, KEY_32));
  }

  @Test
  public void verify_returnsFalseForNullToken() {
    assertFalse(AmbariSessionTokenService.verify(null, KEY_32));
  }

  @Test
  public void verify_returnsFalseForTooShortSigningKey() throws Exception {
    String token = AmbariSessionTokenService.mint("alice", 3600L, KEY_32);
    SignedJWT parsed = SignedJWT.parse(token);
    assertFalse(AmbariSessionTokenService.verify(parsed, KEY_TOO_SHORT));
  }

  /**
   * Defense against alg-confusion attacks: a token signed with HS384 (different alg, same
   * key) must be rejected even though MACVerifier could in principle verify it.  We pin
   * to HS256 because that's the only algorithm {@link AmbariSessionTokenService#mint}
   * ever emits.
   */
  @Test
  public void verify_rejectsNonHs256Algorithm() throws Exception {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer(AmbariSessionTokenService.ISSUER)
        .subject("alice")
        .expirationTime(new Date(System.currentTimeMillis() + 3_600_000L))
        .build();
    // Sign with HS384 - MACSigner requires 48-byte key for HS384.
    byte[] hs384Key = new byte[48];
    System.arraycopy(KEY_32, 0, hs384Key, 0, 32);
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS384), claims);
    jwt.sign(new MACSigner(hs384Key));

    assertFalse(AmbariSessionTokenService.verify(jwt, hs384Key));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // isAmbariSessionToken(...)
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  public void isAmbariSessionToken_trueForFreshMint() throws Exception {
    SignedJWT parsed = SignedJWT.parse(AmbariSessionTokenService.mint("alice", 60L, KEY_32));
    assertTrue(AmbariSessionTokenService.isAmbariSessionToken(parsed));
  }

  @Test
  public void isAmbariSessionToken_falseForNonAmbariIssuer() throws Exception {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer("keycloak")
        .subject("alice")
        .expirationTime(new Date(System.currentTimeMillis() + 3_600_000L))
        .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    jwt.sign(new MACSigner(KEY_32));
    assertFalse(AmbariSessionTokenService.isAmbariSessionToken(jwt));
  }

  @Test
  public void isAmbariSessionToken_falseForMissingIssuer() throws Exception {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .subject("alice")
        .expirationTime(new Date(System.currentTimeMillis() + 3_600_000L))
        .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    jwt.sign(new MACSigner(KEY_32));
    assertFalse(AmbariSessionTokenService.isAmbariSessionToken(jwt));
  }

  @Test
  public void isAmbariSessionToken_falseForHs384Algorithm() throws Exception {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer(AmbariSessionTokenService.ISSUER)
        .subject("alice")
        .expirationTime(new Date(System.currentTimeMillis() + 3_600_000L))
        .build();
    byte[] hs384Key = new byte[48];
    System.arraycopy(KEY_32, 0, hs384Key, 0, 32);
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS384), claims);
    jwt.sign(new MACSigner(hs384Key));
    assertFalse("alg=HS384 must NOT route through the session-token branch",
        AmbariSessionTokenService.isAmbariSessionToken(jwt));
  }

  @Test
  public void isAmbariSessionToken_falseForNullToken() {
    assertFalse(AmbariSessionTokenService.isAmbariSessionToken(null));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // resolveSigningKey(...)
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  public void resolveSigningKey_returnsExplicitKeyWhenSet() {
    JwtAuthenticationProperties props = new JwtAuthenticationProperties(Collections.emptyMap());
    String key = "this-is-an-explicit-key-of-at-least-32-bytes-long";
    props.setSessionSigningKey(key);
    props.setOidcClientSecret("a-different-client-secret");

    byte[] resolved = AmbariSessionTokenService.resolveSigningKey(props);
    assertNotNull(resolved);
    assertEquals(key, new String(resolved, StandardCharsets.UTF_8));
  }

  @Test
  public void resolveSigningKey_fallsBackToHkdfWhenExplicitKeyMissing() {
    JwtAuthenticationProperties props = new JwtAuthenticationProperties(Collections.emptyMap());
    props.setSessionSigningKey("");
    props.setOidcClientSecret("client-secret-of-arbitrary-length");

    byte[] resolved = AmbariSessionTokenService.resolveSigningKey(props);
    assertNotNull(resolved);
    assertEquals("HKDF must produce exactly 32 bytes for HS256",
        AmbariSessionTokenService.MIN_HMAC_KEY_BYTES, resolved.length);
  }

  @Test
  public void resolveSigningKey_fallsBackToHkdfWhenExplicitKeyTooShort() {
    JwtAuthenticationProperties props = new JwtAuthenticationProperties(Collections.emptyMap());
    props.setSessionSigningKey("too-short");
    props.setOidcClientSecret("client-secret-of-arbitrary-length");

    byte[] resolved = AmbariSessionTokenService.resolveSigningKey(props);
    assertNotNull(resolved);
    assertEquals(AmbariSessionTokenService.MIN_HMAC_KEY_BYTES, resolved.length);
    assertFalse("HKDF-derived key must NOT equal the too-short explicit key",
        new String(resolved, StandardCharsets.UTF_8).equals("too-short"));
  }

  @Test
  public void resolveSigningKey_returnsNullWhenNoSourceAvailable() {
    JwtAuthenticationProperties props = new JwtAuthenticationProperties(Collections.emptyMap());
    props.setSessionSigningKey("");
    props.setOidcClientSecret("");
    assertNull(AmbariSessionTokenService.resolveSigningKey(props));
  }

  @Test
  public void resolveSigningKey_isDeterministicForSameClientSecret() {
    JwtAuthenticationProperties props = new JwtAuthenticationProperties(Collections.emptyMap());
    props.setOidcClientSecret("the-same-client-secret");

    byte[] first  = AmbariSessionTokenService.resolveSigningKey(props);
    byte[] second = AmbariSessionTokenService.resolveSigningKey(props);
    assertNotNull(first);
    assertTrue("HKDF must be deterministic — same input must yield same output",
        AmbariSessionTokenService.constantTimeEquals(first, second));
  }

  @Test
  public void resolveSigningKey_differsBetweenDifferentClientSecrets() {
    JwtAuthenticationProperties p1 = new JwtAuthenticationProperties(Collections.emptyMap());
    p1.setOidcClientSecret("secret-one");
    JwtAuthenticationProperties p2 = new JwtAuthenticationProperties(Collections.emptyMap());
    p2.setOidcClientSecret("secret-two");

    byte[] k1 = AmbariSessionTokenService.resolveSigningKey(p1);
    byte[] k2 = AmbariSessionTokenService.resolveSigningKey(p2);
    assertFalse("Different client secrets MUST produce different session keys",
        AmbariSessionTokenService.constantTimeEquals(k1, k2));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // End-to-end round trip via properties resolution
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  public void roundTrip_mintWithDerivedKey_verifiesWithSameProperties() throws Exception {
    JwtAuthenticationProperties props = new JwtAuthenticationProperties(Collections.emptyMap());
    props.setOidcClientSecret("a-realistic-32-character-client-secret-here");

    byte[] key = AmbariSessionTokenService.resolveSigningKey(props);
    assertNotNull(key);

    String token = AmbariSessionTokenService.mint("bob", 60L, key);
    SignedJWT parsed = SignedJWT.parse(token);
    assertTrue(AmbariSessionTokenService.verify(parsed, key));
    assertEquals("bob", parsed.getJWTClaimsSet().getSubject());
  }

  @Test
  public void roundTrip_keyRotation_invalidatesOldTokens() throws Exception {
    JwtAuthenticationProperties props = new JwtAuthenticationProperties(Collections.emptyMap());
    props.setOidcClientSecret("original-client-secret-of-sufficient-length");
    byte[] originalKey = AmbariSessionTokenService.resolveSigningKey(props);

    String token = AmbariSessionTokenService.mint("alice", 3600L, originalKey);
    SignedJWT parsed = SignedJWT.parse(token);

    // Operator rotates the client secret -> derived key changes -> token must be rejected.
    props.setOidcClientSecret("rotated-client-secret-of-sufficient-length");
    byte[] newKey = AmbariSessionTokenService.resolveSigningKey(props);
    assertFalse(AmbariSessionTokenService.verify(parsed, newKey));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // HKDF correctness (RFC 5869 test vector — Test Case 3, IKM=22 zero bytes, derived 42 bytes)
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  public void hkdfSha256_matchesRfc5869TestVector3() throws Exception {
    // RFC 5869 Test Case 3: salt absent, info absent, IKM = 22 bytes of 0x0b, L = 42.
    byte[] ikm = new byte[22];
    java.util.Arrays.fill(ikm, (byte) 0x0b);
    byte[] derived = AmbariSessionTokenService.hkdfSha256(ikm, null, null, 42);

    String expectedHex = "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8";
    StringBuilder hex = new StringBuilder();
    for (byte b : derived) {
      hex.append(String.format("%02x", b));
    }
    assertEquals(expectedHex, hex.toString());
  }

  // ─────────────────────────────────────────────────────────────────────────
  // constantTimeEquals
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  public void constantTimeEquals_trueForEqualArrays() {
    assertTrue(AmbariSessionTokenService.constantTimeEquals(KEY_32, KEY_32.clone()));
  }

  @Test
  public void constantTimeEquals_falseForDifferentArrays() {
    assertFalse(AmbariSessionTokenService.constantTimeEquals(KEY_32, KEY_32_ALT));
  }

  @Test
  public void constantTimeEquals_falseForDifferentLengths() {
    assertFalse(AmbariSessionTokenService.constantTimeEquals(KEY_32, KEY_TOO_SHORT));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Ensure utility class cannot be instantiated (defensive only — not strictly required).
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  public void utilityClass_isFinalAndHasPrivateConstructor() {
    // Reflective check that AmbariSessionTokenService is final and its only constructor is private.
    Class<?> c = AmbariSessionTokenService.class;
    assertTrue("Class must be final", java.lang.reflect.Modifier.isFinal(c.getModifiers()));
    java.lang.reflect.Constructor<?>[] ctors = c.getDeclaredConstructors();
    assertEquals(1, ctors.length);
    assertTrue("Constructor must be private",
        java.lang.reflect.Modifier.isPrivate(ctors[0].getModifiers()));
    // Calling it via reflection should fail-safely return an instance (we just want to confirm
    // no public visibility leak).  We don't actually need to instantiate it.
    try {
      ctors[0].setAccessible(true);
      ctors[0].newInstance();
    } catch (Exception e) {
      fail("Reflective construction unexpectedly threw: " + e);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // resolveUsername(...) — operator-configured claim policy
  // ─────────────────────────────────────────────────────────────────────────

  /** Build a token with arbitrary string claims for use in resolveUsername tests. */
  private static SignedJWT signedTokenWithClaims(java.util.Map<String, String> claims) throws Exception {
    JWTClaimsSet.Builder b = new JWTClaimsSet.Builder();
    for (java.util.Map.Entry<String, String> e : claims.entrySet()) {
      if ("sub".equals(e.getKey())) {
        b = b.subject(e.getValue());
      } else {
        b = b.claim(e.getKey(), e.getValue());
      }
    }
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), b.build());
    jwt.sign(new MACSigner(KEY_32));
    return jwt;
  }

  @Test
  public void resolveUsername_default_returnsPreferredUsername() throws Exception {
    SignedJWT jwt = signedTokenWithClaims(java.util.Collections.singletonMap("preferred_username", "alice"));
    JwtAuthenticationProperties props = new JwtAuthenticationProperties(Collections.emptyMap());
    props.setJwtUsernameClaim(""); // explicit default
    assertEquals("alice", AmbariSessionTokenService.resolveUsername(jwt, props));
  }

  @Test
  public void resolveUsername_default_fallsBackToSub_whenPreferredUsernameMissing() throws Exception {
    SignedJWT jwt = signedTokenWithClaims(java.util.Collections.singletonMap("sub", "fallback-user"));
    JwtAuthenticationProperties props = new JwtAuthenticationProperties(Collections.emptyMap());
    assertEquals("fallback-user", AmbariSessionTokenService.resolveUsername(jwt, props));
  }

  @Test
  public void resolveUsername_configuredClaim_returnsThatClaim() throws Exception {
    java.util.Map<String, String> claims = new java.util.HashMap<>();
    claims.put("sub", "uuid-123");
    claims.put("preferred_username", "alice");
    claims.put("email", "alice@corp.example.com");
    SignedJWT jwt = signedTokenWithClaims(claims);

    JwtAuthenticationProperties props = new JwtAuthenticationProperties(Collections.emptyMap());
    props.setJwtUsernameClaim("email");
    assertEquals("Configured claim wins over preferred_username/sub",
        "alice@corp.example.com", AmbariSessionTokenService.resolveUsername(jwt, props));
  }

  @Test
  public void resolveUsername_configuredClaim_returnsNull_whenClaimAbsent() throws Exception {
    // Operator says "use email", token has no email — return null without falling back.
    // This surfaces the misconfiguration loudly instead of silently auth'ing as the wrong user.
    SignedJWT jwt = signedTokenWithClaims(java.util.Collections.singletonMap("preferred_username", "alice"));
    JwtAuthenticationProperties props = new JwtAuthenticationProperties(Collections.emptyMap());
    props.setJwtUsernameClaim("email");
    assertNull(AmbariSessionTokenService.resolveUsername(jwt, props));
  }

  @Test
  public void resolveUsername_configuredSubExplicitly_returnsSub() throws Exception {
    // Knox SSO legacy: usernameClaim=sub explicitly.
    java.util.Map<String, String> claims = new java.util.HashMap<>();
    claims.put("sub", "knox-user");
    claims.put("preferred_username", "should-be-ignored");
    SignedJWT jwt = signedTokenWithClaims(claims);

    JwtAuthenticationProperties props = new JwtAuthenticationProperties(Collections.emptyMap());
    props.setJwtUsernameClaim("sub");
    assertEquals("knox-user", AmbariSessionTokenService.resolveUsername(jwt, props));
  }

  @Test
  public void resolveUsername_nullToken_returnsNull() throws Exception {
    JwtAuthenticationProperties props = new JwtAuthenticationProperties(Collections.emptyMap());
    assertNull(AmbariSessionTokenService.resolveUsername(null, props));
  }

  @Test
  public void resolveUsername_nullProps_treatsAsDefaultConfig() throws Exception {
    SignedJWT jwt = signedTokenWithClaims(java.util.Collections.singletonMap("preferred_username", "alice"));
    assertEquals("alice", AmbariSessionTokenService.resolveUsername(jwt, null));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // mint(...) with configuredUsernameClaim
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  public void mintWithConfiguredClaim_stampsCustomClaim_inAdditionToSubAndPreferredUsername()
      throws Exception {
    String token = AmbariSessionTokenService.mint("alice@corp.example.com", 3600L, KEY_32, "email");
    JWTClaimsSet claims = SignedJWT.parse(token).getJWTClaimsSet();
    assertEquals("alice@corp.example.com", claims.getSubject());
    assertEquals("alice@corp.example.com", claims.getStringClaim("preferred_username"));
    assertEquals("Custom email claim must also be stamped so resolveUsername finds it",
        "alice@corp.example.com", claims.getStringClaim("email"));
  }

  @Test
  public void mintWithConfiguredClaim_doesNothing_whenClaimIsSubOrPreferredUsername()
      throws Exception {
    // No extra claim appended when the configured value is one of the reserved names.
    String token = AmbariSessionTokenService.mint("alice", 3600L, KEY_32, "sub");
    JWTClaimsSet claims = SignedJWT.parse(token).getJWTClaimsSet();
    // The only string claims should still be sub + preferred_username + jti + (optional iss).
    java.util.Set<String> names = new java.util.HashSet<>(claims.getClaims().keySet());
    // Remove the standard claims we expect:
    names.remove("iss");
    names.remove("sub");
    names.remove("preferred_username");
    names.remove("iat");
    names.remove("exp");
    names.remove("jti");
    assertTrue("No extra claims should be stamped when configured = 'sub'; saw " + names,
        names.isEmpty());
  }

  @Test
  public void mintWithConfiguredClaim_doesNothing_whenClaimIsNullOrBlank() throws Exception {
    String token = AmbariSessionTokenService.mint("alice", 3600L, KEY_32, (String) null);
    JWTClaimsSet claims = SignedJWT.parse(token).getJWTClaimsSet();
    // Same shape as the legacy 3-arg mint() call.
    assertEquals("alice", claims.getSubject());
    assertEquals("alice", claims.getStringClaim("preferred_username"));

    String token2 = AmbariSessionTokenService.mint("alice", 3600L, KEY_32, "   ");
    JWTClaimsSet claims2 = SignedJWT.parse(token2).getJWTClaimsSet();
    assertEquals("alice", claims2.getSubject());
    assertEquals("alice", claims2.getStringClaim("preferred_username"));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // mint(...) with additionalClaims map — AMBARI-433 claim forwarding
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  public void mintWithAdditionalClaims_stampsGroupsArray() throws Exception {
    java.util.Map<String, Object> extras = new java.util.LinkedHashMap<>();
    extras.put("groups", java.util.Arrays.asList("hadoop_admins", "hadoop_users"));
    String token = AmbariSessionTokenService.mint("alice", 3600L, KEY_32, extras);
    JWTClaimsSet claims = SignedJWT.parse(token).getJWTClaimsSet();

    Object groups = claims.getClaim("groups");
    assertNotNull("groups claim must be stamped", groups);
    java.util.List<?> groupList = (java.util.List<?>) groups;
    assertEquals(2, groupList.size());
    assertTrue(groupList.contains("hadoop_admins"));
    assertTrue(groupList.contains("hadoop_users"));
  }

  @Test
  public void mintWithAdditionalClaims_silentlyIgnoresServiceControlledClaims() throws Exception {
    // A caller who tries to override iss/sub/exp/jti MUST be ignored — otherwise they
    // could mint a token claiming to be issued by a different authority, expiring later,
    // or impersonating a different sub.
    java.util.Map<String, Object> extras = new java.util.LinkedHashMap<>();
    extras.put("iss", "attacker-controlled");
    extras.put("sub", "evil");
    extras.put("preferred_username", "evil");
    extras.put("exp", 9999999999L);
    extras.put("jti", "fake-id");
    extras.put("iat", 0L);
    extras.put("groups", java.util.Arrays.asList("ok"));

    String token = AmbariSessionTokenService.mint("alice", 60L, KEY_32, extras);
    JWTClaimsSet claims = SignedJWT.parse(token).getJWTClaimsSet();
    assertEquals("Service-controlled iss must be untouched",
        AmbariSessionTokenService.ISSUER, claims.getIssuer());
    assertEquals("sub must remain the real username", "alice", claims.getSubject());
    assertEquals("preferred_username must remain the real username",
        "alice", claims.getStringClaim("preferred_username"));
    assertFalse("jti must be a fresh UUID, not the caller-supplied value",
        "fake-id".equals(claims.getJWTID()));
    // But the non-service claim DID get through.
    assertNotNull(claims.getClaim("groups"));
  }

  @Test
  public void mintWithAdditionalClaims_skipsNullValuesAndBlankNames() throws Exception {
    java.util.Map<String, Object> extras = new java.util.LinkedHashMap<>();
    extras.put("email", null);   // null value -> skipped
    extras.put("", "x");          // empty key -> skipped
    extras.put("   ", "y");       // whitespace key -> skipped (trim()==empty)
    extras.put("ok", "real");

    String token = AmbariSessionTokenService.mint("alice", 60L, KEY_32, extras);
    JWTClaimsSet claims = SignedJWT.parse(token).getJWTClaimsSet();
    assertNull(claims.getClaim("email"));
    assertNull(claims.getClaim(""));
    assertEquals("real", claims.getStringClaim("ok"));
  }

  @Test
  public void mintWithAdditionalClaims_emptyAndNullMaps_behaveLikeLegacy3ArgMint() throws Exception {
    String fromEmpty = AmbariSessionTokenService.mint("alice", 60L, KEY_32, java.util.Collections.<String, Object>emptyMap());
    String fromNull  = AmbariSessionTokenService.mint("alice", 60L, KEY_32, (java.util.Map<String, Object>) null);
    for (String t : java.util.Arrays.asList(fromEmpty, fromNull)) {
      JWTClaimsSet claims = SignedJWT.parse(t).getJWTClaimsSet();
      assertEquals("alice", claims.getSubject());
      assertEquals("alice", claims.getStringClaim("preferred_username"));
      assertEquals(AmbariSessionTokenService.ISSUER, claims.getIssuer());
    }
  }

  /**
   * End-to-end via the service: mint a session token with a custom claim, then resolve the
   * username back from it using the same configured claim.  Asserts the round-trip closes.
   */
  @Test
  public void mintWithConfiguredClaim_roundTripsViaResolveUsername() throws Exception {
    JwtAuthenticationProperties props = new JwtAuthenticationProperties(Collections.emptyMap());
    props.setJwtUsernameClaim("upn");
    props.setOidcClientSecret("client-secret-of-sufficient-length");

    byte[] key = AmbariSessionTokenService.resolveSigningKey(props);
    String token = AmbariSessionTokenService.mint("alice@corp", 3600L, key, "upn");
    SignedJWT parsed = SignedJWT.parse(token);
    assertTrue(AmbariSessionTokenService.verify(parsed, key));
    assertEquals("alice@corp", AmbariSessionTokenService.resolveUsername(parsed, props));
  }

  @Test
  public void mint_doesNotIncludeRawAccessTokenInClaims() throws JOSEException, java.text.ParseException {
    // Regression: the whole point of AMBARI-433 is that we DON'T forward the upstream access
    // token into the cookie.  Make sure no claim accidentally carries the original token value.
    String token = AmbariSessionTokenService.mint("alice", 60L, KEY_32);
    SignedJWT parsed = SignedJWT.parse(token);
    JWTClaimsSet claims = parsed.getJWTClaimsSet();
    assertNull("session JWT MUST NOT carry an access_token claim", claims.getClaim("access_token"));
    assertNull("session JWT MUST NOT carry a refresh_token claim", claims.getClaim("refresh_token"));
  }
}
