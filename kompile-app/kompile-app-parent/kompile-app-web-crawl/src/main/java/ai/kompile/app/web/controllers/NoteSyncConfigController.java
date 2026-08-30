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

package ai.kompile.app.web.controllers;

import ai.kompile.app.sync.config.NoteSyncConfig;
import ai.kompile.app.sync.config.NoteSyncConfigService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for managing bilateral sync configuration.
 * Configuration is persisted to ~/.kompile/config/note-sync-config.json
 */
@RestController
@RequestMapping("/api/sync/config")
public class NoteSyncConfigController {

    private final NoteSyncConfigService configService;

    public NoteSyncConfigController(NoteSyncConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public ResponseEntity<NoteSyncConfigResponse> getConfig() {
        return ResponseEntity.ok(NoteSyncConfigResponse.from(configService.getConfiguration()));
    }

    @PutMapping
    public ResponseEntity<NoteSyncConfigResponse> updateConfig(
            @RequestBody NoteSyncConfigUpdateRequest update) {
        return ResponseEntity.ok(NoteSyncConfigResponse.from(
                configService.updateConfiguration(update.toConfig())));
    }

    @PostMapping("/reset")
    public ResponseEntity<NoteSyncConfigResponse> resetConfig() {
        return ResponseEntity.ok(NoteSyncConfigResponse.from(configService.resetConfiguration()));
    }

    /**
     * Patch-like write model. In particular, an omitted webhook secret stays {@code null} so the
     * configuration service preserves the existing write-only value.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NoteSyncConfigUpdateRequest(
            Boolean notionEnabled,
            String notionWebhookSecret,
            String notionCallbackBaseUrl,
            Boolean obsidianEnabled,
            Boolean obsidianFileWatchEnabled,
            Boolean schedulerEnabled,
            Long schedulerCheckIntervalMs) {

        NoteSyncConfig toConfig() {
            return new NoteSyncConfig(
                    notionEnabled,
                    notionWebhookSecret,
                    notionCallbackBaseUrl,
                    obsidianEnabled,
                    obsidianFileWatchEnabled,
                    schedulerEnabled,
                    schedulerCheckIntervalMs);
        }
    }

    /**
     * Public, read-safe representation. The webhook secret remains write-only.
     */
    public record NoteSyncConfigResponse(
            Boolean notionEnabled,
            boolean notionWebhookSecretConfigured,
            String notionCallbackBaseUrl,
            Boolean obsidianEnabled,
            Boolean obsidianFileWatchEnabled,
            Boolean schedulerEnabled,
            Long schedulerCheckIntervalMs) {

        static NoteSyncConfigResponse from(NoteSyncConfig config) {
            String secret = config.getNotionWebhookSecret();
            return new NoteSyncConfigResponse(
                    config.getNotionEnabled(),
                    secret != null && !secret.isBlank(),
                    config.getNotionCallbackBaseUrl(),
                    config.getObsidianEnabled(),
                    config.getObsidianFileWatchEnabled(),
                    config.getSchedulerEnabled(),
                    config.getSchedulerCheckIntervalMs());
        }
    }
}
