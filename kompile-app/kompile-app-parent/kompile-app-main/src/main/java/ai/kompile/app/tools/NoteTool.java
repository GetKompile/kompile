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

package ai.kompile.app.tools;

import ai.kompile.app.facts.domain.Note;
import ai.kompile.app.facts.service.NoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Spring AI @Tool bean for note operations against a FactSheet.
 *
 * Notes created or updated through this tool are automatically embedded into
 * the same vector store as Facts via NoteEmbeddingService.
 */
@Component
public class NoteTool {

    private static final Logger logger = LoggerFactory.getLogger(NoteTool.class);

    private final NoteService noteService;

    @Autowired
    public NoteTool(@Autowired(required = false) NoteService noteService) {
        this.noteService = noteService;
        logger.info("NoteTool initialized");
    }

    public record ListNotesInput(Long factSheetId) {}
    public record SearchNotesInput(Long factSheetId, String query) {}
    public record CreateNoteInput(Long factSheetId, String title, String content,
                                   String noteType, String sourceOriginRef, String tags) {}
    public record UpdateNoteInput(Long noteId, String title, String content, String tags) {}
    public record DeleteNoteInput(Long noteId) {}
    public record PromoteFromChatInput(Long factSheetId, String content,
                                       String sourceOriginRef, String passageSnippet) {}

    @Tool(name = "list_notes",
          description = "Lists all notes attached to a FactSheet, ordered by creation date (newest first).")
    public Map<String, Object> listNotes(ListNotesInput input) {
        try {
            if (noteService == null) return errorMap("NoteService not available");
            if (input.factSheetId() == null) return errorMap("factSheetId is required");
            List<Note> notes = noteService.getNotesForFactSheet(input.factSheetId());
            return buildNoteListResult(notes);
        } catch (Exception e) {
            logger.error("Error listing notes for FactSheet {}: {}", input.factSheetId(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "search_notes",
          description = "Searches notes in a FactSheet by text query (title and content). Returns matching notes.")
    public Map<String, Object> searchNotes(SearchNotesInput input) {
        try {
            if (noteService == null) return errorMap("NoteService not available");
            if (input.factSheetId() == null) return errorMap("factSheetId is required");
            List<Note> notes = noteService.searchNotes(input.factSheetId(), input.query());
            return buildNoteListResult(notes);
        } catch (Exception e) {
            logger.error("Error searching notes for FactSheet {}: {}", input.factSheetId(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "create_note",
          description = "Creates a new note attached to a FactSheet. The note is automatically embedded for hybrid search. " +
                        "noteType: HUMAN (default) or AI. sourceOriginRef: source_id of the originating chunk (optional).")
    public Map<String, Object> createNote(CreateNoteInput input) {
        try {
            if (noteService == null) return errorMap("NoteService not available");
            if (input.factSheetId() == null) return errorMap("factSheetId is required");
            if (input.content() == null || input.content().isBlank()) return errorMap("content is required");
            Note.NoteType type = parseNoteType(input.noteType());
            Note note = noteService.createNote(input.factSheetId(), input.title(), input.content(),
                    type, input.sourceOriginRef(), input.tags());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("id", note.getId());
            result.put("title", note.getTitle());
            result.put("message", "Note created and queued for embedding");
            return result;
        } catch (Exception e) {
            logger.error("Error creating note in FactSheet {}: {}", input.factSheetId(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "update_note",
          description = "Updates a note's title, content, or tags. If content changes, the note is re-embedded automatically.")
    public Map<String, Object> updateNote(UpdateNoteInput input) {
        try {
            if (noteService == null) return errorMap("NoteService not available");
            if (input.noteId() == null) return errorMap("noteId is required");
            Note note = noteService.updateNote(input.noteId(), input.title(), input.content(), input.tags());
            return Map.of("status", "success", "id", note.getId(), "message", "Note updated");
        } catch (Exception e) {
            logger.error("Error updating note {}: {}", input.noteId(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "delete_note",
          description = "Permanently deletes a note.")
    public Map<String, Object> deleteNote(DeleteNoteInput input) {
        try {
            if (noteService == null) return errorMap("NoteService not available");
            if (input.noteId() == null) return errorMap("noteId is required");
            noteService.deleteNote(input.noteId());
            return Map.of("status", "success", "message", "Note deleted", "id", input.noteId());
        } catch (Exception e) {
            logger.error("Error deleting note {}: {}", input.noteId(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    @Tool(name = "promote_chat_response_to_note",
          description = "Promotes a chat response passage to a permanent AI-authored note attached to a FactSheet. " +
                        "Links the note back to its source via sourceOriginRef (SourceMetadataConstants.SOURCE_ID).")
    public Map<String, Object> promoteFromChat(PromoteFromChatInput input) {
        try {
            if (noteService == null) return errorMap("NoteService not available");
            if (input.factSheetId() == null) return errorMap("factSheetId is required");
            if (input.content() == null || input.content().isBlank()) return errorMap("content is required");
            Note note = noteService.promoteFromChat(input.factSheetId(), input.content(),
                    input.sourceOriginRef(), input.passageSnippet());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("id", note.getId());
            result.put("message", "Chat response promoted to note");
            return result;
        } catch (Exception e) {
            logger.error("Error promoting chat response to note in FactSheet {}: {}", input.factSheetId(), e.getMessage(), e);
            return errorMap(e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────

    private Map<String, Object> buildNoteListResult(List<Note> notes) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("count", notes.size());
        result.put("notes", notes.stream().map(n -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("factSheetId", n.getFactSheetId());
            m.put("title", n.getTitle());
            m.put("content", n.getContent() != null && n.getContent().length() > 200
                    ? n.getContent().substring(0, 197) + "..."
                    : n.getContent());
            m.put("noteType", n.getNoteType().name());
            m.put("embedded", n.getEmbedded());
            m.put("tags", n.getTags());
            m.put("createdAt", n.getCreatedAt());
            return m;
        }).collect(Collectors.toList()));
        return result;
    }

    private Note.NoteType parseNoteType(String type) {
        if (type == null) return Note.NoteType.HUMAN;
        try {
            return Note.NoteType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Note.NoteType.HUMAN;
        }
    }

    private Map<String, Object> errorMap(String message) {
        return Map.of("status", "error", "error", message);
    }
}
