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

package ai.kompile.notebook;

import ai.kompile.notebook.domain.ChatSessionContext;
import ai.kompile.notebook.domain.Note;
import ai.kompile.notebook.domain.Notebook;
import ai.kompile.notebook.domain.NotebookSource;
import ai.kompile.notebook.service.NoteEmbeddingService;
import ai.kompile.notebook.service.NoteService;
import ai.kompile.notebook.service.NotebookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test for NotebookService and NoteService using an in-memory H2 database.
 *
 * Tests:
 *  1. Create a notebook and retrieve it
 *  2. Add a source (factId reference) and remove it (idempotent add)
 *  3. Create a note in the notebook
 *  4. Update a note
 *  5. Delete a note
 *  6. Per-session source context (FULL / EXCLUDED)
 *  7. Search notes by keyword
 *  8. Promote a chat response to a note
 *
 * The EmbeddingModel is not present in this test context so NoteEmbeddingService
 * gracefully skips embedding and marks notes as not-embedded — which is expected.
 */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.datasource.url=jdbc:h2:mem:notebooktest;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "spring.jpa.properties.hibernate.bytecode.provider=none"
})
public class NotebookServiceTest {

    @Autowired
    private NotebookService notebookService;

    @Autowired
    private NoteService noteService;

    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void createAndRetrieveNotebook() {
        Notebook nb = notebookService.createNotebook("My Research", "Test notebook", "book", "#1976d2");
        assertThat(nb.getId()).isNotNull();
        assertThat(nb.getName()).isEqualTo("My Research");
        assertThat(nb.getArchived()).isFalse();

        List<Notebook> all = notebookService.listNotebooks();
        assertThat(all).isNotEmpty();
        assertThat(all.stream().anyMatch(n -> "My Research".equals(n.getName()))).isTrue();
    }

    @Test
    void addAndRemoveSource() {
        Notebook nb = notebookService.createNotebook("Source Test " + System.nanoTime(), null, null, null);
        Long notebookId = nb.getId();

        NotebookSource ns = notebookService.addSource(notebookId, 42L, "my-document.pdf");
        assertThat(ns.getId()).isNotNull();
        assertThat(ns.getFactId()).isEqualTo(42L);
        assertThat(ns.getDisplayName()).isEqualTo("my-document.pdf");

        List<NotebookSource> sources = notebookService.getSourcesForNotebook(notebookId);
        assertThat(sources).hasSize(1);

        // Adding the same fact again should be idempotent
        notebookService.addSource(notebookId, 42L, "my-document.pdf");
        assertThat(notebookService.getSourcesForNotebook(notebookId)).hasSize(1);

        notebookService.removeSource(notebookId, 42L);
        assertThat(notebookService.getSourcesForNotebook(notebookId)).isEmpty();
    }

    @Test
    void createNoteAndList() {
        Notebook nb = notebookService.createNotebook("Note Test " + System.nanoTime(), null, null, null);
        Long notebookId = nb.getId();

        Note note = noteService.createNote(notebookId, "Key Finding", "This is important.", Note.NoteType.HUMAN, null, "ai,research");
        assertThat(note.getId()).isNotNull();
        assertThat(note.getTitle()).isEqualTo("Key Finding");
        assertThat(note.getNoteType()).isEqualTo(Note.NoteType.HUMAN);
        // Embedding is skipped in tests (no EmbeddingModel bean) — embedded stays false
        assertThat(note.getEmbedded()).isFalse();

        List<Note> notes = noteService.getNotesForNotebook(notebookId);
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).getTitle()).isEqualTo("Key Finding");
    }

    @Test
    void updateNote() {
        Notebook nb = notebookService.createNotebook("Update Test " + System.nanoTime(), null, null, null);
        Note note = noteService.createNote(nb.getId(), "Original", "Original content.", Note.NoteType.HUMAN, null, null);

        Note updated = noteService.updateNote(note.getId(), "Updated Title", "Updated content.", "newTag");
        assertThat(updated.getTitle()).isEqualTo("Updated Title");
        assertThat(updated.getContent()).isEqualTo("Updated content.");
        assertThat(updated.getTags()).isEqualTo("newTag");
        // Content changed → re-embedding triggered (stays false since no model)
        assertThat(updated.getEmbedded()).isFalse();
    }

    @Test
    void deleteNote() {
        Notebook nb = notebookService.createNotebook("Delete Test " + System.nanoTime(), null, null, null);
        Note note = noteService.createNote(nb.getId(), null, "To be deleted.", Note.NoteType.HUMAN, null, null);

        noteService.deleteNote(note.getId());
        assertThat(noteService.getNotesForNotebook(nb.getId())).isEmpty();
    }

    @Test
    void chatSessionContextPerSource() {
        Notebook nb = notebookService.createNotebook("Context Test " + System.nanoTime(), null, null, null);
        notebookService.addSource(nb.getId(), 10L, "source-a.pdf");
        notebookService.addSource(nb.getId(), 20L, "source-b.pdf");

        String sessionId = "session-abc-" + System.nanoTime();

        // Default mode (FULL) — no explicit context row
        List<Long> excluded = notebookService.getExcludedFactIds(sessionId);
        assertThat(excluded).isEmpty();

        // Exclude source 10
        notebookService.setSourceMode(sessionId, nb.getId(), 10L, "source-a.pdf", ChatSessionContext.SourceMode.EXCLUDED);
        excluded = notebookService.getExcludedFactIds(sessionId);
        assertThat(excluded).containsExactly(10L);

        // Restore to FULL
        notebookService.setSourceMode(sessionId, nb.getId(), 10L, "source-a.pdf", ChatSessionContext.SourceMode.FULL);
        excluded = notebookService.getExcludedFactIds(sessionId);
        assertThat(excluded).isEmpty();
    }

    @Test
    void searchNotes() {
        Notebook nb = notebookService.createNotebook("Search Test " + System.nanoTime(), null, null, null);
        noteService.createNote(nb.getId(), "Alpha Note", "Content about alpha.", Note.NoteType.HUMAN, null, null);
        noteService.createNote(nb.getId(), "Beta Note", "Content about beta.", Note.NoteType.HUMAN, null, null);

        List<Note> results = noteService.searchNotes(nb.getId(), "alpha");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("Alpha Note");

        List<Note> all = noteService.searchNotes(nb.getId(), null);
        assertThat(all).hasSize(2);
    }

    @Test
    void promoteFromChat() {
        Notebook nb = notebookService.createNotebook("Promote Test " + System.nanoTime(), null, null, null);
        String sourceRef = "file:///some-doc.pdf";

        Note note = noteService.promoteFromChat(nb.getId(), "Important passage from the document.", sourceRef, "the passage...");
        assertThat(note.getNoteType()).isEqualTo(Note.NoteType.AI);
        assertThat(note.getSourceOriginRef()).isEqualTo(sourceRef);

        List<Note> notes = noteService.getNotesForNotebook(nb.getId());
        assertThat(notes).hasSize(1);
    }
}
