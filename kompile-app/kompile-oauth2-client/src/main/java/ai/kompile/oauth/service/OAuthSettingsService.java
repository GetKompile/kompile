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

package ai.kompile.oauth.service;

import ai.kompile.oauth.dto.OAuthProviderSettings;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing OAuth provider settings.
 * Settings are persisted to a JSON file in the Kompile config directory.
 * Client secrets are stored encrypted.
 */
@Service
public class OAuthSettingsService {

    private static final Logger log = LoggerFactory.getLogger(OAuthSettingsService.class);
    private static final String SETTINGS_FILE = "oauth-settings.json";

    private final TokenEncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    @Value("${kompile.data.dir:${user.home}/.kompile}")
    private String kompileDataDir;

    // Default settings from application properties (as fallback)
    @Value("${kompile.oauth.google.client-id:}")
    private String googleClientId;
    @Value("${kompile.oauth.google.client-secret:}")
    private String googleClientSecret;
    @Value("${kompile.oauth.google.scopes:https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/gmail.readonly email profile}")
    private String googleScopes;

    @Value("${kompile.oauth.microsoft.client-id:}")
    private String microsoftClientId;
    @Value("${kompile.oauth.microsoft.client-secret:}")
    private String microsoftClientSecret;
    @Value("${kompile.oauth.microsoft.tenant-id:common}")
    private String microsoftTenantId;
    @Value("${kompile.oauth.microsoft.scopes:Files.Read User.Read offline_access}")
    private String microsoftScopes;

    @Value("${kompile.oauth.atlassian.client-id:}")
    private String atlassianClientId;
    @Value("${kompile.oauth.atlassian.client-secret:}")
    private String atlassianClientSecret;
    @Value("${kompile.oauth.atlassian.scopes:read:confluence-content.all read:jira-work read:jira-user offline_access}")
    private String atlassianScopes;

    @Value("${kompile.oauth.notion.client-id:}")
    private String notionClientId;
    @Value("${kompile.oauth.notion.client-secret:}")
    private String notionClientSecret;

    @Value("${kompile.oauth.slack.client-id:}")
    private String slackClientId;
    @Value("${kompile.oauth.slack.client-secret:}")
    private String slackClientSecret;
    @Value("${kompile.oauth.slack.scopes:channels:history channels:read users:read}")
    private String slackScopes;

    // In-memory cache of settings
    private final Map<String, OAuthProviderSettings> settingsCache = new ConcurrentHashMap<>();

    // Listeners for settings changes
    private final List<SettingsChangeListener> changeListeners = new ArrayList<>();

    public OAuthSettingsService(TokenEncryptionService encryptionService, ObjectMapper objectMapper) {
        this.encryptionService = encryptionService;
        this.objectMapper = objectMapper.copy();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() {
        loadSettings();
    }

    /**
     * Get settings for a specific provider.
     */
    public OAuthProviderSettings getSettings(String providerId) {
        OAuthProviderSettings settings = settingsCache.get(providerId);
        if (settings == null) {
            // Return default settings with empty credentials
            settings = createDefaultSettings(providerId);
        }
        return settings;
    }

    /**
     * Get all provider settings (sanitized - secrets masked).
     */
    public List<OAuthProviderSettings> getAllSettings() {
        List<OAuthProviderSettings> allSettings = new ArrayList<>();
        for (String providerId : getProviderIds()) {
            OAuthProviderSettings settings = getSettings(providerId);
            allSettings.add(settings.sanitized());
        }
        return allSettings;
    }

    /**
     * Save settings for a provider.
     */
    public OAuthProviderSettings saveSettings(OAuthProviderSettings settings) {
        if (settings.getProviderId() == null) {
            throw new IllegalArgumentException("Provider ID is required");
        }

        // Update configured status
        settings.setConfigured(settings.hasValidCredentials());
        settings.setLastUpdated(System.currentTimeMillis());

        // Store in cache
        settingsCache.put(settings.getProviderId(), settings);

        // Persist to file
        persistSettings();

        // Notify listeners
        notifyListeners(settings.getProviderId(), settings);

        log.info("Saved OAuth settings for provider: {}", settings.getProviderId());

        return settings.sanitized();
    }

    /**
     * Delete settings for a provider (reset to defaults).
     */
    public void deleteSettings(String providerId) {
        settingsCache.remove(providerId);
        persistSettings();
        notifyListeners(providerId, null);
        log.info("Deleted OAuth settings for provider: {}", providerId);
    }

    /**
     * Check if a provider is configured.
     */
    public boolean isConfigured(String providerId) {
        OAuthProviderSettings settings = getSettings(providerId);
        return settings.hasValidCredentials();
    }

    /**
     * Get client ID for a provider.
     */
    public String getClientId(String providerId) {
        OAuthProviderSettings settings = getSettings(providerId);
        return settings.getClientId();
    }

    /**
     * Get client secret for a provider.
     */
    public String getClientSecret(String providerId) {
        OAuthProviderSettings settings = getSettings(providerId);
        return settings.getClientSecret();
    }

    /**
     * Get scopes for a provider.
     */
    public String getScopes(String providerId) {
        OAuthProviderSettings settings = getSettings(providerId);
        return settings.getScopes();
    }

    /**
     * Get tenant ID for Microsoft.
     */
    public String getTenantId(String providerId) {
        OAuthProviderSettings settings = getSettings(providerId);
        return settings.getTenantId();
    }

    /**
     * Register a listener for settings changes.
     */
    public void addChangeListener(SettingsChangeListener listener) {
        changeListeners.add(listener);
    }

    /**
     * Remove a settings change listener.
     */
    public void removeChangeListener(SettingsChangeListener listener) {
        changeListeners.remove(listener);
    }

    /**
     * Get list of supported provider IDs.
     */
    public List<String> getProviderIds() {
        return List.of("google", "microsoft", "atlassian", "notion", "slack");
    }

    /**
     * Load settings from file or fall back to application properties.
     */
    private void loadSettings() {
        Path settingsPath = getSettingsPath();

        if (Files.exists(settingsPath)) {
            try {
                String json = Files.readString(settingsPath);
                Map<String, EncryptedSettings> encryptedMap = objectMapper.readValue(json,
                        new TypeReference<Map<String, EncryptedSettings>>() {});

                for (Map.Entry<String, EncryptedSettings> entry : encryptedMap.entrySet()) {
                    OAuthProviderSettings settings = decryptSettings(entry.getKey(), entry.getValue());
                    settingsCache.put(entry.getKey(), settings);
                }

                log.info("Loaded OAuth settings from: {}", settingsPath);
            } catch (Exception e) {
                log.error("Failed to load OAuth settings, using defaults", e);
                loadDefaultSettings();
            }
        } else {
            loadDefaultSettings();
        }
    }

    /**
     * Load default settings from application properties.
     */
    private void loadDefaultSettings() {
        // Google
        if (hasValue(googleClientId)) {
            settingsCache.put("google", OAuthProviderSettings.builder()
                    .providerId("google")
                    .clientId(googleClientId)
                    .clientSecret(googleClientSecret)
                    .scopes(googleScopes)
                    .configured(hasValue(googleClientId) && hasValue(googleClientSecret))
                    .build());
        }

        // Microsoft
        if (hasValue(microsoftClientId)) {
            settingsCache.put("microsoft", OAuthProviderSettings.builder()
                    .providerId("microsoft")
                    .clientId(microsoftClientId)
                    .clientSecret(microsoftClientSecret)
                    .tenantId(microsoftTenantId)
                    .scopes(microsoftScopes)
                    .configured(hasValue(microsoftClientId) && hasValue(microsoftClientSecret))
                    .build());
        }

        // Atlassian
        if (hasValue(atlassianClientId)) {
            settingsCache.put("atlassian", OAuthProviderSettings.builder()
                    .providerId("atlassian")
                    .clientId(atlassianClientId)
                    .clientSecret(atlassianClientSecret)
                    .scopes(atlassianScopes)
                    .configured(hasValue(atlassianClientId) && hasValue(atlassianClientSecret))
                    .build());
        }

        // Notion
        if (hasValue(notionClientId)) {
            settingsCache.put("notion", OAuthProviderSettings.builder()
                    .providerId("notion")
                    .clientId(notionClientId)
                    .clientSecret(notionClientSecret)
                    .configured(hasValue(notionClientId) && hasValue(notionClientSecret))
                    .build());
        }

        // Slack
        if (hasValue(slackClientId)) {
            settingsCache.put("slack", OAuthProviderSettings.builder()
                    .providerId("slack")
                    .clientId(slackClientId)
                    .clientSecret(slackClientSecret)
                    .scopes(slackScopes)
                    .configured(hasValue(slackClientId) && hasValue(slackClientSecret))
                    .build());
        }

        log.info("Loaded default OAuth settings from application properties");
    }

    /**
     * Create default settings for a provider.
     */
    private OAuthProviderSettings createDefaultSettings(String providerId) {
        String defaultScopes = switch (providerId) {
            case "google" -> "https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/gmail.readonly email profile";
            case "microsoft" -> "Files.Read User.Read offline_access";
            case "atlassian" -> "read:confluence-content.all read:jira-work read:jira-user offline_access";
            case "slack" -> "channels:history channels:read users:read";
            default -> "";
        };

        return OAuthProviderSettings.builder()
                .providerId(providerId)
                .scopes(defaultScopes)
                .tenantId(providerId.equals("microsoft") ? "common" : null)
                .configured(false)
                .build();
    }

    /**
     * Persist settings to file.
     */
    private void persistSettings() {
        try {
            Path settingsPath = getSettingsPath();
            Files.createDirectories(settingsPath.getParent());

            Map<String, EncryptedSettings> encryptedMap = new HashMap<>();
            for (Map.Entry<String, OAuthProviderSettings> entry : settingsCache.entrySet()) {
                encryptedMap.put(entry.getKey(), encryptSettings(entry.getValue()));
            }

            String json = objectMapper.writeValueAsString(encryptedMap);
            Files.writeString(settingsPath, json);

            // Set restrictive permissions
            try {
                settingsPath.toFile().setReadable(false, false);
                settingsPath.toFile().setReadable(true, true);
                settingsPath.toFile().setWritable(false, false);
                settingsPath.toFile().setWritable(true, true);
            } catch (Exception e) {
                log.warn("Could not set restrictive permissions on settings file: {}", e.getMessage());
            }

            log.debug("Persisted OAuth settings to: {}", settingsPath);
        } catch (IOException e) {
            log.error("Failed to persist OAuth settings", e);
            throw new RuntimeException("Failed to save OAuth settings", e);
        }
    }

    /**
     * Encrypt settings for storage.
     */
    private EncryptedSettings encryptSettings(OAuthProviderSettings settings) {
        return new EncryptedSettings(
                settings.getClientId(),
                settings.getClientSecret() != null ? encryptionService.encrypt(settings.getClientSecret()) : null,
                settings.getTenantId(),
                settings.getScopes(),
                settings.isConfigured(),
                settings.getLastUpdated()
        );
    }

    /**
     * Decrypt settings from storage.
     */
    private OAuthProviderSettings decryptSettings(String providerId, EncryptedSettings encrypted) {
        return OAuthProviderSettings.builder()
                .providerId(providerId)
                .clientId(encrypted.clientId)
                .clientSecret(encrypted.encryptedSecret != null ? encryptionService.decrypt(encrypted.encryptedSecret) : null)
                .tenantId(encrypted.tenantId)
                .scopes(encrypted.scopes)
                .configured(encrypted.configured)
                .lastUpdated(encrypted.lastUpdated)
                .build();
    }

    /**
     * Get the path to the settings file.
     */
    private Path getSettingsPath() {
        return Paths.get(kompileDataDir, "config", SETTINGS_FILE);
    }

    /**
     * Check if a value is not null or empty.
     */
    private boolean hasValue(String value) {
        return value != null && !value.isEmpty();
    }

    /**
     * Notify listeners of a settings change.
     */
    private void notifyListeners(String providerId, OAuthProviderSettings settings) {
        for (SettingsChangeListener listener : changeListeners) {
            try {
                listener.onSettingsChanged(providerId, settings);
            } catch (Exception e) {
                log.error("Error notifying settings change listener", e);
            }
        }
    }

    /**
     * Listener interface for settings changes.
     */
    public interface SettingsChangeListener {
        void onSettingsChanged(String providerId, OAuthProviderSettings settings);
    }

    /**
     * Internal class for encrypted settings storage.
     */
    private record EncryptedSettings(
            String clientId,
            String encryptedSecret,
            String tenantId,
            String scopes,
            boolean configured,
            Long lastUpdated
    ) {}
}
