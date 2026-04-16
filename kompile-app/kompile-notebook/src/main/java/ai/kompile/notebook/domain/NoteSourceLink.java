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
 * Optional fine-grained back-reference from a Note to a specific source chunk or chat response.
 *
 * This is the "NoteSourceLink" from the plan — a note can cite multiple source passages,
 * and this entity tracks those citations using the same SourceMetadataConstants keys.
 *
 * Phase 1 creates this table but it is populated optionally (by the NotebookService.linkNoteToSource
 * method). It becomes the foundation for Phase 3's annotation/highlighting features.
 */
@Entity
@Table(name = "note_source_links", indexes = {
    @Index(name = "idx_nsl_note", columnList = "note_id"),
    @Index(name = "idx_nsl_source_id", columnList = "sourceId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteSourceLink {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /** The note that references this source. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "note_id", nullable = false)
    private Note note;

    /**
     * The SourceMetadataConstants.SOURCE_ID value of the referenced document or chunk.
     * Matches how the rest of the retrieval pipeline tracks source identity.
     */
    @Column(nullable = false, length = 1024)
    private String sourceId;

    /** Optional chunk index within the source document. */
    @Column
    private Integer chunkIndex;

    /** Optional page number within the source document. */
    @Column
    private Integer pageNumber;

    /** A short snippet of the source passage that the note was derived from. */
    @Column(columnDefinition = "TEXT")
    private String passageSnippet;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
