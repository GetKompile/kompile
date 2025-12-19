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

package ai.kompile.app.facts.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Entity representing a single Fact - a document or file that is part of a fact sheet.
 * Facts are the individual pieces of information that can be organized into fact sheets.
 */
@Entity
@Table(name = "facts", indexes = {
    @Index(name = "idx_fact_file_name", columnList = "fileName"),
    @Index(name = "idx_fact_checksum", columnList = "checksum"),
    @Index(name = "idx_fact_sheet_id", columnList = "factSheet_id"),
    @Index(name = "idx_fact_source_type", columnList = "sourceType"),
    @Index(name = "idx_fact_created", columnList = "createdAt"),
    @Index(name = "idx_fact_indexed", columnList = "indexed"),
    @Index(name = "idx_fact_sheet_indexed", columnList = "factSheet_id, indexed")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Fact {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * The fact sheet this fact belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factSheet_id", nullable = false)
    private FactSheet factSheet;

    /**
     * Original file name.
     */
    @Column(nullable = false, length = 512)
    private String fileName;

    /**
     * Full path to the file.
     */
    @Column(nullable = false, length = 2048)
    private String filePath;

    /**
     * SHA-256 checksum of the file content.
     */
    @Column(length = 64)
    private String checksum;

    /**
     * Type of source (UPLOAD, STORED, URL, etc.).
     */
    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private SourceType sourceType = SourceType.UPLOAD;

    /**
     * File extension (lowercase, without dot).
     */
    @Column(length = 20)
    private String extension;

    /**
     * MIME type.
     */
    @Column(length = 100)
    private String mimeType;

    /**
     * File size in bytes.
     */
    @Column
    private Long sizeBytes;

    /**
     * How this file can be viewed.
     */
    @Column(length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ViewMode viewMode = ViewMode.DOWNLOAD_ONLY;

    /**
     * Whether this fact can be previewed inline.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean canPreview = false;

    /**
     * User-provided title/label for this fact.
     */
    @Column(length = 512)
    private String title;

    /**
     * User-provided notes about this fact.
     */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /**
     * Tags for categorization (comma-separated).
     */
    @Column(length = 1024)
    private String tags;

    /**
     * Original URL if this was downloaded from the web.
     */
    @Column(length = 2048)
    private String sourceUrl;

    /**
     * Whether this fact has been indexed in the vector store.
     * When false, the fact is stored but not searchable via vector/keyword search.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean indexed = false;

    /**
     * When this fact was indexed (null if not indexed).
     */
    @Column
    private Instant indexedAt;

    /**
     * When this fact was added to the sheet.
     */
    @Column(nullable = false)
    private Instant createdAt;

    /**
     * When this fact was last modified.
     */
    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Last time the file was accessed/viewed.
     */
    @Column
    private Instant lastAccessedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Mark the fact as accessed.
     */
    public void markAccessed() {
        this.lastAccessedAt = Instant.now();
    }

    /**
     * Mark the fact as indexed.
     */
    public void markIndexed() {
        this.indexed = true;
        this.indexedAt = Instant.now();
    }

    /**
     * Check if the fact needs indexing.
     */
    public boolean needsIndexing() {
        return !Boolean.TRUE.equals(this.indexed);
    }

    /**
     * Source types for facts.
     */
    public enum SourceType {
        /** Uploaded by user */
        UPLOAD,
        /** Stored in document storage */
        STORED,
        /** Downloaded from URL */
        URL,
        /** Created from text input */
        TEXT,
        /** Imported from another system */
        IMPORT
    }

    /**
     * View modes for facts.
     */
    public enum ViewMode {
        /** Can be viewed as text */
        TEXT,
        /** Can be viewed as image */
        IMAGE,
        /** Can be embedded (PDF) */
        EMBEDDED,
        /** Can only be downloaded */
        DOWNLOAD_ONLY
    }
}
