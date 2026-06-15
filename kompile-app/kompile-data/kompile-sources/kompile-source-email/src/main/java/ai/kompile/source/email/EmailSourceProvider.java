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

package ai.kompile.source.email;

import ai.kompile.core.source.provider.SourceFormField;
import ai.kompile.core.source.provider.SourceProvider;
import ai.kompile.oauth.dto.OAuthConnectionStatus;
import ai.kompile.oauth.service.OAuthConnectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;

/**
 * Source provider for email via IMAP and POP3.
 * Supports Gmail OAuth, Microsoft 365 OAuth, and standard password/app password authentication.
 */
public class EmailSourceProvider implements SourceProvider {

    @Value("${kompile.email.enabled:true}")
    private boolean enabled;

    @Value("${kompile.oauth.google.client-id:}")
    private String googleClientId;

    @Value("${kompile.oauth.microsoft.client-id:}")
    private String microsoftClientId;

    private final OAuthConnectionService oauthService;

    @Autowired
    public EmailSourceProvider(@Autowired(required = false) OAuthConnectionService oauthService) {
        this.oauthService = oauthService;
    }

    @Override
    public String getId() {
        return "email";
    }

    @Override
    public String getDisplayName() {
        return "Email (IMAP/POP3)";
    }

    @Override
    public String getDescription() {
        return "Import emails from IMAP or POP3 mail servers including Gmail and Microsoft 365";
    }

    @Override
    public String getIcon() {
        return "email";
    }

    @Override
    public String getCategory() {
        return "collaboration";
    }

    @Override
    public int getOrder() {
        return 5;
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public String getUnavailableReason() {
        if (!enabled) {
            return "Email integration is disabled. Set kompile.email.enabled=true to enable.";
        }
        return null;
    }

    @Override
    public boolean requiresAuth() {
        // Auth is always required for email
        // Check if OAuth is already connected
        if (oauthService != null) {
            OAuthConnectionStatus googleStatus = oauthService.getConnectionStatus("google");
            OAuthConnectionStatus microsoftStatus = oauthService.getConnectionStatus("microsoft");
            if (googleStatus.isConnected() || microsoftStatus.isConnected()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String getAuthType() {
        // Mixed auth type - supports OAuth and password
        return "mixed";
    }

    @Override
    public String getOAuthProvider() {
        // Multiple providers possible, return null to indicate mixed
        return null;
    }

    @Override
    public boolean supportsBatch() {
        return true;
    }

    @Override
    public Map<String, Object> getConfiguration() {
        Map<String, Object> config = new HashMap<>();

        // Google OAuth status
        config.put("googleOAuthConfigured", googleClientId != null && !googleClientId.isEmpty());
        if (oauthService != null) {
            OAuthConnectionStatus googleStatus = oauthService.getConnectionStatus("google");
            config.put("googleOAuthConnected", googleStatus.isConnected());
            config.put("googleOAuthStatus", googleStatus.getStatus().name());
            if (googleStatus.getUserEmail() != null) {
                config.put("googleConnectedEmail", googleStatus.getUserEmail());
            }
        } else {
            config.put("googleOAuthConnected", false);
            config.put("googleOAuthStatus", "NOT_CONFIGURED");
        }

        // Microsoft OAuth status
        config.put("microsoftOAuthConfigured", microsoftClientId != null && !microsoftClientId.isEmpty());
        if (oauthService != null) {
            OAuthConnectionStatus microsoftStatus = oauthService.getConnectionStatus("microsoft");
            config.put("microsoftOAuthConnected", microsoftStatus.isConnected());
            config.put("microsoftOAuthStatus", microsoftStatus.getStatus().name());
            if (microsoftStatus.getUserEmail() != null) {
                config.put("microsoftConnectedEmail", microsoftStatus.getUserEmail());
            }
        } else {
            config.put("microsoftOAuthConnected", false);
            config.put("microsoftOAuthStatus", "NOT_CONFIGURED");
        }

        // Preset server configurations
        config.put("presets", Map.of(
                "gmail", Map.of(
                        "host", "imap.gmail.com",
                        "port", 993,
                        "security", "SSL"
                ),
                "microsoft", Map.of(
                        "host", "outlook.office365.com",
                        "port", 993,
                        "security", "SSL"
                ),
                "yahoo", Map.of(
                        "host", "imap.mail.yahoo.com",
                        "port", 993,
                        "security", "SSL"
                ),
                "icloud", Map.of(
                        "host", "imap.mail.me.com",
                        "port", 993,
                        "security", "SSL"
                )
        ));

        return config;
    }

    @Override
    public List<SourceFormField> getFormFields() {
        List<SourceFormField> fields = new ArrayList<>();

        // Authentication method selection
        List<SourceFormField.SelectOption> authOptions = new ArrayList<>();
        authOptions.add(SourceFormField.SelectOption.builder()
                .value("oauth_google")
                .label("Gmail (OAuth)")
                .description("Recommended for Gmail accounts")
                .build());
        authOptions.add(SourceFormField.SelectOption.builder()
                .value("oauth_microsoft")
                .label("Microsoft 365 (OAuth)")
                .description("Recommended for Outlook/Exchange accounts")
                .build());
        authOptions.add(SourceFormField.SelectOption.builder()
                .value("app_password")
                .label("App Password")
                .description("For Gmail, Yahoo, iCloud with 2FA enabled")
                .build());
        authOptions.add(SourceFormField.SelectOption.builder()
                .value("basic")
                .label("Username/Password")
                .description("Standard IMAP/POP3 authentication")
                .build());

        fields.add(SourceFormField.builder()
                .id("authMode")
                .label("Authentication Method")
                .type(SourceFormField.FieldType.SELECT)
                .required(true)
                .options(authOptions)
                .defaultValue("oauth_google")
                .helpText("Select how to authenticate with the mail server")
                .order(1)
                .build());

        // Protocol selection (shown for non-OAuth)
        List<SourceFormField.SelectOption> protocolOptions = Arrays.asList(
                SourceFormField.SelectOption.builder()
                        .value("IMAP")
                        .label("IMAP")
                        .description("Recommended - supports folders, search, and sync")
                        .build(),
                SourceFormField.SelectOption.builder()
                        .value("POP3")
                        .label("POP3")
                        .description("Simple download-only protocol")
                        .build()
        );

        fields.add(SourceFormField.builder()
                .id("protocol")
                .label("Protocol")
                .type(SourceFormField.FieldType.SELECT)
                .required(true)
                .options(protocolOptions)
                .defaultValue("IMAP")
                .showWhen(Map.of("authMode", Arrays.asList("app_password", "basic")))
                .order(2)
                .build());

        // Server configuration (shown for non-OAuth)
        fields.add(SourceFormField.builder()
                .id("host")
                .label("Mail Server")
                .type(SourceFormField.FieldType.TEXT)
                .placeholder("imap.example.com")
                .helpText("IMAP/POP3 server hostname")
                .prefixIcon("dns")
                .showWhen(Map.of("authMode", Arrays.asList("app_password", "basic")))
                .order(3)
                .build());

        fields.add(SourceFormField.builder()
                .id("port")
                .label("Port")
                .type(SourceFormField.FieldType.NUMBER)
                .defaultValue(993)
                .min(1)
                .max(65535)
                .helpText("993 for IMAPS, 995 for POP3S, 143 for IMAP, 110 for POP3")
                .showWhen(Map.of("authMode", Arrays.asList("app_password", "basic")))
                .order(4)
                .build());

        // Security selection
        List<SourceFormField.SelectOption> securityOptions = Arrays.asList(
                SourceFormField.SelectOption.builder()
                        .value("SSL")
                        .label("SSL/TLS")
                        .description("Secure connection (recommended)")
                        .build(),
                SourceFormField.SelectOption.builder()
                        .value("STARTTLS")
                        .label("STARTTLS")
                        .description("Upgrade to TLS after connection")
                        .build(),
                SourceFormField.SelectOption.builder()
                        .value("NONE")
                        .label("None")
                        .description("No encryption (not recommended)")
                        .disabled(false)
                        .build()
        );

        fields.add(SourceFormField.builder()
                .id("security")
                .label("Security")
                .type(SourceFormField.FieldType.SELECT)
                .options(securityOptions)
                .defaultValue("SSL")
                .showWhen(Map.of("authMode", Arrays.asList("app_password", "basic")))
                .order(5)
                .build());

        // Credentials
        fields.add(SourceFormField.builder()
                .id("email")
                .label("Email Address")
                .type(SourceFormField.FieldType.EMAIL)
                .required(true)
                .placeholder("user@example.com")
                .prefixIcon("email")
                .showWhen(Map.of("authMode", Arrays.asList("app_password", "basic")))
                .order(6)
                .build());

        fields.add(SourceFormField.builder()
                .id("password")
                .label("Password / App Password")
                .type(SourceFormField.FieldType.PASSWORD)
                .required(true)
                .helpText("For Gmail: Generate an App Password at myaccount.google.com/apppasswords")
                .prefixIcon("key")
                .showWhen(Map.of("authMode", Arrays.asList("app_password", "basic")))
                .order(7)
                .build());

        // Folder selection (IMAP only)
        List<SourceFormField.SelectOption> folderOptions = Arrays.asList(
                SourceFormField.SelectOption.builder()
                        .value("INBOX")
                        .label("Inbox")
                        .build(),
                SourceFormField.SelectOption.builder()
                        .value("Sent")
                        .label("Sent")
                        .build(),
                SourceFormField.SelectOption.builder()
                        .value("Drafts")
                        .label("Drafts")
                        .build(),
                SourceFormField.SelectOption.builder()
                        .value("[Gmail]/All Mail")
                        .label("All Mail (Gmail)")
                        .build(),
                SourceFormField.SelectOption.builder()
                        .value("[Gmail]/Starred")
                        .label("Starred (Gmail)")
                        .build(),
                SourceFormField.SelectOption.builder()
                        .value("[Gmail]/Important")
                        .label("Important (Gmail)")
                        .build()
        );

        fields.add(SourceFormField.builder()
                .id("folders")
                .label("Folders")
                .type(SourceFormField.FieldType.MULTI_SELECT)
                .options(folderOptions)
                .defaultValue(Arrays.asList("INBOX"))
                .helpText("Select folders to import from (IMAP only)")
                .order(8)
                .build());

        // Date filters
        fields.add(SourceFormField.builder()
                .id("startDate")
                .label("From Date")
                .type(SourceFormField.FieldType.DATE)
                .helpText("Only import emails after this date")
                .group("filters")
                .order(9)
                .build());

        fields.add(SourceFormField.builder()
                .id("endDate")
                .label("To Date")
                .type(SourceFormField.FieldType.DATE)
                .helpText("Only import emails before this date")
                .group("filters")
                .order(10)
                .build());

        // Message limit
        fields.add(SourceFormField.builder()
                .id("messageLimit")
                .label("Message Limit")
                .type(SourceFormField.FieldType.NUMBER)
                .defaultValue(1000)
                .min(1)
                .max(100000)
                .helpText("Maximum number of emails to import")
                .group("filters")
                .order(11)
                .build());

        // Options
        fields.add(SourceFormField.builder()
                .id("includeAttachments")
                .label("Include Attachments")
                .type(SourceFormField.FieldType.TOGGLE)
                .defaultValue(true)
                .helpText("Extract text from PDF, Word, and other document attachments")
                .group("options")
                .order(12)
                .build());

        fields.add(SourceFormField.builder()
                .id("includeHtml")
                .label("Process HTML Content")
                .type(SourceFormField.FieldType.TOGGLE)
                .defaultValue(false)
                .helpText("Convert HTML email bodies to formatted text")
                .group("options")
                .order(13)
                .build());

        return fields;
    }
}
