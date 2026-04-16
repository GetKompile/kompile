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

package ai.kompile.notebook.repository;

import ai.kompile.notebook.domain.Note;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NoteRepository extends JpaRepository<Note, Long> {

    List<Note> findByNotebookIdOrderByCreatedAtDesc(Long notebookId);

    List<Note> findByNotebookIdAndNoteTypeOrderByCreatedAtDesc(Long notebookId, Note.NoteType noteType);

    /** Find notes that have not yet been embedded. */
    List<Note> findByEmbeddedFalseOrderByCreatedAtAsc();

    /** Find un-embedded notes for a specific notebook. */
    List<Note> findByNotebookIdAndEmbeddedFalseOrderByCreatedAtAsc(Long notebookId);

    /** Simple text search on title and content. */
    @Query("SELECT n FROM Note n WHERE n.notebook.id = :notebookId AND " +
           "(LOWER(n.title) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(n.content) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<Note> searchByText(Long notebookId, String query);

    long countByNotebookId(Long notebookId);
}
