/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.services;

import ai.kompile.app.facts.domain.Note;
import ai.kompile.app.facts.repository.NoteRepository;
import ai.kompile.app.sync.domain.NoteSyncRecord;
import ai.kompile.app.sync.repository.NoteSyncRecordRepository;
import ai.kompile.app.sync.service.NoteSyncProgressTracker;
import ai.kompile.app.sync.service.NoteSyncPulledEvent;
import ai.kompile.core.crawl.graph.UnifiedCrawlSource;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Converts provider-backed fact-sheet notes into a stable Markdown snapshot and starts the same
 * incremental graph/vector pipeline used by crawl jobs. The stable path lets crawl content hashes
 * skip unchanged snapshots while every started update remains visible in the crawl UI.
 */
@Service
public class SourceSyncGraphMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(SourceSyncGraphMaintenanceService.class);

    private final NoteRepository noteRepository;
    private final NoteSyncRecordRepository syncRecordRepository;
    private final NoteSyncProgressTracker progressTracker;
    private final SingleSourceCrawlStarter crawlStarter;

    public SourceSyncGraphMaintenanceService(
            NoteRepository noteRepository,
            NoteSyncRecordRepository syncRecordRepository,
            NoteSyncProgressTracker progressTracker,
            SingleSourceCrawlStarter crawlStarter) {
        this.noteRepository = noteRepository;
        this.syncRecordRepository = syncRecordRepository;
        this.progressTracker = progressTracker;
        this.crawlStarter = crawlStarter;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void updateGraph(NoteSyncPulledEvent event) {
        if (event.pulledCount() <= 0 && event.deletedCount() <= 0) {
            return;
        }
        if (!crawlStarter.isAvailable()) {
            progressTracker.graphError(event.sessionId(), "Graph crawl service is unavailable");
            return;
        }
        try {
            Set<Long> activeNoteIds = syncRecordRepository.findByConnectionId(event.connectionId())
                    .stream()
                    .filter(record -> !"EXTERNAL_DELETED".equals(record.getStatus()))
                    .map(NoteSyncRecord::getNoteId)
                    .collect(Collectors.toSet());
            List<Note> notes = noteRepository.findByFactSheetIdOrderByCreatedAtDesc(event.factSheetId())
                    .stream()
                    .filter(note -> activeNoteIds.contains(note.getId()))
                    .toList();

            Path snapshot = snapshotPath(event);
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, renderMarkdown(notes), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            UnifiedCrawlSource source = UnifiedCrawlSource.builder()
                    .label(event.provider() + " synced notes (connection " + event.connectionId() + ")")
                    .sourceType(DocumentSourceDescriptor.SourceType.FILE)
                    .pathOrUrl(snapshot.toString())
                    .loaderName("markdown")
                    .build();
            SingleSourceCrawlStarter.SingleSourceCrawlOptions options =
                    SingleSourceCrawlStarter.SingleSourceCrawlOptions.builder()
                            .factSheetId(event.factSheetId())
                            .build();
            SingleSourceCrawlStarter.SingleSourceCrawlResult job = crawlStarter.start(
                    "Source sync graph update: " + event.provider(), source, options);
            progressTracker.graphQueued(event.sessionId(), job.jobId());
            log.info("Started graph maintenance crawl {} after sync {} ({} pulled, {} deleted)",
                    job.jobId(), event.sessionId(), event.pulledCount(), event.deletedCount());
        } catch (Exception e) {
            progressTracker.graphError(event.sessionId(), e.getMessage());
            log.error("Could not start graph maintenance after source sync {}: {}",
                    event.sessionId(), e.getMessage(), e);
        }
    }

    private Path snapshotPath(NoteSyncPulledEvent event) {
        return Path.of(System.getProperty("java.io.tmpdir"), "kompile", "source-sync-graph",
                "fact-sheet-" + event.factSheetId(),
                "connection-" + event.connectionId() + ".md");
    }

    private String renderMarkdown(List<Note> notes) {
        StringBuilder markdown = new StringBuilder("# Synced source notes\n\n");
        for (Note note : notes) {
            markdown.append("## ").append(note.getTitle() != null ? note.getTitle() : "Untitled").append("\n\n");
            if (note.getContent() != null) {
                markdown.append(note.getContent());
            }
            markdown.append("\n\n---\n\n");
        }
        return markdown.toString();
    }
}
