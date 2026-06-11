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

package ai.kompile.app.web.controllers;

import ai.kompile.app.facts.domain.Note;
import ai.kompile.app.facts.service.NoteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NoteControllerTest {

    @Mock
    private NoteService noteService;

    private NoteController controller;

    @BeforeEach
    void setUp() {
        controller = new NoteController(noteService);
    }

    private Note makeNote(Long id, Long factSheetId) {
        Note note = new Note();
        note.setId(id);
        note.setFactSheetId(factSheetId);
        note.setContent("Sample content");
        note.setNoteType(Note.NoteType.HUMAN);
        return note;
    }

    // ── listNotes ─────────────────────────────────────────────────────────

    @Test
    void listNotes_returnsList() {
        List<Note> notes = List.of(makeNote(1L, 10L), makeNote(2L, 10L));
        when(noteService.getNotesForFactSheet(10L)).thenReturn(notes);

        ResponseEntity<List<Note>> resp = controller.listNotes(10L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(2, resp.getBody().size());
    }

    // ── createNote ────────────────────────────────────────────────────────

    @Test
    void createNote_validRequest_returns201() {
        Note created = makeNote(1L, 10L);
        when(noteService.createNote(eq(10L), any(), any(), any(), any(), any())).thenReturn(created);

        NoteController.CreateNoteRequest body = new NoteController.CreateNoteRequest();
        body.title = "My Note";
        body.content = "Content";
        body.noteType = "HUMAN";

        ResponseEntity<Note> resp = controller.createNote(10L, body);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertSame(created, resp.getBody());
    }

    @Test
    void createNote_nullNoteType_defaultsToHuman() {
        Note created = makeNote(1L, 10L);
        when(noteService.createNote(eq(10L), any(), any(), eq(Note.NoteType.HUMAN), any(), any())).thenReturn(created);

        NoteController.CreateNoteRequest body = new NoteController.CreateNoteRequest();
        body.content = "Content";
        body.noteType = null;

        ResponseEntity<Note> resp = controller.createNote(10L, body);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
    }

    @Test
    void createNote_invalidNoteType_defaultsToHuman() {
        Note created = makeNote(1L, 10L);
        when(noteService.createNote(eq(10L), any(), any(), eq(Note.NoteType.HUMAN), any(), any())).thenReturn(created);

        NoteController.CreateNoteRequest body = new NoteController.CreateNoteRequest();
        body.content = "Content";
        body.noteType = "INVALID_TYPE";

        ResponseEntity<Note> resp = controller.createNote(10L, body);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
    }

    // ── searchNotes ───────────────────────────────────────────────────────

    @Test
    void searchNotes_returnsResults() {
        List<Note> notes = List.of(makeNote(1L, 10L));
        when(noteService.searchNotes(10L, "query")).thenReturn(notes);

        ResponseEntity<List<Note>> resp = controller.searchNotes(10L, "query");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().size());
    }

    // ── promoteFromChat ───────────────────────────────────────────────────

    @Test
    void promoteFromChat_returns201() {
        Note promoted = makeNote(5L, 10L);
        promoted.setNoteType(Note.NoteType.AI);
        when(noteService.promoteFromChat(eq(10L), any(), any(), any())).thenReturn(promoted);

        NoteController.PromoteRequest body = new NoteController.PromoteRequest();
        body.content = "AI-generated text";
        body.sourceOriginRef = "ref-1";
        body.passageSnippet = "snippet";

        ResponseEntity<Note> resp = controller.promoteFromChat(10L, body);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertSame(promoted, resp.getBody());
    }

    // ── getNote ───────────────────────────────────────────────────────────

    @Test
    void getNote_found_returns200() {
        Note note = makeNote(1L, 10L);
        when(noteService.getNote(1L)).thenReturn(Optional.of(note));

        ResponseEntity<Note> resp = controller.getNote(1L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(note, resp.getBody());
    }

    @Test
    void getNote_notFound_returns404() {
        when(noteService.getNote(999L)).thenReturn(Optional.empty());

        ResponseEntity<Note> resp = controller.getNote(999L);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // ── updateNote ────────────────────────────────────────────────────────

    @Test
    void updateNote_returns200() {
        Note updated = makeNote(1L, 10L);
        updated.setContent("Updated content");
        when(noteService.updateNote(eq(1L), any(), any(), any())).thenReturn(updated);

        NoteController.UpdateNoteRequest body = new NoteController.UpdateNoteRequest();
        body.title = "New Title";
        body.content = "Updated content";

        ResponseEntity<Note> resp = controller.updateNote(1L, body);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertSame(updated, resp.getBody());
    }

    // ── deleteNote ────────────────────────────────────────────────────────

    @Test
    void deleteNote_returns204() {
        doNothing().when(noteService).deleteNote(1L);

        ResponseEntity<Void> resp = controller.deleteNote(1L);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        verify(noteService).deleteNote(1L);
    }

    // ── exception handler ─────────────────────────────────────────────────

    @Test
    void handleIllegalArgument_returnsBadRequest() {
        ResponseEntity<String> resp = controller.handleIllegalArgument(new IllegalArgumentException("bad arg"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("bad arg", resp.getBody());
    }
}
