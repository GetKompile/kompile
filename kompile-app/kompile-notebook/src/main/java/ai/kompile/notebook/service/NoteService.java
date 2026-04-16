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

package ai.kompile.notebook.service;

import ai.kompile.notebook.domain.Note;
import ai.kompile.notebook.domain.NoteSourceLink;
import ai.kompile.notebook.domain.Notebook;
import ai.kompile.notebook.repository.NoteRepository;
import ai.kompile.notebook.repository.NoteSourceLinkRepository;
import ai.kompile.notebook.repository.NotebookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Service for creating and managing Notes within a notebook.
 *
 * Notes go through the same embedding pipeline as source documents:
 * after creation, NoteEmbeddingService picks up un-embedded notes and
 * calls EmbeddingModel.embed(note.getContent()), then stores the vector
 * so hybrid search crosses sources and notes uniformly.
 */
@Service
@Transactional
public class NoteService {

    private static final Logger logger = LoggerFactory.getLogger(NoteService.class);

    private final NoteRepository noteRepository;
    private final NoteSourceLinkRepository noteSourceLinkRepository;
    private final NotebookRepository notebookRepository;
    private final NoteEmbeddingService noteEmbeddingService;

    @Autowired
    public NoteService(
            NoteRepository noteRepository,
            NoteSourceLinkRepository noteSourceLinkRepository,
            NotebookRepository notebookRepository,
            NoteEmbeddingService noteEmbeddingService) {
        this.noteRepository = noteRepository;
        this.noteSourceLinkRepository = noteSourceLinkRepository;
        this.notebookRepository = notebookRepository;
        this.noteEmbeddingService = noteEmbeddingService;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CRUD
    // ──────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Note> getNotesForNotebook(Long notebookId) {
        return noteRepository.findByNotebookIdOrderByCreatedAtDesc(notebookId);
    }

    @Transactional(readOnly = true)
    public Optional<Note> getNote(Long id) {
        return noteRepository.findById(id);
    }

    /**
     * Create a new note.  After persisting, triggers async embedding so the note
     * becomes searchable alongside source documents.
     *
     * @param notebookId     notebook the note belongs to
     * @param title          optional short heading
     * @param content        the note's text content (required)
     * @param noteType       HUMAN or AI
     * @param sourceOriginRef optional SourceMetadataConstants.SOURCE_ID of the originating chunk
     * @param tags           optional comma-separated tags
     * @return the persisted Note
     */
    public Note createNote(Long notebookId, String title, String content,
                           Note.NoteType noteType, String sourceOriginRef, String tags) {
        Notebook notebook = notebookRepository.findById(notebookId)
                .orElseThrow(() -> new IllegalArgumentException("Notebook not found: " + notebookId));

        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Note content must not be empty");
        }

        Note note = Note.builder()
                .notebook(notebook)
                .title(title)
                .content(content)
                .noteType(noteType != null ? noteType : Note.NoteType.HUMAN)
                .sourceOriginRef(sourceOriginRef)
                .tags(tags)
                .embedded(false)
                .build();

        Note saved = noteRepository.save(note);
        logger.info("Created note '{}' (id={}) in notebook {} ('{}')",
                saved.getTitle(), saved.getId(), notebookId, notebook.getName());

        // Trigger embedding asynchronously — does not block the caller
        noteEmbeddingService.scheduleEmbedding(saved.getId());

        return saved;
    }

    public Note updateNote(Long id, String title, String content, String tags) {
        Note note = noteRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Note not found: " + id));

        boolean contentChanged = content != null && !content.equals(note.getContent());

        if (title != null) note.setTitle(title);
        if (content != null) note.setContent(content);
        if (tags != null) note.setTags(tags);

        // If content changed, mark as needing re-embedding
        if (contentChanged) {
            note.setEmbedded(false);
            note.setEmbeddingData(null);
        }

        Note saved = noteRepository.save(note);

        if (contentChanged) {
            noteEmbeddingService.scheduleEmbedding(saved.getId());
        }

        return saved;
    }

    public void deleteNote(Long id) {
        Note note = noteRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Note not found: " + id));
        noteSourceLinkRepository.deleteByNoteId(id);
        noteRepository.delete(note);
        logger.info("Deleted note id={}", id);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SEARCH
    // ──────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Note> searchNotes(Long notebookId, String query) {
        if (query == null || query.isBlank()) {
            return noteRepository.findByNotebookIdOrderByCreatedAtDesc(notebookId);
        }
        return noteRepository.searchByText(notebookId, query);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SOURCE LINKING
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Link a note to a specific source chunk.
     * Stores the SourceMetadataConstants.SOURCE_ID so the UI can render a citation link.
     */
    public NoteSourceLink linkNoteToSource(Long noteId, String sourceId,
                                           Integer chunkIndex, Integer pageNumber,
                                           String passageSnippet) {
        Note note = noteRepository.findById(noteId)
                .orElseThrow(() -> new IllegalArgumentException("Note not found: " + noteId));

        NoteSourceLink link = NoteSourceLink.builder()
                .note(note)
                .sourceId(sourceId)
                .chunkIndex(chunkIndex)
                .pageNumber(pageNumber)
                .passageSnippet(passageSnippet)
                .build();

        return noteSourceLinkRepository.save(link);
    }

    @Transactional(readOnly = true)
    public List<NoteSourceLink> getSourceLinks(Long noteId) {
        return noteSourceLinkRepository.findByNoteId(noteId);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PROMOTE FROM CHAT
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Promote a chat response passage to a permanent Note.
     * Convenience wrapper used by the NotebookController's /api/notebook/{id}/notes/promote endpoint.
     */
    public Note promoteFromChat(Long notebookId, String content, String sourceOriginRef, String passageSnippet) {
        Note note = createNote(notebookId, null, content, Note.NoteType.AI, sourceOriginRef, null);
        if (sourceOriginRef != null && !sourceOriginRef.isBlank()) {
            linkNoteToSource(note.getId(), sourceOriginRef, null, null, passageSnippet);
        }
        return note;
    }
}
