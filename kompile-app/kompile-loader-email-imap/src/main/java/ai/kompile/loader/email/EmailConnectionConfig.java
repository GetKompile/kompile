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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Configuration for connecting to an email server via IMAP or POP3.
 * Supports multiple authentication modes including OAuth2 for Gmail and Microsoft 365.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailConnectionConfig {

    /**
     * Email protocol to use for connection.
     */
    public enum Protocol {
        IMAP,   // Internet Message Access Protocol - supports folders, search, flags
        POP3    // Post Office Protocol 3 - simple download-only
    }

    /**
     * Connection security mode.
     */
    public enum Security {
        SSL,        // SSL/TLS connection (recommended, ports 993/995)
        TLS,        // Same as SSL (alias)
        STARTTLS,   // Upgrade to TLS after connection (ports 143/110)
        NONE        // No encryption (not recommended)
    }

    /**
     * Authentication mode.
     */
    public enum AuthMode {
        BASIC,              // Standard username/password
        APP_PASSWORD,       // App-specific password (Gmail, Yahoo, etc.)
        OAUTH2_GOOGLE,      // Google OAuth 2.0 with XOAUTH2 SASL
        OAUTH2_MICROSOFT    // Microsoft OAuth 2.0 with XOAUTH2 SASL
    }

    /**
     * Protocol to use (IMAP or POP3).
     */
    @Builder.Default
    private Protocol protocol = Protocol.IMAP;

    /**
     * Mail server hostname.
     * Examples: imap.gmail.com, outlook.office365.com, mail.example.com
     */
    private String host;

    /**
     * Mail server port.
     * Defaults: 993 (IMAPS), 995 (POP3S), 143 (IMAP), 110 (POP3)
     */
    @Builder.Default
    private int port = 993;

    /**
     * Connection security mode.
     */
    @Builder.Default
    private Security security = Security.SSL;

    /**
     * Authentication mode to use.
     */
    @Builder.Default
    private AuthMode authMode = AuthMode.BASIC;

    /**
     * Username or email address for authentication.
     */
    private String username;

    /**
     * Password or app password for BASIC/APP_PASSWORD auth modes.
     */
    private String password;

    /**
     * OAuth2 access token for OAUTH2_GOOGLE/OAUTH2_MICROSOFT auth modes.
     */
    private String accessToken;

    /**
     * OAuth2 refresh token for token refresh operations.
     */
    private String refreshToken;

    /**
     * Folders to fetch emails from (IMAP only).
     * If empty, defaults to INBOX.
     */
    private List<String> folders;

    /**
     * Only fetch emails sent/received after this date.
     */
    private LocalDateTime startDate;

    /**
     * Only fetch emails sent/received before this date.
     */
    private LocalDateTime endDate;

    /**
     * Maximum number of messages to fetch (0 = no limit).
     */
    @Builder.Default
    private int messageLimit = 1000;

    /**
     * Whether to include email attachments.
     */
    @Builder.Default
    private boolean includeAttachments = true;

    /**
     * Whether to process HTML body (convert to text).
     */
    @Builder.Default
    private boolean includeHtmlBody = false;

    /**
     * Connection timeout in milliseconds.
     */
    @Builder.Default
    private int connectionTimeout = 30000;

    /**
     * Read timeout in milliseconds.
     */
    @Builder.Default
    private int readTimeout = 60000;

    /**
     * Gets the default port based on protocol and security settings.
     */
    public int getEffectivePort() {
        if (port > 0) {
            return port;
        }

        if (protocol == Protocol.IMAP) {
            return (security == Security.SSL || security == Security.TLS) ? 993 : 143;
        } else {
            return (security == Security.SSL || security == Security.TLS) ? 995 : 110;
        }
    }

    /**
     * Checks if OAuth2 authentication is being used.
     */
    public boolean isOAuth2() {
        return authMode == AuthMode.OAUTH2_GOOGLE || authMode == AuthMode.OAUTH2_MICROSOFT;
    }

    /**
     * Gets the effective folders list, defaulting to INBOX if empty.
     */
    public List<String> getEffectiveFolders() {
        if (folders == null || folders.isEmpty()) {
            return List.of("INBOX");
        }
        return folders;
    }

    /**
     * Creates a preset configuration for Gmail with OAuth.
     */
    public static EmailConnectionConfig gmailOAuth(String email, String accessToken) {
        return EmailConnectionConfig.builder()
                .protocol(Protocol.IMAP)
                .host("imap.gmail.com")
                .port(993)
                .security(Security.SSL)
                .authMode(AuthMode.OAUTH2_GOOGLE)
                .username(email)
                .accessToken(accessToken)
                .build();
    }

    /**
     * Creates a preset configuration for Gmail with app password.
     */
    public static EmailConnectionConfig gmailAppPassword(String email, String appPassword) {
        return EmailConnectionConfig.builder()
                .protocol(Protocol.IMAP)
                .host("imap.gmail.com")
                .port(993)
                .security(Security.SSL)
                .authMode(AuthMode.APP_PASSWORD)
                .username(email)
                .password(appPassword)
                .build();
    }

    /**
     * Creates a preset configuration for Microsoft 365 with OAuth.
     */
    public static EmailConnectionConfig microsoftOAuth(String email, String accessToken) {
        return EmailConnectionConfig.builder()
                .protocol(Protocol.IMAP)
                .host("outlook.office365.com")
                .port(993)
                .security(Security.SSL)
                .authMode(AuthMode.OAUTH2_MICROSOFT)
                .username(email)
                .accessToken(accessToken)
                .build();
    }
}
