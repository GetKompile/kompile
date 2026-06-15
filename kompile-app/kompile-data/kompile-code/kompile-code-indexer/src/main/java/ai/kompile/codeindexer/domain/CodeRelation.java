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
 * A relationship between two code entities — e.g. a method calling another method,
 * a class extending another class, or a file importing a symbol. Persisted so that
 * call-graph and unused-function queries can run without the knowledge graph.
 */
@Entity
@Table(name = "code_relations",
       indexes = {
           @Index(name = "idx_code_rel_project", columnList = "projectId"),
           @Index(name = "idx_code_rel_type", columnList = "relationType"),
           @Index(name = "idx_code_rel_target_name", columnList = "targetName"),
           @Index(name = "idx_code_rel_source", columnList = "sourceFqn"),
           @Index(name = "idx_code_rel_project_type", columnList = "projectId,relationType")
       })
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class CodeRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CodeRelationType relationType;

    /** FQN of the source entity (e.g. the calling method). */
    @Column(nullable = false, length = 2000)
    private String sourceFqn;

    /** Simple name of the target (e.g. the called function name). */
    @Column(nullable = false, length = 500)
    private String targetName;

    /** FQN of the target entity, if it could be resolved within the same file. */
    @Column(length = 2000)
    private String targetFqn;

    /** File where this relationship was found. */
    @Column(length = 4000)
    private String filePath;

    /** Line number where the relationship occurs (e.g. the call site). */
    private Integer line;

    @Column(nullable = false)
    private Instant indexedAt;

    @PrePersist
    void prePersist() {
        if (indexedAt == null) indexedAt = Instant.now();
    }
}
