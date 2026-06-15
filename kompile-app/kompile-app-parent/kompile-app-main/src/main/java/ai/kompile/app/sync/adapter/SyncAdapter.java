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

package ai.kompile.app.sync.adapter;

import ai.kompile.app.facts.domain.Note;
import ai.kompile.app.sync.domain.NoteSyncConnection;
import ai.kompile.app.sync.dto.SyncConnectionTestResponse;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Provider-specific adapter for bilateral note sync.
 * Implementations: NotionSyncAdapter, ObsidianSyncAdapter.
 */
public interface SyncAdapter {

    /** Human-readable adapter name for logging (e.g. "notion", "obsidian"). */
    String adapterId();

    /**
     * Fetch all externally-changed items since a given timestamp.
     */
    List<ExternalNoteSnapshot> fetchChangedSince(NoteSyncConnection conn, Instant since);

    /**
     * Fetch a single external item by its externalId. Returns empty if deleted.
     */
    Optional<ExternalNoteSnapshot> fetchById(NoteSyncConnection conn, String externalId);

    /**
     * Create a new external item corresponding to a Kompile Note.
     * @return the newly assigned externalId
     */
    String createExternal(NoteSyncConnection conn, Note note, String markdownContent);

    /**
     * Update an existing external item.
     */
    void updateExternal(NoteSyncConnection conn, String externalId, Note note, String markdownContent);

    /**
     * Delete an external item.
     */
    void deleteExternal(NoteSyncConnection conn, String externalId);

    /**
     * Validate filesystem/network access and any configured credentials.
     */
    default SyncConnectionTestResponse testConnection(NoteSyncConnection conn) {
        return SyncConnectionTestResponse.success(conn.getId(), conn.getAuthMode(),
                "No provider-specific auth test is required.");
    }

    /**
     * Register a webhook for real-time change detection on this connection.
     * @return webhookId if supported, empty if not
     */
    default Optional<String> registerWebhook(NoteSyncConnection conn, String callbackUrl) {
        return Optional.empty();
    }

    /**
     * Snapshot of a note fetched from an external provider.
     * Content is pre-converted to Markdown by the adapter.
     */
    record ExternalNoteSnapshot(
        String externalId,
        String title,
        String markdownContent,
        String tags,
        Instant externalUpdatedAt
    ) {}
}
