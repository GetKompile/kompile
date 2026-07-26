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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ai.kompile.utils.HashUtils;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Core sync orchestration engine. Handles pull (external -> Kompile) and push
 * (Kompile -> external) phases with per-note conflict detection via timestamps
 * and content checksums.
 */
@Service
public class NoteSyncEngine {

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected NoteSyncEngine() {}


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

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    /**
     * Run a full sync cycle for a given connection.
     */
    @Transactional
    public SyncRunResult syncConnection(Long connectionId) {
        return syncConnection(connectionId, "sync-" + java.util.UUID.randomUUID(), false);
    }

    /** Pull external changes without pushing local edits back to the provider. */
    @Transactional
    public SyncRunResult pullConnection(Long connectionId) {
        return syncConnection(connectionId, "pull-" + java.util.UUID.randomUUID(), true);
    }

    /**
     * Executes a previously accepted durable run. checkpointCandidate is captured before
     * reading the provider and is committed only when every requested phase succeeds.
     */
    @Transactional
    public SyncRunResult syncConnection(Long connectionId, String syncSessionId, boolean pullOnly) {
        NoteSyncConnection conn = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + connectionId));
        if (!Boolean.TRUE.equals(conn.getEnabled())) {
            SyncRunResult skipped = SyncRunResult.skipped(connectionId);
            progressTracker.complete(syncSessionId, skipped, conn.getLastSyncAt());
            return skipped;
        }

        Instant checkpointBefore = conn.getLastSyncAt();
        Instant checkpointCandidate = Instant.now();

        try {
            SyncAdapter adapter = resolveAdapter(conn.getProvider());
            progressTracker.start(syncSessionId, conn);
            SyncRunResult result = doSync(conn, adapter, syncSessionId, pullOnly);
            if (result.getErrors() > 0) {
                conn.setLastSyncStatus("ERROR");
                conn.setLastSyncError("Sync completed with " + result.getErrors()
                        + " error(s); the prior checkpoint was retained for a safe retry.");
                applyFailureBackoff(conn);
            } else {
                conn.setLastSyncAt(checkpointCandidate);
                conn.setConsecutiveSyncFailures(0);
                conn.setNextSyncAttemptAt(null);
                if (result.getConflicts() > 0) {
                    conn.setLastSyncStatus("CONFLICT");
                    conn.setLastSyncError("Sync completed with " + result.getConflicts() + " conflict(s).");
                } else {
                    conn.setLastSyncStatus("OK");
                    conn.setLastSyncError(null);
                }
            }
            connectionRepository.save(conn);
            progressTracker.complete(syncSessionId, result, conn.getLastSyncAt());
            if ((result.getPulled() > 0 || result.getDeleted() > 0) && eventPublisher != null) {
                progressTracker.graphPending(syncSessionId);
                eventPublisher.publishEvent(new NoteSyncPulledEvent(
                        syncSessionId, conn.getId(), conn.getFactSheetId(), conn.getProvider(),
                        result.getPulled(), result.getDeleted()));
            }
            return result;
        } catch (NoteSyncConnectionService.SyncLeaseLostException leaseLost) {
            log.warn("Stopping stale source sync worker {}: {}",
                    syncSessionId, leaseLost.getMessage());
            throw leaseLost;
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("Sync failed for connection {}: {}", connectionId, message, e);
            conn.setLastSyncAt(checkpointBefore);
            conn.setLastSyncStatus("ERROR");
            conn.setLastSyncError(message);
            applyFailureBackoff(conn);
            connectionRepository.save(conn);
            progressTracker.error(syncSessionId, connectionId, message);
            return SyncRunResult.builder()
                    .connectionId(connectionId)
                    .errors(1)
                    .build();
        }
    }

    private void applyFailureBackoff(NoteSyncConnection conn) {
        int failures = Math.max(0, conn.getConsecutiveSyncFailures()) + 1;
        int exponent = Math.min(failures - 1, 7);
        long delaySeconds = Math.min(3600L, 30L * (1L << exponent));
        conn.setConsecutiveSyncFailures(failures);
        conn.setNextSyncAttemptAt(Instant.now().plusSeconds(delaySeconds));
    }

    private SyncRunResult doSync(NoteSyncConnection conn, SyncAdapter adapter, String sessionId, boolean pullOnly) {
        SyncRunResult.SyncRunResultBuilder result = SyncRunResult.builder().connectionId(conn.getId());
        Instant since = conn.getLastSyncAt() != null ? conn.getLastSyncAt() : Instant.EPOCH;

        int pulled = 0, pushed = 0, deleted = 0, conflicts = 0, skipped = 0, errors = 0;
        boolean pullSucceeded = conn.getDirection() == SyncDirection.KOMPILE_TO_EXTERNAL;

        // --- PULL: external -> Kompile ---
        if (conn.getDirection() != SyncDirection.KOMPILE_TO_EXTERNAL) {
            try {
                progressTracker.progress(
                        sessionId, conn.getId(), "PULL", "Checking the source for updates");
                List<ExternalNoteSnapshot> changed = adapter.fetchChangedSince(conn, since);
                for (ExternalNoteSnapshot snap : changed) {
                    progressTracker.progress(
                            sessionId, conn.getId(), "PULL", "Pulling: " + snap.title());
                    PullResult pr = pullExternalChange(conn, snap);
                    switch (pr) {
                        case CREATED, UPDATED -> pulled++;
                        case CONFLICT -> conflicts++;
                        case SKIPPED -> skipped++;
                    }
                }

                Optional<java.util.Set<String>> externalIds = adapter.listExternalIds(conn);
                if (externalIds.isPresent()) {
                    progressTracker.progress(
                            sessionId, conn.getId(), "RECONCILE", "Reconciling source deletions");
                    deleted += reconcileExternalDeletions(conn, externalIds.get());
                }
                pullSucceeded = true;
            } catch (NoteSyncConnectionService.SyncLeaseLostException leaseLost) {
                throw leaseLost;
            } catch (Exception e) {
                log.error("Pull phase failed for connection {}: {}", conn.getId(), e.getMessage(), e);
                errors++;
            }
        }

        // --- PUSH: Kompile -> external ---
        boolean pushRequested = !pullOnly
                && conn.getDirection() != SyncDirection.EXTERNAL_TO_KOMPILE;
        if (pushRequested && pullSucceeded) {
            try {
                List<Note> modifiedNotes = noteRepository.findByFactSheetIdAndUpdatedAtAfter(
                        conn.getFactSheetId(), since);
                for (Note note : modifiedNotes) {
                    progressTracker.progress(
                            sessionId, conn.getId(), "PUSH", "Pushing: " + note.getTitle());
                    PushResult pr = pushNoteChange(conn, note, adapter);
                    switch (pr) {
                        case CREATED, UPDATED -> pushed++;
                        case SKIPPED -> skipped++;
                        case ERROR -> errors++;
                    }
                }
            } catch (NoteSyncConnectionService.SyncLeaseLostException leaseLost) {
                throw leaseLost;
            } catch (Exception e) {
                log.error("Push phase failed for connection {}: {}", conn.getId(), e.getMessage(), e);
                errors++;
            }
        } else if (pushRequested) {
            progressTracker.progress(
                    sessionId, conn.getId(), "PUSH_SKIPPED",
                    "Push skipped because the source pull did not complete safely");
        }

        return result.pulled(pulled).pushed(pushed).deleted(deleted).conflicts(conflicts)
                .skipped(skipped).errors(errors).build();
    }

    private int reconcileExternalDeletions(NoteSyncConnection conn, java.util.Set<String> externalIds) {
        int deleted = 0;
        for (NoteSyncRecord record : syncRecordRepository.findByConnectionId(conn.getId())) {
            String externalId = record.getExternalId();
            String status = record.getStatus();
            if (externalId == null || "EXTERNAL_DELETED".equals(status)) {
                continue;
            }

            if (externalIds.contains(externalId)) {
                if ("EXTERNAL_MISSING".equals(status)) {
                    record.setStatus("SYNCED");
                    record.setErrorMessage(null);
                    record.setLastSyncAt(Instant.now());
                    syncRecordRepository.save(record);
                }
                continue;
            }

            // Conflict and error states require explicit resolution; deletion reconciliation
            // must not silently replace or clear them.
            if (!"SYNCED".equals(status) && !"EXTERNAL_MISSING".equals(status)) {
                continue;
            }

            if ("EXTERNAL_MISSING".equals(status)) {
                record.setStatus("EXTERNAL_DELETED");
                record.setErrorMessage(
                        "The source item was deleted. The Kompile note is retained until this tombstone is resolved.");
                deleted++;
            } else {
                record.setStatus("EXTERNAL_MISSING");
                record.setErrorMessage(
                        "The source item was absent from one complete snapshot; "
                                + "deletion must be confirmed by the next successful sync.");
            }
            record.setLastSyncAt(Instant.now());
            syncRecordRepository.save(record);
        }
        return deleted;
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
                    .contentChecksum(noteChecksum(snap.title(), snap.tags(), snap.markdownContent()))
                    .status("SYNCED").lastSyncAt(Instant.now()).build();
            syncRecordRepository.save(record);
            return PullResult.CREATED;
        }

        NoteSyncRecord record = existingRecord.get();
        Note note = noteRepository.findById(record.getNoteId()).orElse(null);
        if (note == null) {
            return PullResult.SKIPPED;
        }

        String externalChecksum = noteChecksum(snap.title(), snap.tags(), snap.markdownContent());
        boolean externalChanged = "EXTERNAL_DELETED".equals(record.getStatus())
                || !externalChecksum.equals(record.getContentChecksum());
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
            noteService.updateNote(note.getId(), snap.title(), snap.markdownContent(), snap.tags());
            note.setExternalUpdatedAt(snap.externalUpdatedAt());
            noteRepository.save(note);
            record.setExternalUpdatedAt(snap.externalUpdatedAt());
            record.setKompileUpdatedAt(Instant.now());
            record.setContentChecksum(externalChecksum);
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

        String currentChecksum = noteChecksum(note.getTitle(), note.getTags(), note.getContent());

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
        if ("EXTERNAL_MISSING".equals(record.getStatus())
                || "EXTERNAL_DELETED".equals(record.getStatus())) {
            // Preserve the local note while an external deletion is provisional or confirmed.
            // Re-creating remote content requires an explicit resolution.
            return PushResult.SKIPPED;
        }
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

    static String noteChecksum(String title, String tags, String content) {
        String normalizedTags = tags == null ? "" : java.util.Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(java.util.stream.Collectors.joining(","));
        String canonical = checksumPart(title) + checksumPart(normalizedTags) + checksumPart(content);
        return sha256(canonical);
    }

    private static String checksumPart(String value) {
        if (value == null) {
            return "-1:";
        }
        String normalized = java.text.Normalizer.normalize(
                value.replace("\r\n", "\n").replace('\r', '\n'),
                java.text.Normalizer.Form.NFC);
        return normalized.length() + ":" + normalized;
    }

    static String sha256(String content) {
        if (content == null) return "";
        return HashUtils.sha256Hex(content);
    }

    private enum PullResult { CREATED, UPDATED, CONFLICT, SKIPPED }
    private enum PushResult { CREATED, UPDATED, SKIPPED, ERROR }
}
