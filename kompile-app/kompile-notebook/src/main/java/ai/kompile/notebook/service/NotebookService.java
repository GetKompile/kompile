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

import ai.kompile.notebook.domain.ChatSessionContext;
import ai.kompile.notebook.domain.Notebook;
import ai.kompile.notebook.domain.NotebookSource;
import ai.kompile.notebook.repository.ChatSessionContextRepository;
import ai.kompile.notebook.repository.NotebookRepository;
import ai.kompile.notebook.repository.NotebookSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing Notebooks and their source memberships.
 *
 * Design note: a Notebook is a LOGICAL grouping that references Facts stored via the
 * existing FactSheet/Fact infrastructure.  FactSheet = "physical index scope";
 * Notebook = "curated research workspace."  They are independent concepts.
 */
@Service
@Transactional
public class NotebookService {

    private static final Logger logger = LoggerFactory.getLogger(NotebookService.class);

    private final NotebookRepository notebookRepository;
    private final NotebookSourceRepository notebookSourceRepository;
    private final ChatSessionContextRepository chatSessionContextRepository;

    @Autowired
    public NotebookService(
            NotebookRepository notebookRepository,
            NotebookSourceRepository notebookSourceRepository,
            ChatSessionContextRepository chatSessionContextRepository) {
        this.notebookRepository = notebookRepository;
        this.notebookSourceRepository = notebookSourceRepository;
        this.chatSessionContextRepository = chatSessionContextRepository;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // NOTEBOOK CRUD
    // ──────────────────────────────────────────────────────────────────────────

    /** Return all non-archived notebooks, newest first. */
    @Transactional(readOnly = true)
    public List<Notebook> listNotebooks() {
        return notebookRepository.findByArchivedFalseOrderByUpdatedAtDesc();
    }

    /** Return all notebooks including archived. */
    @Transactional(readOnly = true)
    public List<Notebook> listAllNotebooks() {
        return notebookRepository.findAllByOrderByUpdatedAtDesc();
    }

    @Transactional(readOnly = true)
    public Optional<Notebook> getNotebook(Long id) {
        return notebookRepository.findById(id);
    }

    public Notebook createNotebook(String name, String description, String icon, String color) {
        Notebook notebook = Notebook.builder()
                .name(name)
                .description(description)
                .icon(icon != null ? icon : "book")
                .color(color != null ? color : "#1976d2")
                .archived(false)
                .build();
        Notebook saved = notebookRepository.save(notebook);
        logger.info("Created notebook '{}' (id={})", saved.getName(), saved.getId());
        return saved;
    }

    public Notebook updateNotebook(Long id, String name, String description, String icon, String color) {
        Notebook notebook = notebookRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Notebook not found: " + id));
        if (name != null) notebook.setName(name);
        if (description != null) notebook.setDescription(description);
        if (icon != null) notebook.setIcon(icon);
        if (color != null) notebook.setColor(color);
        Notebook saved = notebookRepository.save(notebook);
        logger.info("Updated notebook '{}' (id={})", saved.getName(), saved.getId());
        return saved;
    }

    public Notebook archiveNotebook(Long id) {
        Notebook notebook = notebookRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Notebook not found: " + id));
        notebook.setArchived(true);
        Notebook saved = notebookRepository.save(notebook);
        logger.info("Archived notebook '{}' (id={})", saved.getName(), saved.getId());
        return saved;
    }

    public Notebook unarchiveNotebook(Long id) {
        Notebook notebook = notebookRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Notebook not found: " + id));
        notebook.setArchived(false);
        return notebookRepository.save(notebook);
    }

    public void deleteNotebook(Long id) {
        Notebook notebook = notebookRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Notebook not found: " + id));
        logger.info("Deleting notebook '{}' (id={})", notebook.getName(), notebook.getId());
        notebookRepository.delete(notebook);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SOURCE MANAGEMENT
    // ──────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<NotebookSource> getSourcesForNotebook(Long notebookId) {
        return notebookSourceRepository.findByNotebookIdOrderByAddedAtDesc(notebookId);
    }

    /**
     * Add a Fact (by factId) to a notebook.
     *
     * @param notebookId    target notebook
     * @param factId        Fact.id from the facts table
     * @param displayName   human-readable label (usually Fact.fileName or Fact.title)
     * @return the created NotebookSource, or the existing one if already present
     */
    public NotebookSource addSource(Long notebookId, Long factId, String displayName) {
        Notebook notebook = notebookRepository.findById(notebookId)
                .orElseThrow(() -> new IllegalArgumentException("Notebook not found: " + notebookId));

        Optional<NotebookSource> existing = notebookSourceRepository.findByNotebookIdAndFactId(notebookId, factId);
        if (existing.isPresent()) {
            logger.debug("Fact {} already in notebook {} — skipping", factId, notebookId);
            return existing.get();
        }

        NotebookSource ns = NotebookSource.builder()
                .notebook(notebook)
                .factId(factId)
                .displayName(displayName)
                .build();
        NotebookSource saved = notebookSourceRepository.save(ns);
        logger.info("Added fact {} ('{}') to notebook {} ('{}')", factId, displayName, notebookId, notebook.getName());
        return saved;
    }

    public void removeSource(Long notebookId, Long factId) {
        if (!notebookRepository.existsById(notebookId)) {
            throw new IllegalArgumentException("Notebook not found: " + notebookId);
        }
        notebookSourceRepository.deleteByNotebookIdAndFactId(notebookId, factId);
        logger.info("Removed fact {} from notebook {}", factId, notebookId);
    }

    @Transactional(readOnly = true)
    public boolean isSourceInNotebook(Long notebookId, Long factId) {
        return notebookSourceRepository.existsByNotebookIdAndFactId(notebookId, factId);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CHAT SESSION CONTEXT (per-source visibility control)
    // ──────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ChatSessionContext> getSessionContexts(String sessionId) {
        return chatSessionContextRepository.findBySessionId(sessionId);
    }

    /**
     * Set the mode for a specific source within a chat session.
     * Creates or updates the context row.
     */
    public ChatSessionContext setSourceMode(String sessionId, Long notebookId,
                                            Long factId, String displayName,
                                            ChatSessionContext.SourceMode mode) {
        Optional<ChatSessionContext> existing = chatSessionContextRepository.findBySessionIdAndFactId(sessionId, factId);
        ChatSessionContext ctx;
        if (existing.isPresent()) {
            ctx = existing.get();
            ctx.setMode(mode);
            ctx.setUpdatedAt(Instant.now());
        } else {
            ctx = ChatSessionContext.builder()
                    .sessionId(sessionId)
                    .notebookId(notebookId)
                    .factId(factId)
                    .sourceDisplayName(displayName)
                    .mode(mode)
                    .build();
        }
        ChatSessionContext saved = chatSessionContextRepository.save(ctx);
        logger.debug("Set source {} mode={} in session {}", factId, mode, sessionId);
        return saved;
    }

    /**
     * Return all factIds that are EXCLUDED from the given session.
     * Useful for filtering during retrieval.
     */
    @Transactional(readOnly = true)
    public List<Long> getExcludedFactIds(String sessionId) {
        return chatSessionContextRepository
                .findBySessionIdAndMode(sessionId, ChatSessionContext.SourceMode.EXCLUDED)
                .stream()
                .map(ChatSessionContext::getFactId)
                .toList();
    }
}
