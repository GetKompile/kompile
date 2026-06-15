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
 * Tracks per-note sync state for a given connection. One row per (noteId, connectionId) pair.
 * Used for conflict detection via timestamp comparison and content checksums.
 */
@Entity
@Table(name = "note_sync_records", indexes = {
    @Index(name = "idx_nsr_note", columnList = "noteId"),
    @Index(name = "idx_nsr_connection", columnList = "connectionId"),
    @Index(name = "idx_nsr_external_id", columnList = "externalId"),
    @Index(name = "idx_nsr_pending", columnList = "pendingPush")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteSyncRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(nullable = false)
    private Long noteId;

    @Column(nullable = false)
    private Long connectionId;

    /** The external ID at the time of last successful sync. */
    @Column(length = 512)
    private String externalId;

    /** Kompile-side updatedAt at last sync -- used for LWW comparison. */
    @Column
    private Instant kompileUpdatedAt;

    /** External-side timestamp at last sync. */
    @Column
    private Instant externalUpdatedAt;

    /** SHA-256 hex of content at last sync -- fast change detection. */
    @Column(length = 64)
    private String contentChecksum;

    /** If true, an outbound push is pending (dirty flag). */
    @Column(nullable = false)
    @Builder.Default
    private Boolean pendingPush = false;

    /** SYNCED, CONFLICT, ERROR, PENDING */
    @Column(length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column
    private Instant lastSyncAt;
}
