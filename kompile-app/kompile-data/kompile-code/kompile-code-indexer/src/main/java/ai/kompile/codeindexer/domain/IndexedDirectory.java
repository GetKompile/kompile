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
 * Tracks directories that have been added for indexing. Each directory
 * belongs to a project and records its indexing status and stats.
 */
@Entity
@Table(name = "indexed_directories",
       uniqueConstraints = @UniqueConstraint(columnNames = {"projectId", "absolutePath"}))
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class IndexedDirectory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String projectId;

    @Column(nullable = false, length = 4000)
    private String absolutePath;

    @Column(length = 500)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private IndexStatus status = IndexStatus.PENDING;

    private int filesIndexed;
    private int entitiesFound;
    private int relationsCreated;
    private int errors;

    private Instant addedAt;
    private Instant lastIndexedAt;

    /** Glob patterns to include (comma-separated, null = all) */
    @Column(length = 2000)
    private String includePatterns;

    /** Glob patterns to exclude (comma-separated) */
    @Column(length = 2000)
    private String excludePatterns;

    /** Language overrides JSON: {"*.jsx": "typescript", "legacy/*.js": "coffeescript"} */
    @Column(columnDefinition = "TEXT")
    private String languageOverridesJson;

    /** Human-readable description of what this directory contains */
    @Column(length = 2000)
    private String description;

    /** Comma-separated tags for categorization (e.g. "backend,java,microservice") */
    @Column(length = 1000)
    private String tags;

    @PrePersist
    void prePersist() {
        if (addedAt == null) addedAt = Instant.now();
    }

    public enum IndexStatus {
        PENDING, INDEXING, INDEXED, FAILED
    }
}
