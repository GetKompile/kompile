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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing OAuth provider settings using the kompile managed-JSON config pattern.
 * <p>
 * Non-secret configuration (clientId, scopes, tenantId, configured flag) is persisted to
 * {@code <dataDir>/config/oauth-config.json}. Secrets (clientSecrets) are <em>never</em>
 * written to that file; they are resolved at load time using the following precedence:
 * <ol>
 *   <li>OS environment variable — e.g. {@code GOOGLE_CLIENT_SECRET} (derived from the
 *       provider name; same variables the application.properties layer bridged previously).</li>
 *   <li>Optional gitignored sidecar file {@code <dataDir>/config/secrets/oauth-secrets.json},
 *       expected format: {@code { "google.client-secret": "...", ... }}.</li>
 *   <li>Empty string (provider remains unconfigured).</li>
 * </ol>
 * Calling {@link #persist()} writes only the non-secret fields back to
 * {@code oauth-config.json}.
 */
@Service
public class OAuthSettingsService {

    private static final Logger log = LoggerFactory.getLogger(OAuthSettingsService.class);
    private static final String CONFIG_FILENAME = "oauth-config.json";

    /** Kept in the constructor for backward-compatibility with existing Spring wiring. */
    private final TokenEncryptionService encryptionService;
    private final ObjectMapper objectMapper;
    private final Path configFilePath;
    private final Path secretsFilePath;

    // In-memory cache; secrets are held here but never serialised to oauth-config.json
    private final Map<String, OAuthProviderSettings> settingsCache = new ConcurrentHashMap<>();

    // Listeners for settings changes
    private final List<SettingsChangeListener> changeListeners = new ArrayList<>();

    public OAuthSettingsService(
            TokenEncryptionService encryptionService,
            ObjectMapper objectMapper,
            @Value("${kompile.data.dir:#{null}}") String dataDir) {
        this.encryptionService = encryptionService;
        this.objectMapper = objectMapper.copy();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        String effectiveDataDir = (dataDir != null && !dataDir.isBlank())
                ? dataDir : System.getProperty("user.home") + "/.kompile";
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
        this.secretsFilePath = Paths.get(effectiveDataDir, "config", "secrets", "oauth-secrets.json");
        log.info("OAuthSettingsService initialized, config path: {}", configFilePath);
    }

    @PostConstruct
    public void init() {
        loadSettings();
    }

    // ── public API (unchanged signatures) ────────────────────────────────────────

    /**
     * Get settings for a specific provider.
     */
    public OAuthProviderSettings getSettings(String providerId) {
        OAuthProviderSettings settings = settingsCache.get(providerId);
        if (settings == null) {
            settings = createDefaultSettings(providerId);
        }
        return settings;
    }

    /**
     * Get all provider settings (sanitized — secrets masked).
     */
    public List<OAuthProviderSettings> getAllSettings() {
        List<OAuthProviderSettings> allSettings = new ArrayList<>();
        for (String providerId : getProviderIds()) {
            allSettings.add(getSettings(providerId).sanitized());
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

        settings.setConfigured(settings.hasValidCredentials());
        settings.setLastUpdated(System.currentTimeMillis());

        settingsCache.put(settings.getProviderId(), settings);
        persist();
        notifyListeners(settings.getProviderId(), settings);

        log.info("Saved OAuth settings for provider: {}", settings.getProviderId());
        return settings.sanitized();
    }

    /**
     * Delete settings for a provider (reset to defaults).
     */
    public void deleteSettings(String providerId) {
        settingsCache.remove(providerId);
        persist();
        notifyListeners(providerId, null);
        log.info("Deleted OAuth settings for provider: {}", providerId);
    }

    /**
     * Check if a provider is configured.
     */
    public boolean isConfigured(String providerId) {
        return getSettings(providerId).hasValidCredentials();
    }

    /**
     * Get client ID for a provider.
     */
    public String getClientId(String providerId) {
        return getSettings(providerId).getClientId();
    }

    /**
     * Get client secret for a provider.
     */
    public String getClientSecret(String providerId) {
        return getSettings(providerId).getClientSecret();
    }

    /**
     * Get scopes for a provider.
     */
    public String getScopes(String providerId) {
        return getSettings(providerId).getScopes();
    }

    /**
     * Get tenant ID for Microsoft.
     */
    public String getTenantId(String providerId) {
        return getSettings(providerId).getTenantId();
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
     * Persists <em>non-secret</em> configuration fields to
     * {@code <dataDir>/config/oauth-config.json} as pretty-printed JSON.
     * The {@code clientSecret} field is explicitly excluded from serialisation.
     * Parent directories are created if absent.
     */
    public void persist() {
        try {
            Path parent = configFilePath.getParent();
            if (!Files.exists(parent)) {
                Files.createDirectories(parent);
                log.info("Created config directory: {}", parent);
            }

            // Build a sanitised copy — no secrets
            Map<String, OAuthProviderSettings> toWrite = new LinkedHashMap<>();
            for (Map.Entry<String, OAuthProviderSettings> entry : settingsCache.entrySet()) {
                OAuthProviderSettings v = entry.getValue();
                toWrite.put(entry.getKey(), OAuthProviderSettings.builder()
                        .providerId(v.getProviderId())
                        .clientId(v.getClientId())
                        // clientSecret intentionally omitted — never written to disk
                        .tenantId(v.getTenantId())
                        .scopes(v.getScopes())
                        .configured(v.isConfigured())
                        .lastUpdated(v.getLastUpdated())
                        .build());
            }

            String json = objectMapper.writeValueAsString(toWrite);
            Files.writeString(configFilePath, json);

            // Owner read/write only
            try {
                configFilePath.toFile().setReadable(false, false);
                configFilePath.toFile().setReadable(true, true);
                configFilePath.toFile().setWritable(false, false);
                configFilePath.toFile().setWritable(true, true);
            } catch (Exception e) {
                log.warn("Could not set restrictive permissions on config file: {}", e.getMessage());
            }

            log.info("Persisted OAuth config (non-secret fields only) to {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to persist OAuth config to {}: {}", configFilePath, e.getMessage(), e);
            throw new RuntimeException("Failed to save OAuth config", e);
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────────

    /**
     * Populate the in-memory cache from the JSON config file (non-secrets) and overlay
     * secrets resolved from the OS environment or the optional sidecar secrets file.
     */
    private void loadSettings() {
        Map<String, OAuthProviderSettings> fromFile = loadNonSecretConfig();
        Map<String, String> secretsFromFile = loadSecretsFile();

        for (String providerId : getProviderIds()) {
            OAuthProviderSettings base = fromFile.getOrDefault(providerId, createDefaultSettings(providerId));

            // Ensure providerId is always stamped (JSON deserialisation may omit it)
            if (base.getProviderId() == null) {
                base.setProviderId(providerId);
            }

            // Bootstrap client-id from env var if absent in the config file
            // (preserves backward-compat for users who set e.g. GOOGLE_CLIENT_ID)
            if (!hasValue(base.getClientId())) {
                String envClientId = System.getenv(providerId.toUpperCase(Locale.ROOT) + "_CLIENT_ID");
                if (hasValue(envClientId)) {
                    base.setClientId(envClientId);
                }
            }

            // Bootstrap Microsoft tenant-id from env var if absent in the config file
            // (preserves backward-compat for users who set MICROSOFT_TENANT_ID)
            if ("microsoft".equals(providerId) && !hasValue(base.getTenantId())) {
                String envTenantId = System.getenv("MICROSOFT_TENANT_ID");
                if (hasValue(envTenantId)) {
                    base.setTenantId(envTenantId);
                }
            }

            // Overlay secret — never from the JSON file
            String secret = resolveSecret(providerId, secretsFromFile);
            if (hasValue(secret)) {
                base.setClientSecret(secret);
            }

            // Recompute configured flag based on actual in-memory credentials
            base.setConfigured(base.hasValidCredentials());
            settingsCache.put(providerId, base);
        }

        log.info("Loaded OAuth settings from: {}", configFilePath);
    }

    /**
     * Read the non-secret config map from {@code oauth-config.json}.
     * Returns an empty map if the file does not exist or cannot be parsed.
     */
    private Map<String, OAuthProviderSettings> loadNonSecretConfig() {
        if (!Files.exists(configFilePath)) {
            log.info("No OAuth config found at {} — using defaults", configFilePath);
            return new HashMap<>();
        }
        try {
            String json = Files.readString(configFilePath);
            return objectMapper.readValue(json, new TypeReference<Map<String, OAuthProviderSettings>>() {});
        } catch (IOException e) {
            log.warn("Could not read OAuth config from {} — using defaults: {}", configFilePath, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Load the optional secrets sidecar file.
     * Expected format: {@code { "google.client-secret": "...", "microsoft.client-secret": "..." }}.
     * Returns an empty map if the file is absent or cannot be read.
     */
    private Map<String, String> loadSecretsFile() {
        if (!Files.exists(secretsFilePath)) {
            return new HashMap<>();
        }
        try {
            String json = Files.readString(secretsFilePath);
            Map<String, String> secrets = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
            log.info("Loaded OAuth secrets sidecar from {}", secretsFilePath);
            return secrets;
        } catch (IOException e) {
            log.warn("Could not read OAuth secrets sidecar from {}: {}", secretsFilePath, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Resolve the client secret for {@code providerId} using the precedence:
     * <ol>
     *   <li>OS env var {@code <PROVIDER>_CLIENT_SECRET}</li>
     *   <li>Secrets sidecar key {@code <provider>.client-secret}</li>
     *   <li>Empty string</li>
     * </ol>
     */
    private String resolveSecret(String providerId, Map<String, String> secretsFromFile) {
        // (1) OS environment variable
        String envVar = providerId.toUpperCase(Locale.ROOT) + "_CLIENT_SECRET";
        String fromEnv = System.getenv(envVar);
        if (hasValue(fromEnv)) {
            log.debug("Resolved secret for provider '{}' from env var {}", providerId, envVar);
            return fromEnv;
        }
        // (2) Sidecar secrets file
        String fromFile = secretsFromFile.get(providerId + ".client-secret");
        if (hasValue(fromFile)) {
            log.debug("Resolved secret for provider '{}' from secrets sidecar", providerId);
            return fromFile;
        }
        // (3) Unconfigured
        return "";
    }

    /**
     * Create a default (empty) settings object for a provider with well-known scope defaults.
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
                .tenantId("microsoft".equals(providerId) ? "common" : null)
                .configured(false)
                .build();
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
     * Check if a value is non-null and non-empty.
     */
    private boolean hasValue(String value) {
        return value != null && !value.isEmpty();
    }

    // ── interfaces ────────────────────────────────────────────────────────────────

    /**
     * Listener interface for settings changes.
     */
    public interface SettingsChangeListener {
        void onSettingsChanged(String providerId, OAuthProviderSettings settings);
    }
}
