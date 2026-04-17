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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * REST controller for Notes attached to a FactSheet.
 *
 * FactSheet IS Kompile's notebook primitive — notes layer on top and share the
 * embedding pipeline so hybrid search crosses both sources and notes uniformly.
 *
 * Endpoints:
 * <ul>
 *   <li>GET    /api/fact-sheets/{factSheetId}/notes             — list notes</li>
 *   <li>POST   /api/fact-sheets/{factSheetId}/notes             — create note</li>
 *   <li>GET    /api/fact-sheets/{factSheetId}/notes/search?q=   — search notes</li>
 *   <li>POST   /api/fact-sheets/{factSheetId}/notes/promote     — promote chat response to note</li>
 *   <li>GET    /api/notes/{noteId}                              — get one note</li>
 *   <li>PUT    /api/notes/{noteId}                              — update note</li>
 *   <li>DELETE /api/notes/{noteId}                              — delete note</li>
 * </ul>
 */
@RestController
public class NoteController {

    private final NoteService noteService;

    @Autowired
    public NoteController(NoteService noteService) {
        this.noteService = noteService;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // LIST / CREATE under a FactSheet
    // ──────────────────────────────────────────────────────────────────────────

    @GetMapping("/api/fact-sheets/{factSheetId}/notes")
    public ResponseEntity<List<Note>> listNotes(@PathVariable Long factSheetId) {
        return ResponseEntity.ok(noteService.getNotesForFactSheet(factSheetId));
    }

    @PostMapping("/api/fact-sheets/{factSheetId}/notes")
    public ResponseEntity<Note> createNote(@PathVariable Long factSheetId,
                                           @RequestBody CreateNoteRequest body) {
        Note.NoteType type = parseNoteType(body.noteType);
        Note note = noteService.createNote(factSheetId, body.title, body.content,
                type, body.sourceOriginRef, body.tags);
        return ResponseEntity.status(HttpStatus.CREATED).body(note);
    }

    @GetMapping("/api/fact-sheets/{factSheetId}/notes/search")
    public ResponseEntity<List<Note>> searchNotes(@PathVariable Long factSheetId,
                                                  @RequestParam(value = "q", required = false) String q) {
        return ResponseEntity.ok(noteService.searchNotes(factSheetId, q));
    }

    @PostMapping("/api/fact-sheets/{factSheetId}/notes/promote")
    public ResponseEntity<Note> promoteFromChat(@PathVariable Long factSheetId,
                                                @RequestBody PromoteRequest body) {
        Note note = noteService.promoteFromChat(factSheetId, body.content,
                body.sourceOriginRef, body.passageSnippet);
        return ResponseEntity.status(HttpStatus.CREATED).body(note);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Single-note GET/PUT/DELETE — flat path keyed by noteId
    // ──────────────────────────────────────────────────────────────────────────

    @GetMapping("/api/notes/{noteId}")
    public ResponseEntity<Note> getNote(@PathVariable Long noteId) {
        Optional<Note> note = noteService.getNote(noteId);
        return note.map(ResponseEntity::ok)
                   .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/api/notes/{noteId}")
    public ResponseEntity<Note> updateNote(@PathVariable Long noteId,
                                           @RequestBody UpdateNoteRequest body) {
        Note note = noteService.updateNote(noteId, body.title, body.content, body.tags);
        return ResponseEntity.ok(note);
    }

    @DeleteMapping("/api/notes/{noteId}")
    public ResponseEntity<Void> deleteNote(@PathVariable Long noteId) {
        noteService.deleteNote(noteId);
        return ResponseEntity.noContent().build();
    }

    // ──────────────────────────────────────────────────────────────────────────

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }

    private static Note.NoteType parseNoteType(String type) {
        if (type == null) return Note.NoteType.HUMAN;
        try {
            return Note.NoteType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Note.NoteType.HUMAN;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Request bodies
    // ──────────────────────────────────────────────────────────────────────────

    public static class CreateNoteRequest {
        public String title;
        public String content;
        public String noteType;
        public String sourceOriginRef;
        public String tags;
    }

    public static class UpdateNoteRequest {
        public String title;
        public String content;
        public String tags;
    }

    public static class PromoteRequest {
        public String content;
        public String sourceOriginRef;
        public String passageSnippet;
    }
}
