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

package ai.kompile.app.sync.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Represents a configured sync connection between a Kompile FactSheet and an
 * external provider (Notion page/database or Obsidian vault directory).
 * One row per (factSheetId, provider, externalScope) triple.
 */
@Entity
@Table(name = "note_sync_connections", indexes = {
    @Index(name = "idx_nsc_fact_sheet", columnList = "factSheetId"),
    @Index(name = "idx_nsc_provider", columnList = "provider")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteSyncConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(nullable = false)
    private Long factSheetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncProvider provider;

    /**
     * Notion: parent page or database UUID to sync into.
     * Obsidian: vault-relative directory, e.g. "Notes/Kompile".
     */
    @Column(nullable = false, length = 1024)
    private String externalScope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    @Builder.Default
    private SyncDirection direction = SyncDirection.BIDIRECTIONAL;

    /** Cron expression for polling fallback, e.g. "0 *\/15 * * * ?". */
    @Column(length = 64)
    private String pollCron;

    /** Notion-specific: registered webhook ID (null until registered). */
    @Column(length = 128)
    private String webhookId;

    /** Obsidian-specific: REST API base URL, e.g. "https://localhost:27124". */
    @Column(length = 512)
    private String obsidianApiUrl;

    /** Obsidian-specific: Bearer token, stored encrypted via TokenEncryptionService. */
    @Column(columnDefinition = "TEXT")
    private String obsidianTokenEncrypted;

    /** Git-specific: remote repository URL. externalScope remains the local working tree path. */
    @Column(length = 1024)
    private String repositoryUrl;

    /** Git-specific: branch to sync. Defaults to main when blank. */
    @Column(length = 128)
    private String gitBranch;

    /** Git-specific: HTTPS username for token-based auth. Token is stored separately. */
    @Column(length = 256)
    private String gitUsername;

    /** Git-specific: encrypted HTTPS token/password for remote operations. */
    @Column(columnDefinition = "TEXT")
    private String gitTokenEncrypted;

    /** Explicit auth mode so users know how the connection authenticates. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    @Builder.Default
    private SyncAuthMode authMode = SyncAuthMode.NONE;

    /** UNKNOWN, NOT_REQUIRED, CONFIGURED, VALID, INVALID, MISSING. */
    @Column(length = 32)
    @Builder.Default
    private String authStatus = "UNKNOWN";

    @Column(columnDefinition = "TEXT")
    private String authStatusMessage;

    @Column
    private Instant authLastCheckedAt;

    /** Whether filesystem changes should be committed automatically for git-backed stores. */
    @Column(nullable = false)
    @Builder.Default
    private Boolean autoCommit = true;

    /** Whether git-backed stores should pull and push the remote repository. */
    @Column(nullable = false)
    @Builder.Default
    private Boolean remoteSyncEnabled = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column
    private Instant lastSyncAt;

    /** NEVER, OK, ERROR, CONFLICT */
    @Column(length = 50)
    @Builder.Default
    private String lastSyncStatus = "NEVER";

    @Column(columnDefinition = "TEXT")
    private String lastSyncError;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
