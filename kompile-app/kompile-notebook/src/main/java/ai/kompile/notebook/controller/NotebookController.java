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

package ai.kompile.notebook.controller;

import ai.kompile.notebook.domain.ChatSessionContext;
import ai.kompile.notebook.domain.Note;
import ai.kompile.notebook.domain.Notebook;
import ai.kompile.notebook.domain.NotebookSource;
import ai.kompile.notebook.service.NoteService;
import ai.kompile.notebook.service.NotebookService;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for the Notebook research workspace feature.
 *
 * Endpoints:
 *   GET    /api/notebook                       — list notebooks
 *   POST   /api/notebook                       — create notebook
 *   GET    /api/notebook/{id}                  — get notebook
 *   PUT    /api/notebook/{id}                  — update notebook
 *   POST   /api/notebook/{id}/archive          — archive
 *   DELETE /api/notebook/{id}                  — delete
 *
 *   GET    /api/notebook/{id}/sources          — list sources
 *   POST   /api/notebook/{id}/sources          — add source (factId)
 *   DELETE /api/notebook/{id}/sources/{factId} — remove source
 *
 *   GET    /api/notebook/{id}/notes            — list notes
 *   POST   /api/notebook/{id}/notes            — create note
 *   PUT    /api/notebook/{id}/notes/{noteId}   — update note
 *   DELETE /api/notebook/{id}/notes/{noteId}   — delete note
 *   POST   /api/notebook/{id}/notes/promote    — promote chat response to note
 *   GET    /api/notebook/{id}/notes/search     — text search notes
 *
 *   GET    /api/notebook/{id}/chat/{sessionId}/context   — get session source context
 *   PUT    /api/notebook/{id}/chat/{sessionId}/context   — set source mode
 */
@RestController
@RequestMapping("/api/notebook")
public class NotebookController {

    private static final Logger logger = LoggerFactory.getLogger(NotebookController.class);

    private final NotebookService notebookService;
    private final NoteService noteService;

    @Autowired
    public NotebookController(NotebookService notebookService, NoteService noteService) {
        this.notebookService = notebookService;
        this.noteService = noteService;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // NOTEBOOK CRUD
    // ──────────────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<NotebookDto>> listNotebooks(
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        List<Notebook> notebooks = includeArchived
                ? notebookService.listAllNotebooks()
                : notebookService.listNotebooks();
        return ResponseEntity.ok(notebooks.stream().map(NotebookDto::from).collect(Collectors.toList()));
    }

    @PostMapping
    public ResponseEntity<NotebookDto> createNotebook(@RequestBody CreateNotebookRequest req) {
        try {
            Notebook nb = notebookService.createNotebook(req.name(), req.description(), req.icon(), req.color());
            return ResponseEntity.status(HttpStatus.CREATED).body(NotebookDto.from(nb));
        } catch (Exception e) {
            logger.error("Failed to create notebook: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<NotebookDto> getNotebook(@PathVariable Long id) {
        return notebookService.getNotebook(id)
                .map(nb -> ResponseEntity.ok(NotebookDto.from(nb)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<NotebookDto> updateNotebook(@PathVariable Long id,
                                                       @RequestBody UpdateNotebookRequest req) {
        try {
            Notebook nb = notebookService.updateNotebook(id, req.name(), req.description(), req.icon(), req.color());
            return ResponseEntity.ok(NotebookDto.from(nb));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Failed to update notebook {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<NotebookDto> archiveNotebook(@PathVariable Long id) {
        try {
            Notebook nb = notebookService.archiveNotebook(id);
            return ResponseEntity.ok(NotebookDto.from(nb));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNotebook(@PathVariable Long id) {
        try {
            notebookService.deleteNotebook(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SOURCES
    // ──────────────────────────────────────────────────────────────────────────

    @GetMapping("/{id}/sources")
    public ResponseEntity<List<SourceDto>> getSources(@PathVariable Long id) {
        List<NotebookSource> sources = notebookService.getSourcesForNotebook(id);
        return ResponseEntity.ok(sources.stream().map(SourceDto::from).collect(Collectors.toList()));
    }

    @PostMapping("/{id}/sources")
    public ResponseEntity<SourceDto> addSource(@PathVariable Long id,
                                                @RequestBody AddSourceRequest req) {
        try {
            NotebookSource ns = notebookService.addSource(id, req.factId(), req.displayName());
            return ResponseEntity.status(HttpStatus.CREATED).body(SourceDto.from(ns));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Failed to add source {} to notebook {}: {}", req.factId(), id, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping("/{id}/sources/{factId}")
    public ResponseEntity<Void> removeSource(@PathVariable Long id, @PathVariable Long factId) {
        try {
            notebookService.removeSource(id, factId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // NOTES
    // ──────────────────────────────────────────────────────────────────────────

    @GetMapping("/{id}/notes")
    public ResponseEntity<List<NoteDto>> getNotes(@PathVariable Long id) {
        List<Note> notes = noteService.getNotesForNotebook(id);
        return ResponseEntity.ok(notes.stream().map(NoteDto::from).collect(Collectors.toList()));
    }

    @GetMapping("/{id}/notes/search")
    public ResponseEntity<List<NoteDto>> searchNotes(@PathVariable Long id,
                                                      @RequestParam String q) {
        List<Note> notes = noteService.searchNotes(id, q);
        return ResponseEntity.ok(notes.stream().map(NoteDto::from).collect(Collectors.toList()));
    }

    @PostMapping("/{id}/notes")
    public ResponseEntity<NoteDto> createNote(@PathVariable Long id,
                                               @RequestBody CreateNoteRequest req) {
        try {
            Note.NoteType type = req.noteType() != null
                    ? Note.NoteType.valueOf(req.noteType().toUpperCase())
                    : Note.NoteType.HUMAN;
            Note note = noteService.createNote(id, req.title(), req.content(), type,
                    req.sourceOriginRef(), req.tags());
            return ResponseEntity.status(HttpStatus.CREATED).body(NoteDto.from(note));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Failed to create note in notebook {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PutMapping("/{id}/notes/{noteId}")
    public ResponseEntity<NoteDto> updateNote(@PathVariable Long id, @PathVariable Long noteId,
                                               @RequestBody UpdateNoteRequest req) {
        try {
            Note note = noteService.updateNote(noteId, req.title(), req.content(), req.tags());
            return ResponseEntity.ok(NoteDto.from(note));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Failed to update note {}: {}", noteId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping("/{id}/notes/{noteId}")
    public ResponseEntity<Void> deleteNote(@PathVariable Long id, @PathVariable Long noteId) {
        try {
            noteService.deleteNote(noteId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/notes/promote")
    public ResponseEntity<NoteDto> promoteFromChat(@PathVariable Long id,
                                                    @RequestBody PromoteNoteRequest req) {
        try {
            Note note = noteService.promoteFromChat(id, req.content(), req.sourceOriginRef(), req.passageSnippet());
            return ResponseEntity.status(HttpStatus.CREATED).body(NoteDto.from(note));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Failed to promote chat response to note in notebook {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CHAT SESSION CONTEXT
    // ──────────────────────────────────────────────────────────────────────────

    @GetMapping("/{id}/chat/{sessionId}/context")
    public ResponseEntity<List<SessionContextDto>> getSessionContext(@PathVariable Long id,
                                                                      @PathVariable String sessionId) {
        List<ChatSessionContext> ctxList = notebookService.getSessionContexts(sessionId);
        return ResponseEntity.ok(ctxList.stream().map(SessionContextDto::from).collect(Collectors.toList()));
    }

    @PutMapping("/{id}/chat/{sessionId}/context")
    public ResponseEntity<SessionContextDto> setSourceMode(@PathVariable Long id,
                                                            @PathVariable String sessionId,
                                                            @RequestBody SetSourceModeRequest req) {
        try {
            ChatSessionContext.SourceMode mode = ChatSessionContext.SourceMode.valueOf(req.mode().toUpperCase());
            ChatSessionContext ctx = notebookService.setSourceMode(
                    sessionId, id, req.factId(), req.displayName(), mode);
            return ResponseEntity.ok(SessionContextDto.from(ctx));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Failed to set source mode in session {}: {}", sessionId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // DTOs and request records
    // ──────────────────────────────────────────────────────────────────────────

    public record CreateNotebookRequest(String name, String description, String icon, String color) {}
    public record UpdateNotebookRequest(String name, String description, String icon, String color) {}
    public record AddSourceRequest(Long factId, String displayName) {}
    public record CreateNoteRequest(String title, String content, String noteType,
                                    String sourceOriginRef, String tags) {}
    public record UpdateNoteRequest(String title, String content, String tags) {}
    public record PromoteNoteRequest(String content, String sourceOriginRef, String passageSnippet) {}
    public record SetSourceModeRequest(Long factId, String displayName, String mode) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NotebookDto(Long id, String name, String description, String icon, String color,
                               Boolean archived, String ownerUserId,
                               Instant createdAt, Instant updatedAt) {
        public static NotebookDto from(Notebook n) {
            return new NotebookDto(n.getId(), n.getName(), n.getDescription(),
                    n.getIcon(), n.getColor(), n.getArchived(), n.getOwnerUserId(),
                    n.getCreatedAt(), n.getUpdatedAt());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SourceDto(Long id, Long notebookId, Long factId, String displayName, Instant addedAt) {
        public static SourceDto from(NotebookSource ns) {
            return new SourceDto(ns.getId(), ns.getNotebook().getId(), ns.getFactId(),
                    ns.getDisplayName(), ns.getAddedAt());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NoteDto(Long id, Long notebookId, String title, String content, String noteType,
                           String sourceOriginRef, String tags, Boolean embedded,
                           Instant createdAt, Instant updatedAt) {
        public static NoteDto from(Note n) {
            return new NoteDto(n.getId(), n.getNotebook().getId(), n.getTitle(), n.getContent(),
                    n.getNoteType().name(), n.getSourceOriginRef(), n.getTags(), n.getEmbedded(),
                    n.getCreatedAt(), n.getUpdatedAt());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionContextDto(Long id, String sessionId, Long notebookId, Long factId,
                                     String sourceDisplayName, String mode,
                                     Instant createdAt, Instant updatedAt) {
        public static SessionContextDto from(ChatSessionContext ctx) {
            return new SessionContextDto(ctx.getId(), ctx.getSessionId(), ctx.getNotebookId(),
                    ctx.getFactId(), ctx.getSourceDisplayName(), ctx.getMode().name(),
                    ctx.getCreatedAt(), ctx.getUpdatedAt());
        }
    }
}
