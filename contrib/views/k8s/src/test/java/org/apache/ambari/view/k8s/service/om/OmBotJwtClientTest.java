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

package org.apache.ambari.view.k8s.service.om;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure pieces of {@link OmBotJwtClient}: reading a token's lifetime and what KDPS tells the
 * operator about it. The exec-in-pod read itself needs a live cluster.
 */
class OmBotJwtClientTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private static String jwt(String claimsJson) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    private static OmBotJwtClient.BotToken expiringIn(Duration d) {
        String token = jwt("{\"sub\":\"ingestion-bot\",\"isBot\":true,\"exp\":" + NOW.plus(d).getEpochSecond() + "}");
        return new OmBotJwtClient.BotToken(token, OmBotJwtClient.expiryOf(token));
    }

    @Test
    void readsTheExpiryClaim() {
        long exp = NOW.plus(Duration.ofDays(60)).getEpochSecond();
        assertEquals(Instant.ofEpochSecond(exp), OmBotJwtClient.expiryOf(jwt("{\"sub\":\"ingestion-bot\",\"exp\":" + exp + "}")));
    }

    @Test
    void aTokenWithoutExpiryNeverExpires() {
        assertNull(OmBotJwtClient.expiryOf(jwt("{\"sub\":\"ingestion-bot\",\"isBot\":true}")));
        assertNull(OmBotJwtClient.expiryOf("not-a-jwt"));
        assertNull(OmBotJwtClient.expiryOf("eyJ.%%%.sig"));
        assertNull(OmBotJwtClient.expiryOf(null));
    }

    @Test
    void refusesAnExpiredTokenAndSaysWhereToRegenerateIt() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> OmBotJwtClient.requireUsable(expiringIn(Duration.ofDays(-1)), NOW));
        assertTrue(ex.getMessage().contains("Settings > Bots > ingestion-bot"), ex.getMessage());
        assertSaysEverythingStopsAndHowToHandOverTheNewToken(ex.getMessage());
        assertDoesNotThrow(() -> OmBotJwtClient.requireUsable(expiringIn(Duration.ofDays(2)), NOW));
        assertDoesNotThrow(() -> OmBotJwtClient.requireUsable(new OmBotJwtClient.BotToken("eyJ.x.y", null), NOW));
    }

    @Test
    void warnsAboutATokenThatNeverExpires() {
        // OpenMetadata creates the bot with such a token and KDPS cannot rotate it: it is handed over with advice.
        String warning = OmBotJwtClient.neverExpiresWarning(new OmBotJwtClient.BotToken("eyJ.x.y", null));
        assertNotNull(warning);
        assertTrue(warning.contains("never expires"), warning);
        assertTrue(warning.contains("Settings > Bots > ingestion-bot"), warning);
        assertTrue(warning.contains("Re-register Ranger TagSync source"), warning);
        // The token asked for is the shared bot's: its expiry stops every pipeline, not only TagSync.
        assertSaysEverythingStopsAndHowToHandOverTheNewToken(warning);
        assertFalse(warning.contains("eyJ"), "the token never reaches the message");
        assertNull(OmBotJwtClient.neverExpiresWarning(expiringIn(Duration.ofDays(90))));
    }

    @Test
    void warnsAboutATokenThatExpiresSoon() {
        String soon = OmBotJwtClient.lifetimeWarning(expiringIn(Duration.ofDays(10)), NOW);
        assertNotNull(soon);
        assertTrue(soon.contains("expires on"), soon);
        assertSaysEverythingStopsAndHowToHandOverTheNewToken(soon);

        assertNull(OmBotJwtClient.lifetimeWarning(expiringIn(Duration.ofDays(60)), NOW));
        assertNull(OmBotJwtClient.lifetimeWarning(new OmBotJwtClient.BotToken("eyJ.x.y", null), NOW),
                "a token that never expires is reported by neverExpiresWarning");
    }

    /**
     * The ingestion-bot is the identity of every OpenMetadata ingestion pipeline, and OpenMetadata
     * puts its token into a pipeline when it deploys it: a message about its lifetime says that all
     * of them stop, and names each action that hands a regenerated token over.
     */
    private static void assertSaysEverythingStopsAndHowToHandOverTheNewToken(String message) {
        assertTrue(message.contains("every OpenMetadata ingestion pipeline"), message);
        for (String action : new String[] {"Re-register Ranger TagSync source", "Re-register Hive ingestion",
                "Re-register Trino ingestion", "Re-register Atlas federation"}) {
            assertTrue(message.contains(action), action + " missing from: " + message);
        }
        assertTrue(message.contains("redeploys its ingestion pipelines"), message);
    }

    @Test
    void theTokenNeverReachesALogThroughToString() {
        OmBotJwtClient.BotToken token = new OmBotJwtClient.BotToken("eyJ.secret.part", null);
        assertFalse(token.toString().contains("secret"), token.toString());
    }

    @Test
    void theServiceHostIsTheReleaseNameWhateverItIsCalled() {
        for (String release : new String[] {"openmetadata", "datacatalog", "om-prod"}) {
            assertEquals(release, OmBotJwtClient.serviceHost(release));
            String env = OmBotJwtClient.connectionEnv(release, "data", "eyJ.x.y");
            assertTrue(env.contains(" OM_SVC_HOST='" + release + "' "), env);
            assertTrue(env.startsWith("OM_RELEASE='" + release + "' "), env);
            assertTrue(env.contains(" OM_NAMESPACE='data' OM_JWT='eyJ.x.y'"), env);
        }
    }

    @Test
    void theConnectionEnvironmentIsShellQuoted() {
        String env = OmBotJwtClient.connectionEnv("a'b", "ns", "tok");
        assertTrue(env.contains("OM_SVC_HOST='a'\\''b'"), env);
    }
}
