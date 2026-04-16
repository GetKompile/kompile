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

package ai.kompile.notebook.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Many-to-many join between Notebook and Fact (source document).
 *
 * A single Fact (source) can appear in multiple Notebooks.
 * A Notebook can contain many Facts.
 *
 * The factId field references ai.kompile.app.facts.domain.Fact.id.
 * We store it as a plain Long rather than a @ManyToOne to avoid a compile-time
 * cross-module JPA association — the Fact entity is in kompile-app-main which
 * would create a circular dependency if we depended on it here.
 * The service layer resolves the actual Fact via the FactRepository.
 */
@Entity
@Table(name = "notebook_sources", indexes = {
    @Index(name = "idx_nb_source_notebook", columnList = "notebook_id"),
    @Index(name = "idx_nb_source_fact", columnList = "factId"),
    @Index(name = "idx_nb_source_notebook_fact", columnList = "notebook_id,factId", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotebookSource {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /** The notebook this source belongs to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notebook_id", nullable = false)
    private Notebook notebook;

    /**
     * ID of the Fact (source document) in the facts table.
     * Intentionally a plain Long to avoid circular module dependency.
     */
    @Column(nullable = false)
    private Long factId;

    /**
     * Denormalised display label for UI (copied from Fact.fileName or Fact.title at add time).
     * Kept for quick listing without a join.
     */
    @Column(length = 512)
    private String displayName;

    /** When this source was added to the notebook. */
    @Column(nullable = false)
    private Instant addedAt;

    @PrePersist
    protected void onCreate() {
        if (addedAt == null) addedAt = Instant.now();
    }
}
