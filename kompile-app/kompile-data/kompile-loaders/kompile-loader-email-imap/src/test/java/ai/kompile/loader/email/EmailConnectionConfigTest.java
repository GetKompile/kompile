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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmailConnectionConfigTest {

    // ── Builder defaults ─────────────────────────────────────────────────

    @Test
    void builderSetsCorrectDefaults() {
        EmailConnectionConfig config = EmailConnectionConfig.builder().build();

        assertEquals(EmailConnectionConfig.Protocol.IMAP, config.getProtocol());
        assertEquals(993, config.getPort());
        assertEquals(EmailConnectionConfig.Security.SSL, config.getSecurity());
        assertEquals(EmailConnectionConfig.AuthMode.BASIC, config.getAuthMode());
        assertEquals(1000, config.getMessageLimit());
        assertTrue(config.isIncludeAttachments());
        assertFalse(config.isIncludeHtmlBody());
        assertEquals(30000, config.getConnectionTimeout());
        assertEquals(60000, config.getReadTimeout());
        assertNull(config.getHost());
        assertNull(config.getUsername());
        assertNull(config.getPassword());
        assertNull(config.getAccessToken());
        assertNull(config.getRefreshToken());
        assertNull(config.getFolders());
        assertNull(config.getStartDate());
        assertNull(config.getEndDate());
    }

    // ── getEffectivePort ─────────────────────────────────────────────────

    @Test
    void effectivePortReturnsConfiguredPortWhenPositive() {
        EmailConnectionConfig config = EmailConnectionConfig.builder()
                .port(12345)
                .build();
        assertEquals(12345, config.getEffectivePort());
    }

    @Test
    void effectivePortReturns993ForImapSsl() {
        EmailConnectionConfig config = EmailConnectionConfig.builder()
                .port(0)
                .protocol(EmailConnectionConfig.Protocol.IMAP)
                .security(EmailConnectionConfig.Security.SSL)
                .build();
        assertEquals(993, config.getEffectivePort());
    }

    @Test
    void effectivePortReturns993ForImapTls() {
        EmailConnectionConfig config = EmailConnectionConfig.builder()
                .port(0)
                .protocol(EmailConnectionConfig.Protocol.IMAP)
                .security(EmailConnectionConfig.Security.TLS)
                .build();
        assertEquals(993, config.getEffectivePort());
    }

    @Test
    void effectivePortReturns143ForImapStarttls() {
        EmailConnectionConfig config = EmailConnectionConfig.builder()
                .port(0)
                .protocol(EmailConnectionConfig.Protocol.IMAP)
                .security(EmailConnectionConfig.Security.STARTTLS)
                .build();
        assertEquals(143, config.getEffectivePort());
    }

    @Test
    void effectivePortReturns143ForImapNone() {
        EmailConnectionConfig config = EmailConnectionConfig.builder()
                .port(0)
                .protocol(EmailConnectionConfig.Protocol.IMAP)
                .security(EmailConnectionConfig.Security.NONE)
                .build();
        assertEquals(143, config.getEffectivePort());
    }

    @Test
    void effectivePortReturns995ForPop3Ssl() {
        EmailConnectionConfig config = EmailConnectionConfig.builder()
                .port(0)
                .protocol(EmailConnectionConfig.Protocol.POP3)
                .security(EmailConnectionConfig.Security.SSL)
                .build();
        assertEquals(995, config.getEffectivePort());
    }

    @Test
    void effectivePortReturns995ForPop3Tls() {
        EmailConnectionConfig config = EmailConnectionConfig.builder()
                .port(0)
                .protocol(EmailConnectionConfig.Protocol.POP3)
                .security(EmailConnectionConfig.Security.TLS)
                .build();
        assertEquals(995, config.getEffectivePort());
    }

    @Test
    void effectivePortReturns110ForPop3None() {
        EmailConnectionConfig config = EmailConnectionConfig.builder()
                .port(0)
                .protocol(EmailConnectionConfig.Protocol.POP3)
                .security(EmailConnectionConfig.Security.NONE)
                .build();
        assertEquals(110, config.getEffectivePort());
    }

    @Test
    void effectivePortReturns110ForPop3Starttls() {
        EmailConnectionConfig config = EmailConnectionConfig.builder()
                .port(0)
                .protocol(EmailConnectionConfig.Protocol.POP3)
                .security(EmailConnectionConfig.Security.STARTTLS)
                .build();
        assertEquals(110, config.getEffectivePort());
    }

    // ── isOAuth2 ─────────────────────────────────────────────────────────

    @Test
    void isOAuth2ReturnsTrueForGoogle() {
        EmailConnectionConfig config = EmailConnectionConfig.builder()
                .authMode(EmailConnectionConfig.AuthMode.OAUTH2_GOOGLE)
                .build();
        assertTrue(config.isOAuth2());
    }

    @Test
    void isOAuth2ReturnsTrueForMicrosoft() {
        EmailConnectionConfig config = EmailConnectionConfig.builder()
                .authMode(EmailConnectionConfig.AuthMode.OAUTH2_MICROSOFT)
                .build();
        assertTrue(config.isOAuth2());
    }

    @Test
    void isOAuth2ReturnsFalseForBasic() {
        EmailConnectionConfig config = EmailConnectionConfig.builder()
                .authMode(EmailConnectionConfig.AuthMode.BASIC)
                .build();
        assertFalse(config.isOAuth2());
    }

    @Test
    void isOAuth2ReturnsFalseForAppPassword() {
        EmailConnectionConfig config = EmailConnectionConfig.builder()
                .authMode(EmailConnectionConfig.AuthMode.APP_PASSWORD)
                .build();
        assertFalse(config.isOAuth2());
    }

    // ── getEffectiveFolders ──────────────────────────────────────────────

    @Test
    void effectiveFoldersDefaultsToInbox() {
        EmailConnectionConfig config = EmailConnectionConfig.builder().build();
        assertEquals(List.of("INBOX"), config.getEffectiveFolders());
    }

    @Test
    void effectiveFoldersDefaultsToInboxWhenNull() {
        EmailConnectionConfig config = EmailConnectionConfig.builder()
                .folders(null)
                .build();
        assertEquals(List.of("INBOX"), config.getEffectiveFolders());
    }

    @Test
    void effectiveFoldersDefaultsToInboxWhenEmpty() {
        EmailConnectionConfig config = EmailConnectionConfig.builder()
                .folders(List.of())
                .build();
        assertEquals(List.of("INBOX"), config.getEffectiveFolders());
    }

    @Test
    void effectiveFoldersReturnsConfiguredFolders() {
        List<String> folders = List.of("INBOX", "Sent", "Archive");
        EmailConnectionConfig config = EmailConnectionConfig.builder()
                .folders(folders)
                .build();
        assertEquals(folders, config.getEffectiveFolders());
    }

    // ── Factory methods ──────────────────────────────────────────────────

    @Test
    void gmailOAuthCreatesCorrectConfig() {
        EmailConnectionConfig config = EmailConnectionConfig.gmailOAuth("user@gmail.com", "token123");

        assertEquals(EmailConnectionConfig.Protocol.IMAP, config.getProtocol());
        assertEquals("imap.gmail.com", config.getHost());
        assertEquals(993, config.getPort());
        assertEquals(EmailConnectionConfig.Security.SSL, config.getSecurity());
        assertEquals(EmailConnectionConfig.AuthMode.OAUTH2_GOOGLE, config.getAuthMode());
        assertEquals("user@gmail.com", config.getUsername());
        assertEquals("token123", config.getAccessToken());
        assertTrue(config.isOAuth2());
    }

    @Test
    void gmailAppPasswordCreatesCorrectConfig() {
        EmailConnectionConfig config = EmailConnectionConfig.gmailAppPassword("user@gmail.com", "abcd-efgh-ijkl");

        assertEquals(EmailConnectionConfig.Protocol.IMAP, config.getProtocol());
        assertEquals("imap.gmail.com", config.getHost());
        assertEquals(993, config.getPort());
        assertEquals(EmailConnectionConfig.Security.SSL, config.getSecurity());
        assertEquals(EmailConnectionConfig.AuthMode.APP_PASSWORD, config.getAuthMode());
        assertEquals("user@gmail.com", config.getUsername());
        assertEquals("abcd-efgh-ijkl", config.getPassword());
        assertFalse(config.isOAuth2());
    }

    @Test
    void microsoftOAuthCreatesCorrectConfig() {
        EmailConnectionConfig config = EmailConnectionConfig.microsoftOAuth("user@outlook.com", "ms-token");

        assertEquals(EmailConnectionConfig.Protocol.IMAP, config.getProtocol());
        assertEquals("outlook.office365.com", config.getHost());
        assertEquals(993, config.getPort());
        assertEquals(EmailConnectionConfig.Security.SSL, config.getSecurity());
        assertEquals(EmailConnectionConfig.AuthMode.OAUTH2_MICROSOFT, config.getAuthMode());
        assertEquals("user@outlook.com", config.getUsername());
        assertEquals("ms-token", config.getAccessToken());
        assertTrue(config.isOAuth2());
    }
}
