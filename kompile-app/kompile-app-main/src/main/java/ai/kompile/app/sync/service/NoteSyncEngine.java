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

package ai.kompile.app.sync.service;

import ai.kompile.app.facts.domain.Note;
import ai.kompile.app.facts.repository.NoteRepository;
import ai.kompile.app.facts.service.NoteService;
import ai.kompile.app.sync.adapter.SyncAdapter;
import ai.kompile.app.sync.adapter.SyncAdapter.ExternalNoteSnapshot;
import ai.kompile.app.sync.domain.NoteSyncConnection;
import ai.kompile.app.sync.domain.NoteSyncRecord;
import ai.kompile.app.sync.domain.SyncDirection;
import ai.kompile.app.sync.domain.SyncProvider;
import ai.kompile.app.sync.dto.SyncRunResult;
import ai.kompile.app.sync.repository.NoteSyncConnectionRepository;
import ai.kompile.app.sync.repository.NoteSyncRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Core sync orchestration engine. Handles pull (external -> Kompile) and push
 * (Kompile -> external) phases with per-note conflict detection via timestamps
 * and content checksums.
 */
@Service
public class NoteSyncEngine {

    private static final Logger log = LoggerFactory.getLogger(NoteSyncEngine.class);

    @Autowired
    private NoteRepository noteRepository;

    @Autowired
    private NoteService noteService;

    @Autowired
    private NoteSyncRecordRepository syncRecordRepository;

    @Autowired
    private NoteSyncConnectionRepository connectionRepository;

    @Autowired(required = false)
    private List<SyncAdapter> adapters;

    @Autowired
    private NoteSyncProgressTracker progressTracker;

    /**
     * Run a full sync cycle for a given connection.
     */
    public SyncRunResult syncConnection(Long connectionId) {
        NoteSyncConnection conn = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + connectionId));
        if (!conn.getEnabled()) {
            return SyncRunResult.skipped(connectionId);
        }

        SyncAdapter adapter = resolveAdapter(conn.getProvider());
        String syncSessionId = "sync-" + connectionId + "-" + System.currentTimeMillis();
        progressTracker.start(syncSessionId, conn);

        try {
            SyncRunResult result = doSync(conn, adapter, syncSessionId);
            conn.setLastSyncAt(Instant.now());
            if (result.getErrors() > 0) {
                conn.setLastSyncStatus("ERROR");
                conn.setLastSyncError("Sync completed with " + result.getErrors() + " error(s). Check sync records and auth status for details.");
            } else if (result.getConflicts() > 0) {
                conn.setLastSyncStatus("CONFLICT");
                conn.setLastSyncError("Sync completed with " + result.getConflicts() + " conflict(s).");
            } else {
                conn.setLastSyncStatus("OK");
                conn.setLastSyncError(null);
            }
            connectionRepository.save(conn);
            progressTracker.complete(syncSessionId, result);
            return result;
        } catch (Exception e) {
            log.error("Sync failed for connection {}: {}", connectionId, e.getMessage(), e);
            conn.setLastSyncStatus("ERROR");
            conn.setLastSyncError(e.getMessage());
            connectionRepository.save(conn);
            progressTracker.error(syncSessionId, connectionId, e.getMessage());
            throw new RuntimeException("Sync failed: " + e.getMessage(), e);
        }
    }

    private SyncRunResult doSync(NoteSyncConnection conn, SyncAdapter adapter, String sessionId) {
        SyncRunResult.SyncRunResultBuilder result = SyncRunResult.builder().connectionId(conn.getId());
        Instant since = conn.getLastSyncAt() != null ? conn.getLastSyncAt() : Instant.EPOCH;

        int pulled = 0, pushed = 0, conflicts = 0, skipped = 0, errors = 0;

        // --- PULL: external -> Kompile ---
        if (conn.getDirection() != SyncDirection.KOMPILE_TO_EXTERNAL) {
            try {
                List<ExternalNoteSnapshot> changed = adapter.fetchChangedSince(conn, since);
                for (ExternalNoteSnapshot snap : changed) {
                    progressTracker.progress(sessionId, conn.getId(), "Pulling: " + snap.title());
                    PullResult pr = pullExternalChange(conn, snap);
                    switch (pr) {
                        case CREATED, UPDATED -> pulled++;
                        case CONFLICT -> conflicts++;
                        case SKIPPED -> skipped++;
                    }
                }
            } catch (Exception e) {
                log.error("Pull phase failed for connection {}: {}", conn.getId(), e.getMessage(), e);
                errors++;
            }
        }

        // --- PUSH: Kompile -> external ---
        if (conn.getDirection() != SyncDirection.EXTERNAL_TO_KOMPILE) {
            try {
                List<Note> modifiedNotes = noteRepository.findByFactSheetIdAndUpdatedAtAfter(
                        conn.getFactSheetId(), since);
                for (Note note : modifiedNotes) {
                    progressTracker.progress(sessionId, conn.getId(), "Pushing: " + note.getTitle());
                    PushResult pr = pushNoteChange(conn, note, adapter);
                    switch (pr) {
                        case CREATED, UPDATED -> pushed++;
                        case SKIPPED -> skipped++;
                        case ERROR -> errors++;
                    }
                }
            } catch (Exception e) {
                log.error("Push phase failed for connection {}: {}", conn.getId(), e.getMessage(), e);
                errors++;
            }
        }

        return result.pulled(pulled).pushed(pushed).conflicts(conflicts)
                .skipped(skipped).errors(errors).build();
    }

    @Transactional
    protected PullResult pullExternalChange(NoteSyncConnection conn, ExternalNoteSnapshot snap) {
        Optional<NoteSyncRecord> existingRecord = syncRecordRepository
                .findByExternalIdAndConnectionId(snap.externalId(), conn.getId());

        if (existingRecord.isEmpty()) {
            // New external item -> create Note in Kompile
            Note note = noteService.createNote(
                    conn.getFactSheetId(), snap.title(), snap.markdownContent(),
                    Note.NoteType.HUMAN, null, snap.tags());
            note.setExternalId(snap.externalId());
            note.setSyncProvider(conn.getProvider());
            note.setExternalUpdatedAt(snap.externalUpdatedAt());
            noteRepository.save(note);

            NoteSyncRecord record = NoteSyncRecord.builder()
                    .noteId(note.getId()).connectionId(conn.getId())
                    .externalId(snap.externalId())
                    .kompileUpdatedAt(note.getUpdatedAt())
                    .externalUpdatedAt(snap.externalUpdatedAt())
                    .contentChecksum(sha256(snap.markdownContent()))
                    .status("SYNCED").lastSyncAt(Instant.now()).build();
            syncRecordRepository.save(record);
            return PullResult.CREATED;
        }

        NoteSyncRecord record = existingRecord.get();
        Note note = noteRepository.findById(record.getNoteId()).orElse(null);
        if (note == null) {
            return PullResult.SKIPPED;
        }

        boolean externalChanged = !snap.externalUpdatedAt().equals(record.getExternalUpdatedAt());
        boolean kompileChanged = note.getUpdatedAt().isAfter(
                record.getKompileUpdatedAt() != null ? record.getKompileUpdatedAt() : Instant.EPOCH);

        if (externalChanged && kompileChanged) {
            // CONFLICT -- create a conflict note with both versions
            String conflictContent = "# Sync Conflict\n\n" +
                    "Both Kompile and the external source were modified since the last sync.\n\n" +
                    "## External Version (" + conn.getProvider() + ")\n\n" +
                    snap.markdownContent() + "\n\n---\n\n" +
                    "## Kompile Version\n\n" + note.getContent();
            noteService.createNote(
                    conn.getFactSheetId(), "[CONFLICT] " + snap.title(), conflictContent,
                    Note.NoteType.AI, null, "conflict,sync");
            record.setStatus("CONFLICT");
            syncRecordRepository.save(record);
            return PullResult.CONFLICT;

        } else if (externalChanged) {
            // External wins -- update Kompile note
            noteService.updateNote(note.getId(), snap.title(), snap.markdownContent(), note.getTags());
            note.setExternalUpdatedAt(snap.externalUpdatedAt());
            noteRepository.save(note);
            record.setExternalUpdatedAt(snap.externalUpdatedAt());
            record.setKompileUpdatedAt(Instant.now());
            record.setContentChecksum(sha256(snap.markdownContent()));
            record.setStatus("SYNCED");
            record.setLastSyncAt(Instant.now());
            syncRecordRepository.save(record);
            return PullResult.UPDATED;
        }

        return PullResult.SKIPPED;
    }

    @Transactional
    protected PushResult pushNoteChange(NoteSyncConnection conn, Note note, SyncAdapter adapter) {
        Optional<NoteSyncRecord> existingRecord = syncRecordRepository
                .findByNoteIdAndConnectionId(note.getId(), conn.getId());

        String currentChecksum = sha256(note.getContent());

        if (existingRecord.isEmpty()) {
            // First push for this note
            try {
                String externalId = adapter.createExternal(conn, note, note.getContent());
                note.setExternalId(externalId);
                note.setSyncProvider(conn.getProvider());
                noteRepository.save(note);

                NoteSyncRecord record = NoteSyncRecord.builder()
                        .noteId(note.getId()).connectionId(conn.getId())
                        .externalId(externalId)
                        .kompileUpdatedAt(note.getUpdatedAt())
                        .externalUpdatedAt(Instant.now())
                        .contentChecksum(currentChecksum)
                        .status("SYNCED").lastSyncAt(Instant.now()).build();
                syncRecordRepository.save(record);
                return PushResult.CREATED;
            } catch (Exception e) {
                log.error("Failed to create external note '{}': {}", note.getTitle(), e.getMessage());
                return PushResult.ERROR;
            }
        }

        NoteSyncRecord record = existingRecord.get();
        if (currentChecksum.equals(record.getContentChecksum())) {
            return PushResult.SKIPPED;
        }

        try {
            adapter.updateExternal(conn, record.getExternalId(), note, note.getContent());
            record.setKompileUpdatedAt(note.getUpdatedAt());
            record.setExternalUpdatedAt(Instant.now());
            record.setContentChecksum(currentChecksum);
            record.setStatus("SYNCED");
            record.setLastSyncAt(Instant.now());
            syncRecordRepository.save(record);
            return PushResult.UPDATED;
        } catch (Exception e) {
            log.error("Failed to update external note '{}': {}", note.getTitle(), e.getMessage());
            record.setStatus("ERROR");
            record.setErrorMessage(e.getMessage());
            syncRecordRepository.save(record);
            return PushResult.ERROR;
        }
    }

    private SyncAdapter resolveAdapter(SyncProvider provider) {
        if (adapters == null || adapters.isEmpty()) {
            throw new IllegalStateException("No sync adapters are configured. Enable kompile.sync."
                    + provider.name().toLowerCase() + ".enabled=true");
        }
        return adapters.stream()
                .filter(a -> a.adapterId().equalsIgnoreCase(provider.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No adapter for " + provider));
    }

    static String sha256(String content) {
        if (content == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private enum PullResult { CREATED, UPDATED, CONFLICT, SKIPPED }
    private enum PushResult { CREATED, UPDATED, SKIPPED, ERROR }
}
