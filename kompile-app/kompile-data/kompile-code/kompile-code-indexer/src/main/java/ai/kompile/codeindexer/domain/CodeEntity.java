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
 * A code entity extracted from a source file — e.g. a class, method, function,
 * interface, field, import, package, or module.
 */
@Entity
@Table(name = "code_entities",
       indexes = {
           @Index(name = "idx_code_entity_type", columnList = "entityType"),
           @Index(name = "idx_code_entity_fqn", columnList = "fullyQualifiedName"),
           @Index(name = "idx_code_entity_file", columnList = "filePath"),
           @Index(name = "idx_code_entity_project", columnList = "projectId")
       })
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class CodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CodeEntityType entityType;

    @Column(nullable = false)
    private String name;

    @Column(length = 2000)
    private String fullyQualifiedName;

    @Column(nullable = false, length = 4000)
    private String filePath;

    private Integer startLine;
    private Integer endLine;

    @Column(length = 1000)
    private String language;

    @Column(columnDefinition = "TEXT")
    private String signature;

    @Column(columnDefinition = "TEXT")
    private String docComment;

    @Column(columnDefinition = "TEXT")
    private String contentPreview;

    @Column(length = 500)
    private String parentFqn;

    @Column(length = 500)
    private String packageName;

    private String visibility;

    private Boolean isStatic;
    private Boolean isAbstract;

    @Column(length = 4000)
    private String metadataJson;

    /** The knowledge graph node ID this entity is stored as */
    private UUID graphNodeId;

    @Column(nullable = false)
    private Instant indexedAt;

    @PrePersist
    void prePersist() {
        if (indexedAt == null) indexedAt = Instant.now();
    }
}
