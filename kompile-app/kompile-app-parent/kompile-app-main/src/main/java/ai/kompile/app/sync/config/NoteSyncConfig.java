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

package ai.kompile.app.sync.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JSON-persisted configuration for bilateral note sync.
 * Stored at ~/.kompile/config/note-sync-config.json
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NoteSyncConfig {

    /** Master enable/disable for the Notion sync adapter. */
    @Builder.Default
    private Boolean notionEnabled = false;

    /** HMAC-SHA256 secret for verifying Notion webhook payloads. */
    @Builder.Default
    private String notionWebhookSecret = "";

    /** Public base URL of this Kompile instance (for webhook registration). */
    @Builder.Default
    private String notionCallbackBaseUrl = "http://localhost:8080";

    /** Master enable/disable for the Obsidian sync adapter. */
    @Builder.Default
    private Boolean obsidianEnabled = false;

    /** Enable native filesystem watching for locally-mounted Obsidian vaults. */
    @Builder.Default
    private Boolean obsidianFileWatchEnabled = false;

    /** Enable the background polling scheduler for sync connections. */
    @Builder.Default
    private Boolean schedulerEnabled = false;

    /** How often (ms) the scheduler checks if any connection's pollCron should fire. */
    @Builder.Default
    private Long schedulerCheckIntervalMs = 60_000L;

    public static NoteSyncConfig defaults() {
        return NoteSyncConfig.builder().build();
    }

    /**
     * Merge non-null fields from another config into this one.
     */
    public NoteSyncConfig merge(NoteSyncConfig other) {
        if (other == null) return this;
        if (other.notionEnabled != null) this.notionEnabled = other.notionEnabled;
        if (other.notionWebhookSecret != null) this.notionWebhookSecret = other.notionWebhookSecret;
        if (other.notionCallbackBaseUrl != null) this.notionCallbackBaseUrl = other.notionCallbackBaseUrl;
        if (other.obsidianEnabled != null) this.obsidianEnabled = other.obsidianEnabled;
        if (other.obsidianFileWatchEnabled != null) this.obsidianFileWatchEnabled = other.obsidianFileWatchEnabled;
        if (other.schedulerEnabled != null) this.schedulerEnabled = other.schedulerEnabled;
        if (other.schedulerCheckIntervalMs != null) this.schedulerCheckIntervalMs = other.schedulerCheckIntervalMs;
        return this;
    }
}
