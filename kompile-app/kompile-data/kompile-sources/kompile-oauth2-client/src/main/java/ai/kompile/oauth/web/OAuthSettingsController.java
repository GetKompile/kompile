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

package ai.kompile.oauth.web;

import ai.kompile.oauth.dto.OAuthProviderSettings;
import ai.kompile.oauth.service.OAuthSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for OAuth settings management.
 * Allows configuring OAuth provider credentials from the UI.
 */
@RestController
@RequestMapping("/api/oauth/settings")
public class OAuthSettingsController {

    private static final Logger log = LoggerFactory.getLogger(OAuthSettingsController.class);

    private final OAuthSettingsService settingsService;

    @Autowired
    public OAuthSettingsController(OAuthSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * Get all OAuth provider settings (secrets are masked).
     */
    @GetMapping
    public ResponseEntity<List<OAuthProviderSettings>> getAllSettings() {
        return ResponseEntity.ok(settingsService.getAllSettings());
    }

    /**
     * Get settings for a specific provider (secrets are masked).
     */
    @GetMapping("/{providerId}")
    public ResponseEntity<OAuthProviderSettings> getSettings(@PathVariable String providerId) {
        OAuthProviderSettings settings = settingsService.getSettings(providerId);
        return ResponseEntity.ok(settings.sanitized());
    }

    /**
     * Save or update settings for a provider.
     */
    @PostMapping("/{providerId}")
    public ResponseEntity<OAuthProviderSettings> saveSettings(
            @PathVariable String providerId,
            @RequestBody OAuthProviderSettings settings) {
        try {
            settings.setProviderId(providerId);

            // If the secret is masked (********), keep the existing secret
            if ("********".equals(settings.getClientSecret())) {
                OAuthProviderSettings existing = settingsService.getSettings(providerId);
                settings.setClientSecret(existing.getClientSecret());
            }

            OAuthProviderSettings saved = settingsService.saveSettings(settings);
            log.info("Updated OAuth settings for provider: {}", providerId);

            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            log.error("Failed to save OAuth settings for {}: {}", providerId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Delete settings for a provider (reset to defaults).
     */
    @DeleteMapping("/{providerId}")
    public ResponseEntity<Map<String, Object>> deleteSettings(@PathVariable String providerId) {
        try {
            settingsService.deleteSettings(providerId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Settings deleted for " + providerId
            ));
        } catch (Exception e) {
            log.error("Failed to delete OAuth settings for {}: {}", providerId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Test if the current settings would allow configuration.
     */
    @GetMapping("/{providerId}/validate")
    public ResponseEntity<Map<String, Object>> validateSettings(@PathVariable String providerId) {
        boolean configured = settingsService.isConfigured(providerId);
        return ResponseEntity.ok(Map.of(
                "providerId", providerId,
                "configured", configured,
                "message", configured ? "Provider is properly configured" : "Provider needs configuration"
        ));
    }

    /**
     * Get provider setup information.
     */
    @GetMapping("/setup-info")
    public ResponseEntity<List<ProviderSetupInfo>> getSetupInfo() {
        List<ProviderSetupInfo> info = List.of(
                new ProviderSetupInfo(
                        "google",
                        "Google",
                        "Connect to Google Drive, Gmail, and other Google services",
                        "https://console.cloud.google.com",
                        List.of(
                                "Go to Google Cloud Console",
                                "Create a new project or select existing",
                                "Enable Google Drive API and Gmail API",
                                "Go to 'APIs & Services' > 'Credentials'",
                                "Create OAuth 2.0 credentials (Web application)",
                                "Add authorized redirect URI"
                        ),
                        "/api/oauth/google/callback",
                        List.of("https://www.googleapis.com/auth/drive.readonly", "https://www.googleapis.com/auth/gmail.readonly", "email", "profile")
                ),
                new ProviderSetupInfo(
                        "microsoft",
                        "Microsoft",
                        "Connect to OneDrive, SharePoint, and Outlook",
                        "https://portal.azure.com",
                        List.of(
                                "Go to Azure Portal > Azure Active Directory",
                                "Navigate to 'App registrations' > 'New registration'",
                                "Set supported account types (usually 'Any Azure AD directory')",
                                "Add redirect URI as 'Web' platform",
                                "Go to 'Certificates & secrets' > 'New client secret'",
                                "Go to 'API permissions' > Add: Files.Read, User.Read"
                        ),
                        "/api/oauth/microsoft/callback",
                        List.of("Files.Read", "User.Read", "offline_access")
                ),
                new ProviderSetupInfo(
                        "atlassian",
                        "Atlassian",
                        "Connect to Jira and Confluence",
                        "https://developer.atlassian.com/console/myapps/",
                        List.of(
                                "Go to Atlassian Developer Console",
                                "Create a new OAuth 2.0 app",
                                "Set app name and agree to terms",
                                "Go to 'Permissions' and add Jira/Confluence scopes",
                                "Go to 'Authorization' > 'Add' OAuth 2.0",
                                "Set callback URL"
                        ),
                        "/api/oauth/atlassian/callback",
                        List.of("read:confluence-content.all", "read:jira-work", "read:jira-user", "offline_access")
                ),
                new ProviderSetupInfo(
                        "notion",
                        "Notion",
                        "Connect to Notion workspaces and pages",
                        "https://www.notion.so/my-integrations",
                        List.of(
                                "Go to Notion Integrations page",
                                "Click 'Create new integration'",
                                "Select 'Public' integration type",
                                "Fill in basic information",
                                "Under 'OAuth Domain & URIs', add redirect URI",
                                "Copy the OAuth client ID and secret"
                        ),
                        "/api/oauth/notion/callback",
                        List.of()
                ),
                new ProviderSetupInfo(
                        "slack",
                        "Slack",
                        "Connect to Slack workspaces and channels",
                        "https://api.slack.com/apps",
                        List.of(
                                "Go to Slack API Apps page",
                                "Click 'Create New App' > 'From scratch'",
                                "Set app name and select workspace",
                                "Go to 'OAuth & Permissions'",
                                "Add redirect URL",
                                "Under 'Scopes' > 'User Token Scopes', add required scopes"
                        ),
                        "/api/oauth/slack/callback",
                        List.of("channels:history", "channels:read", "users:read")
                )
        );

        return ResponseEntity.ok(info);
    }

    /**
     * DTO for provider setup information.
     */
    public record ProviderSetupInfo(
            String providerId,
            String displayName,
            String description,
            String consoleUrl,
            List<String> setupSteps,
            String callbackPath,
            List<String> defaultScopes
    ) {}
}
