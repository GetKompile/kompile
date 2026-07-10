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

import ai.kompile.app.facts.domain.Note;
import ai.kompile.app.facts.repository.NoteRepository;
import ai.kompile.core.embeddings.EmbeddingModel;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Embeds notes using the same EmbeddingModel pipeline as source documents.
 *
 * This ensures hybrid search (Lucene BM25 + dense vector) crosses both
 * source documents and user notes uniformly — the primary requirement from
 * the plan ("anything text-based produced by the system must flow
 * through the existing embedding pipeline").
 *
 * The EmbeddingModel bean is injected with required=false because the model
 * may not be initialised in all deployment configurations (e.g., tests, no-op
 * mode).  When the model is absent, notes are saved but flagged as not embedded,
 * and embedding can be retried later via processAllPendingNotes().
 *
 * The embedding vector is stored as a comma-separated float string in Note.embeddingData.
 */
@Service
public class NoteEmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(NoteEmbeddingService.class);
    private static final int MAX_NOTE_EMBEDDING_RETRIES = 3;
    private static final long INITIAL_RETRY_BACKOFF_MS = 250L;
    private static final double MIN_EMBEDDING_MAGNITUDE = 1e-18;

    private NoteRepository noteRepository;

    private static float[] toHostFloatVector(INDArray array) {
        if (array == null || array.isEmpty()) {
            return new float[0];
        }
        INDArray source = array;
        INDArray row = null;
        INDArray copy = null;
        try {
            if (array.isMatrix()) {
                row = array.getRow(0);
                source = row;
            }
            long length = source.length();
            if (length > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("INDArray too large to materialize as float[]: " + length);
            }
            copy = source.dup('c');
            return copy.data().getFloatsAt(copy.offset(), (int) length);
        } finally {
            if (copy != null && !copy.wasClosed()) {
                copy.close();
            }
            if (row != null && !row.wasClosed()) {
                row.close();
            }
        }
    }

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    @Autowired
    public NoteEmbeddingService(NoteRepository noteRepository) {
        this.noteRepository = noteRepository;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected NoteEmbeddingService() {}


    /**
     * Schedule embedding for a single note (async, non-blocking).
     * Called immediately after note creation/content update.
     */
    @Async
    public void scheduleEmbedding(Long noteId) {
        try {
            embedNote(noteId);
        } catch (Exception e) {
            logger.warn("Async embedding failed for note {}: {} — note will be retried during next processAllPendingNotes()",
                    noteId, e.getMessage());
        }
    }

    /**
     * Embed a specific note synchronously.
     * Returns true if embedding succeeded, false if the model is unavailable or an error occurs.
     */
    @Transactional
    public boolean embedNote(Long noteId) {
        Optional<Note> optNote = noteRepository.findById(noteId);
        if (optNote.isEmpty()) {
            logger.warn("Note {} not found for embedding", noteId);
            return false;
        }

        Note note = optNote.get();

        if (Boolean.TRUE.equals(note.getEmbedded())) {
            logger.debug("Note {} already embedded — skipping", noteId);
            return true;
        }

        if (embeddingModel == null) {
            logger.debug("No EmbeddingModel available — skipping embedding for note {}", noteId);
            return false;
        }

        try {
            String textToEmbed = buildEmbeddingText(note);

            if (Thread.currentThread().isInterrupted()) {
                logger.warn("Thread interrupted — aborting embedding for note {}", noteId);
                return false;
            }

            String embeddingData = embedNoteDataWithRetry(textToEmbed, noteId);

            note.setEmbeddingData(embeddingData);
            note.markEmbedded();
            noteRepository.save(note);

            logger.debug("Embedded note {} ('{}') — vector dim={}", noteId, note.getTitle(),
                    embeddingModel.dimensions());
            return true;

        } catch (Exception e) {
            logger.error("Failed to embed note {}: {}", noteId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Process all notes that have not yet been embedded.
     * Can be called on startup or via a scheduled task.
     *
     * @return number of notes successfully embedded
     */
    @Transactional
    public int processAllPendingNotes() {
        List<Note> pending = noteRepository.findByEmbeddedFalseOrderByCreatedAtAsc();
        if (pending.isEmpty()) {
            return 0;
        }

        logger.info("Processing {} pending note embeddings", pending.size());
        int success = 0;

        for (Note note : pending) {
            if (Thread.currentThread().isInterrupted()) {
                logger.warn("Thread interrupted — stopping note embedding batch at {}/{}", success, pending.size());
                break;
            }
            if (embedNote(note.getId())) {
                success++;
            }
        }

        logger.info("Embedded {}/{} pending notes", success, pending.size());
        return success;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private String buildEmbeddingText(Note note) {
        if (note.getTitle() != null && !note.getTitle().isBlank()) {
            return note.getTitle() + "\n\n" + note.getContent();
        }
        return note.getContent();
    }

    private String embedNoteDataWithRetry(String textToEmbed, Long noteId) throws InterruptedException {
        int attempt = 0;
        while (true) {
            INDArray vector = null;
            try {
                vector = embeddingModel.embed(textToEmbed);
                float[] values = toHostFloatVector(vector);
                validateVectorValues(values, "note " + noteId);
                return serializeVector(values);
            } catch (Exception e) {
                if (e instanceof InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
                attempt++;
                if (attempt >= MAX_NOTE_EMBEDDING_RETRIES) {
                    throw new IllegalStateException("Embedding failed for note " + noteId
                            + " after " + attempt + " attempt(s): " + e.getMessage(), e);
                }
                long backoffMs = retryBackoffMs(attempt);
                logger.warn("Embedding failed for note {} (attempt {}/{}): {}. Retrying after {}ms",
                        noteId, attempt, MAX_NOTE_EMBEDDING_RETRIES, e.getMessage(), backoffMs);
                Thread.sleep(backoffMs);
            } finally {
                closeVector(vector, noteId);
            }
        }
    }

    private void validateVectorValues(float[] values, String label) {
        if (values == null || values.length == 0) {
            throw new IllegalStateException(label + " produced no vector");
        }
        double magnitudeSquared = 0.0;
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException(label + " produced a non-finite vector value");
            }
            magnitudeSquared += (double) value * value;
        }
        if (magnitudeSquared <= MIN_EMBEDDING_MAGNITUDE) {
            throw new IllegalStateException(label + " produced a near-zero vector");
        }
    }

    private long retryBackoffMs(int attempt) {
        return Math.min(5_000L, INITIAL_RETRY_BACKOFF_MS << Math.max(0, attempt - 1));
    }

    private void closeVector(INDArray vector, Long noteId) {
        if (vector != null && !vector.wasClosed()) {
            try {
                vector.close();
            } catch (Exception e) {
                logger.warn("Failed to close embedding vector for note {}: {}", noteId, e.getMessage());
            }
        }
    }

    private String serializeVector(INDArray vector) {
        float[] values = toHostFloatVector(vector);
        validateVectorValues(values, "embedding vector");
        return serializeVector(values);
    }

    private String serializeVector(float[] values) {
        StringJoiner sj = new StringJoiner(",");
        for (float value : values) {
            sj.add(Float.toString(value));
        }
        return sj.toString();
    }

    /**
     * Deserialize a comma-separated float string back to a float array.
     * Exposed for potential use by search/retrieval components.
     */
    public static float[] deserializeVector(String embeddingData) {
        if (embeddingData == null || embeddingData.isBlank()) {
            return new float[0];
        }
        String[] parts = embeddingData.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }
}
