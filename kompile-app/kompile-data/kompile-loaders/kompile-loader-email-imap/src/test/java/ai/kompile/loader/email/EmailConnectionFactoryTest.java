/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.loader.email;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class EmailConnectionFactoryTest {

    private EmailConnectionFactory factory;

    @BeforeEach
    void setUp() {
        factory = new EmailConnectionFactory();
    }

    // ── buildXOAuth2Token ────────────────────────────────────────────────

    @Test
    void buildXOAuth2TokenFormatsCorrectly() {
        String token = factory.buildXOAuth2Token("user@gmail.com", "ya29.access-token");

        // Decode and verify the SASL XOAUTH2 format
        String decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
        assertEquals("user=user@gmail.com\001auth=Bearer ya29.access-token\001\001", decoded);
    }

    @Test
    void buildXOAuth2TokenIsBase64Encoded() {
        String token = factory.buildXOAuth2Token("test@example.com", "tok");

        // Should not throw — valid Base64
        byte[] decoded = Base64.getDecoder().decode(token);
        assertNotNull(decoded);
        assertTrue(decoded.length > 0);
    }

    @Test
    void buildXOAuth2TokenHandlesSpecialCharactersInEmail() {
        String token = factory.buildXOAuth2Token("user+tag@sub.domain.com", "token-with-chars_123");

        String decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
        assertTrue(decoded.startsWith("user=user+tag@sub.domain.com\001"));
        assertTrue(decoded.contains("auth=Bearer token-with-chars_123\001\001"));
    }

    @Test
    void buildXOAuth2TokenHandlesEmptyEmail() {
        String token = factory.buildXOAuth2Token("", "token");

        String decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
        assertEquals("user=\001auth=Bearer token\001\001", decoded);
    }

    // ── testConnection (negative path) ───────────────────────────────────

    @Test
    void testConnectionReturnsFalseForInvalidHost() {
        EmailConnectionConfig config = EmailConnectionConfig.builder()
                .host("nonexistent.invalid.host.example")
                .port(993)
                .username("user")
                .password("pass")
                .connectionTimeout(2000)
                .readTimeout(2000)
                .build();

        assertFalse(factory.testConnection(config));
    }

    @Test
    void testConnectionReturnsFalseForNullHost() {
        EmailConnectionConfig config = EmailConnectionConfig.builder()
                .port(993)
                .username("user")
                .password("pass")
                .connectionTimeout(1000)
                .readTimeout(1000)
                .build();

        assertFalse(factory.testConnection(config));
    }

    // ── listFolders for POP3 ─────────────────────────────────────────────

    @Test
    void listFoldersReturnInboxForPop3() throws Exception {
        EmailConnectionConfig config = EmailConnectionConfig.builder()
                .protocol(EmailConnectionConfig.Protocol.POP3)
                .build();

        String[] folders = factory.listFolders(config);
        assertArrayEquals(new String[]{"INBOX"}, folders);
    }
}
