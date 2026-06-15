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

package ai.kompile.staging.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Service for managing UI-configurable staging settings.
 * Settings are persisted to a JSON file in the kompile home directory.
 */
@Service
public class StagingSettingsService {

    private static final Logger log = LoggerFactory.getLogger(StagingSettingsService.class);
    private static final String SETTINGS_FILENAME = "staging-settings.json";

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private Path settingsFile;
    private volatile StagingSettings settings;

    @Value("${kompile.staging.settings-dir:${kompile.home:${user.home}/.kompile}}")
    private String settingsDir;

    @Value("${kompile.staging.callback-url:}")
    private String callbackUrlOverride;

    public StagingSettingsService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.restTemplate = new RestTemplate();
    }

    @PostConstruct
    public void init() {
        this.settingsFile = Paths.get(settingsDir).resolve(SETTINGS_FILENAME);
        log.info("Staging settings file: {}", settingsFile);
        loadSettings();
        // Apply callback URL override from Spring property if settings file didn't have one
        if (callbackUrlOverride != null && !callbackUrlOverride.isBlank()) {
            StagingSettings s = getSettings();
            if (s.getCallbackUrl() == null || s.getCallbackUrl().isBlank()) {
                s.setCallbackUrl(callbackUrlOverride);
                s.setAutoReloadEnabled(true);
                updateSettings(s);
                log.info("Set callback URL from property: {}", callbackUrlOverride);
            }
        }
    }

    /**
     * Get the current settings.
     */
    public StagingSettings getSettings() {
        lock.readLock().lock();
        try {
            return settings != null ? settings : StagingSettings.defaults();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Update and persist settings.
     */
    public StagingSettings updateSettings(StagingSettings newSettings) {
        lock.writeLock().lock();
        try {
            this.settings = newSettings;
            saveSettings();
            return this.settings;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get the callback URL, or null if not configured.
     */
    public String getCallbackUrl() {
        StagingSettings s = getSettings();
        return s.getCallbackUrl();
    }

    /**
     * Check if auto-reload is enabled and callback URL is configured.
     */
    public boolean isAutoReloadEnabled() {
        StagingSettings s = getSettings();
        return s.isAutoReloadEnabled() &&
               s.getCallbackUrl() != null &&
               !s.getCallbackUrl().isBlank();
    }

    /**
     * Notify kompile-app-main to reload the model.
     *
     * @param modelId The ID of the model that was changed
     * @return true if notification was successful, false otherwise
     */
    public boolean notifyModelReload(String modelId) {
        if (!isAutoReloadEnabled()) {
            log.debug("Auto-reload not enabled or no callback URL configured");
            return false;
        }

        String callbackUrl = getCallbackUrl();
        try {
            String reloadUrl = callbackUrl.endsWith("/")
                    ? callbackUrl + "api/models/embedding/reload"
                    : callbackUrl + "/api/models/embedding/reload";

            log.info("Notifying kompile-app-main to reload model at: {}", reloadUrl);

            ResponseEntity<Map> response = restTemplate.postForEntity(reloadUrl, null, Map.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                Map<?, ?> body = response.getBody();
                boolean success = body != null && Boolean.TRUE.equals(body.get("success"));
                if (success) {
                    log.info("Model reload notification successful - model {} reloaded", modelId);
                    return true;
                } else {
                    log.warn("Model reload notification returned non-success: {}", body);
                    return false;
                }
            } else {
                log.warn("Model reload notification failed with status: {}", response.getStatusCode());
                return false;
            }
        } catch (Exception e) {
            log.warn("Failed to notify kompile-app-main to reload model: {}. " +
                    "You may need to manually refresh.", e.getMessage());
            return false;
        }
    }

    /**
     * Notify kompile-app-main to reload the VLM model.
     *
     * @param modelId The ID of the VLM model that was loaded
     * @return true if notification was successful, false otherwise
     */
    public boolean notifyVlmReload(String modelId) {
        if (!isAutoReloadEnabled()) {
            log.debug("Auto-reload not enabled or no callback URL configured, skipping VLM reload notification");
            return false;
        }

        String callbackUrl = getCallbackUrl();
        try {
            String reloadUrl = callbackUrl.endsWith("/")
                    ? callbackUrl + "api/models/vlm/reload"
                    : callbackUrl + "/api/models/vlm/reload";

            log.info("Notifying kompile-app-main to reload VLM model at: {}", reloadUrl);

            ResponseEntity<Map> response = restTemplate.postForEntity(reloadUrl, null, Map.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                Map<?, ?> body = response.getBody();
                boolean success = body != null && Boolean.TRUE.equals(body.get("success"));
                if (success) {
                    log.info("VLM model reload notification successful - model {}", modelId);
                    return true;
                } else {
                    log.warn("VLM model reload notification returned non-success: {}", body);
                    return false;
                }
            } else {
                log.warn("VLM model reload notification failed with status: {}", response.getStatusCode());
                return false;
            }
        } catch (Exception e) {
            log.warn("Failed to notify kompile-app-main to reload VLM model: {}. " +
                    "You may need to manually refresh.", e.getMessage());
            return false;
        }
    }

    /**
     * Test the callback URL connection.
     */
    public CallbackTestResult testCallback() {
        String callbackUrl = getCallbackUrl();
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return new CallbackTestResult(false, "No callback URL configured");
        }

        try {
            String statusUrl = callbackUrl.endsWith("/")
                    ? callbackUrl + "api/models/embedding/status"
                    : callbackUrl + "/api/models/embedding/status";

            ResponseEntity<Map> response = restTemplate.getForEntity(statusUrl, Map.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                return new CallbackTestResult(true, "Connection successful");
            } else {
                return new CallbackTestResult(false, "HTTP " + response.getStatusCode());
            }
        } catch (Exception e) {
            return new CallbackTestResult(false, e.getMessage());
        }
    }

    private void loadSettings() {
        lock.writeLock().lock();
        try {
            if (Files.exists(settingsFile)) {
                try {
                    this.settings = objectMapper.readValue(settingsFile.toFile(), StagingSettings.class);
                    log.info("Loaded staging settings from: {}", settingsFile);
                } catch (IOException e) {
                    log.warn("Failed to load settings from {}", settingsFile, e);
                    this.settings = StagingSettings.defaults();
                }
            } else {
                this.settings = StagingSettings.defaults();
                log.info("Using default staging settings (no settings file found)");
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void saveSettings() {
        try {
            Files.createDirectories(settingsFile.getParent());
            objectMapper.writeValue(settingsFile.toFile(), settings);
            log.info("Saved staging settings to: {}", settingsFile);
        } catch (IOException e) {
            log.error("Failed to save settings to {}", settingsFile, e);
        }
    }

    /**
     * Result of testing the callback connection.
     */
    public static class CallbackTestResult {
        private final boolean success;
        private final String message;

        public CallbackTestResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean success() {
            return success;
        }

        public String message() {
            return message;
        }
    }
}
