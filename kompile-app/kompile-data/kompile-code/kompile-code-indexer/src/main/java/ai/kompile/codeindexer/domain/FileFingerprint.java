/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.codeindexer.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks the fingerprint (content hash + mtime + size) of each indexed file.
 * Used for incremental indexing: only files whose fingerprint has changed
 * since last index need to be re-processed.
 */
@Entity
@Table(name = "code_file_fingerprints",
       uniqueConstraints = @UniqueConstraint(columnNames = {"projectId", "filePath"}),
       indexes = {
           @Index(name = "idx_fingerprint_project", columnList = "projectId"),
           @Index(name = "idx_fingerprint_project_file", columnList = "projectId,filePath")
       })
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class FileFingerprint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String projectId;

    @Column(nullable = false, length = 4000)
    private String filePath;

    /** SHA-256 hash of the file content */
    @Column(nullable = false, length = 64)
    private String contentHash;

    /** File size in bytes */
    private long fileSize;

    /** Last modification time of the file */
    private Instant lastModified;

    /** When this fingerprint was last verified/updated */
    @Column(nullable = false)
    private Instant indexedAt;

    @PrePersist
    void prePersist() {
        if (indexedAt == null) indexedAt = Instant.now();
    }
}
