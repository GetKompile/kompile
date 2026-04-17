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

package ai.kompile.app.facts.service;

import ai.kompile.app.facts.domain.ChatSessionContext;
import ai.kompile.app.facts.domain.Note;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test for NoteService and ChatSessionContextService using an in-memory H2 database.
 *
 * FactSheet IS Kompile's notebook primitive — notes and chat session contexts live alongside
 * the FactSheet entity in {@code ai.kompile.app.facts}. Tests use arbitrary {@code factSheetId}
 * values since referential integrity is enforced at the application level.
 *
 * Uses {@link DataJpaTest} with explicit imports of the services under test to keep the
 * Spring context small. The embedding model is not wired in so notes remain unembedded —
 * which is the expected behaviour when no model is available.
 */
@DataJpaTest
@Import({NoteService.class, NoteEmbeddingService.class, ChatSessionContextService.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.datasource.url=jdbc:h2:mem:notetest;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "spring.jpa.properties.hibernate.bytecode.provider=none"
})
public class NoteServiceTest {

    @Autowired
    private NoteService noteService;

    @Autowired
    private ChatSessionContextService chatSessionContextService;

    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void createNoteAndList() {
        Long factSheetId = nextFactSheetId();

        Note note = noteService.createNote(factSheetId, "Key Finding", "This is important.",
                Note.NoteType.HUMAN, null, "ai,research");
        assertThat(note.getId()).isNotNull();
        assertThat(note.getTitle()).isEqualTo("Key Finding");
        assertThat(note.getNoteType()).isEqualTo(Note.NoteType.HUMAN);
        assertThat(note.getFactSheetId()).isEqualTo(factSheetId);
        assertThat(note.getEmbedded()).isFalse();

        List<Note> notes = noteService.getNotesForFactSheet(factSheetId);
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).getTitle()).isEqualTo("Key Finding");
    }

    @Test
    void updateNote() {
        Long factSheetId = nextFactSheetId();
        Note note = noteService.createNote(factSheetId, "Original", "Original content.",
                Note.NoteType.HUMAN, null, null);

        Note updated = noteService.updateNote(note.getId(), "Updated Title", "Updated content.", "newTag");
        assertThat(updated.getTitle()).isEqualTo("Updated Title");
        assertThat(updated.getContent()).isEqualTo("Updated content.");
        assertThat(updated.getTags()).isEqualTo("newTag");
        assertThat(updated.getEmbedded()).isFalse();
    }

    @Test
    void deleteNote() {
        Long factSheetId = nextFactSheetId();
        Note note = noteService.createNote(factSheetId, null, "To be deleted.",
                Note.NoteType.HUMAN, null, null);

        noteService.deleteNote(note.getId());
        assertThat(noteService.getNotesForFactSheet(factSheetId)).isEmpty();
    }

    @Test
    void chatSessionContextPerSource() {
        Long factSheetId = nextFactSheetId();
        String sessionId = "session-abc-" + System.nanoTime();

        List<Long> excluded = chatSessionContextService.getExcludedFactIds(sessionId);
        assertThat(excluded).isEmpty();

        chatSessionContextService.setSourceMode(sessionId, factSheetId, 10L, "source-a.pdf",
                ChatSessionContext.SourceMode.EXCLUDED);
        excluded = chatSessionContextService.getExcludedFactIds(sessionId);
        assertThat(excluded).containsExactly(10L);

        chatSessionContextService.setSourceMode(sessionId, factSheetId, 10L, "source-a.pdf",
                ChatSessionContext.SourceMode.FULL);
        excluded = chatSessionContextService.getExcludedFactIds(sessionId);
        assertThat(excluded).isEmpty();
    }

    @Test
    void searchNotes() {
        Long factSheetId = nextFactSheetId();
        noteService.createNote(factSheetId, "Alpha Note", "Content about alpha.",
                Note.NoteType.HUMAN, null, null);
        noteService.createNote(factSheetId, "Beta Note", "Content about beta.",
                Note.NoteType.HUMAN, null, null);

        List<Note> results = noteService.searchNotes(factSheetId, "alpha");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("Alpha Note");

        List<Note> all = noteService.searchNotes(factSheetId, null);
        assertThat(all).hasSize(2);
    }

    @Test
    void promoteFromChat() {
        Long factSheetId = nextFactSheetId();
        String sourceRef = "file:///some-doc.pdf";

        Note note = noteService.promoteFromChat(factSheetId, "Important passage from the document.",
                sourceRef, "the passage...");
        assertThat(note.getNoteType()).isEqualTo(Note.NoteType.AI);
        assertThat(note.getSourceOriginRef()).isEqualTo(sourceRef);

        List<Note> notes = noteService.getNotesForFactSheet(factSheetId);
        assertThat(notes).hasSize(1);
    }

    // ──────────────────────────────────────────────────────────────────────────

    /** Each test uses a unique factSheetId so H2 state doesn't leak across tests. */
    private static Long nextFactSheetId() {
        return System.nanoTime();
    }
}
