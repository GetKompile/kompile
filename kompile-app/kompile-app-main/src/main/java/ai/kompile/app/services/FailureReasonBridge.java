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

package ai.kompile.app.services;

import ai.kompile.app.ingest.domain.IndexingJobHistory;
import ai.kompile.app.services.subprocess.SubprocessRestartManager;
import ai.kompile.app.web.dto.IngestProgressUpdate;
import org.springframework.stereotype.Service;

/**
 * Bridge service for converting between different FailureReason enum types
 * used across the application.
 *
 * This service provides a centralized place to handle the mapping between:
 * - IndexingJobHistory.FailureReason (database/persistence layer)
 * - IngestProgressUpdate.FailureReason (DTO/API layer)
 * - SubprocessRestartManager.FailureReason (subprocess management layer)
 * - String-based failure reasons from subprocess messages
 *
 * It ensures consistency and handles cases where enum values don't have
 * direct mappings by falling back to appropriate defaults.
 */
@Service
public class FailureReasonBridge {

    /**
     * Convert a string failure reason to IndexingJobHistory.FailureReason.
     * This is the primary method for converting subprocess error messages.
     *
     * @param reason the string reason (e.g., "MODEL_NOT_FOUND", "Out of memory")
     * @return the corresponding FailureReason enum value
     */
    public IndexingJobHistory.FailureReason toJobHistoryReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return IndexingJobHistory.FailureReason.UNKNOWN;
        }

        String normalized = reason.toUpperCase().trim();

        // Direct enum name match
        try {
            return IndexingJobHistory.FailureReason.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            // Fall through to pattern matching
        }

        // Pattern-based matching for common error messages
        if (normalized.contains("MODEL") && normalized.contains("NOT") && normalized.contains("FOUND")) {
            return IndexingJobHistory.FailureReason.MODEL_NOT_FOUND;
        }
        if (normalized.contains("STAGING") || normalized.contains("REGISTRY")) {
            return IndexingJobHistory.FailureReason.STAGING_ERROR;
        }
        if (normalized.contains("OUT") && normalized.contains("MEMORY") || normalized.contains("OOM")) {
            return IndexingJobHistory.FailureReason.OUT_OF_MEMORY;
        }
        if (normalized.contains("MEMORY") && normalized.contains("KILL")) {
            return IndexingJobHistory.FailureReason.MEMORY_KILLED;
        }
        if (normalized.contains("CANCEL")) {
            return IndexingJobHistory.FailureReason.USER_CANCELLED;
        }
        if (normalized.contains("TIMEOUT") || normalized.contains("TIMED OUT")) {
            return IndexingJobHistory.FailureReason.TIMEOUT;
        }
        if (normalized.contains("LOAD") && normalized.contains("ERROR")) {
            return IndexingJobHistory.FailureReason.LOAD_ERROR;
        }
        if (normalized.contains("CONVERSION") || normalized.contains("CONVERT")) {
            return IndexingJobHistory.FailureReason.CONVERSION_ERROR;
        }
        if (normalized.contains("CHUNK")) {
            return IndexingJobHistory.FailureReason.CHUNKING_ERROR;
        }
        if (normalized.contains("EMBED")) {
            return IndexingJobHistory.FailureReason.EMBEDDING_ERROR;
        }
        if (normalized.contains("INDEX")) {
            return IndexingJobHistory.FailureReason.INDEXING_ERROR;
        }
        if (normalized.contains("SUBPROCESS") || normalized.contains("PROCESS")) {
            return IndexingJobHistory.FailureReason.SUBPROCESS_ERROR;
        }
        if (normalized.contains("IO") || normalized.contains("NETWORK") || normalized.contains("CONNECTION")) {
            return IndexingJobHistory.FailureReason.IO_ERROR;
        }
        if (normalized.contains("INVALID") || normalized.contains("INPUT")) {
            return IndexingJobHistory.FailureReason.INVALID_INPUT;
        }

        return IndexingJobHistory.FailureReason.UNKNOWN;
    }

    /**
     * Convert SubprocessRestartManager.FailureReason to IndexingJobHistory.FailureReason.
     *
     * @param reason the subprocess restart failure reason
     * @return the corresponding job history failure reason
     */
    public IndexingJobHistory.FailureReason toJobHistoryReason(SubprocessRestartManager.FailureReason reason) {
        if (reason == null) {
            return IndexingJobHistory.FailureReason.UNKNOWN;
        }

        return switch (reason) {
            case OUT_OF_MEMORY -> IndexingJobHistory.FailureReason.OUT_OF_MEMORY;
            case OOM_KILLED -> IndexingJobHistory.FailureReason.MEMORY_KILLED;
            case GPU_OUT_OF_MEMORY -> IndexingJobHistory.FailureReason.OUT_OF_MEMORY;
            case MEMORY_PRESSURE -> IndexingJobHistory.FailureReason.OUT_OF_MEMORY;
            case NATIVE_CRASH -> IndexingJobHistory.FailureReason.SUBPROCESS_ERROR;
            case TIMEOUT -> IndexingJobHistory.FailureReason.TIMEOUT;
            case STALLED_NO_HEARTBEAT, STALLED_NO_PROGRESS -> IndexingJobHistory.FailureReason.SUBPROCESS_ERROR;
            case BATCH_SIZE_TOO_LARGE -> IndexingJobHistory.FailureReason.INVALID_INPUT;
            case CANCELLED -> IndexingJobHistory.FailureReason.USER_CANCELLED;
            case UNKNOWN -> IndexingJobHistory.FailureReason.UNKNOWN;
        };
    }

    /**
     * Convert IngestProgressUpdate.FailureReason to IndexingJobHistory.FailureReason.
     *
     * @param reason the DTO failure reason
     * @return the corresponding job history failure reason
     */
    public IndexingJobHistory.FailureReason toJobHistoryReason(IngestProgressUpdate.FailureReason reason) {
        if (reason == null) {
            return IndexingJobHistory.FailureReason.UNKNOWN;
        }

        return switch (reason) {
            case UNKNOWN -> IndexingJobHistory.FailureReason.UNKNOWN;
            case OUT_OF_MEMORY -> IndexingJobHistory.FailureReason.OUT_OF_MEMORY;
            case OOM_KILLED -> IndexingJobHistory.FailureReason.MEMORY_KILLED;
            case MEMORY_PRESSURE -> IndexingJobHistory.FailureReason.OUT_OF_MEMORY;
            case NATIVE_CRASH -> IndexingJobHistory.FailureReason.SUBPROCESS_ERROR;
            case USER_CANCELLED -> IndexingJobHistory.FailureReason.USER_CANCELLED;
            case PROCESS_STUCK -> IndexingJobHistory.FailureReason.SUBPROCESS_ERROR;
            case LOAD_ERROR -> IndexingJobHistory.FailureReason.LOAD_ERROR;
            case EMBEDDING_ERROR -> IndexingJobHistory.FailureReason.EMBEDDING_ERROR;
            case INDEXING_ERROR -> IndexingJobHistory.FailureReason.INDEXING_ERROR;
            case RESTART_EXHAUSTED -> IndexingJobHistory.FailureReason.SUBPROCESS_ERROR;
        };
    }

    /**
     * Convert IndexingJobHistory.FailureReason to IngestProgressUpdate.FailureReason.
     *
     * @param reason the job history failure reason
     * @return the corresponding DTO failure reason
     */
    public IngestProgressUpdate.FailureReason toDtoReason(IndexingJobHistory.FailureReason reason) {
        if (reason == null) {
            return IngestProgressUpdate.FailureReason.UNKNOWN;
        }

        return switch (reason) {
            case NONE -> IngestProgressUpdate.FailureReason.UNKNOWN;
            case OUT_OF_MEMORY -> IngestProgressUpdate.FailureReason.OUT_OF_MEMORY;
            case MEMORY_KILLED -> IngestProgressUpdate.FailureReason.OOM_KILLED;
            case USER_CANCELLED -> IngestProgressUpdate.FailureReason.USER_CANCELLED;
            case LOAD_ERROR -> IngestProgressUpdate.FailureReason.LOAD_ERROR;
            case CONVERSION_ERROR -> IngestProgressUpdate.FailureReason.LOAD_ERROR;
            case CHUNKING_ERROR -> IngestProgressUpdate.FailureReason.LOAD_ERROR;
            case EMBEDDING_ERROR -> IngestProgressUpdate.FailureReason.EMBEDDING_ERROR;
            case INDEXING_ERROR -> IngestProgressUpdate.FailureReason.INDEXING_ERROR;
            case SUBPROCESS_ERROR -> IngestProgressUpdate.FailureReason.NATIVE_CRASH;
            case IO_ERROR -> IngestProgressUpdate.FailureReason.LOAD_ERROR;
            case INVALID_INPUT -> IngestProgressUpdate.FailureReason.LOAD_ERROR;
            case TIMEOUT -> IngestProgressUpdate.FailureReason.PROCESS_STUCK;
            case MODEL_NOT_FOUND -> IngestProgressUpdate.FailureReason.EMBEDDING_ERROR;
            case STAGING_ERROR -> IngestProgressUpdate.FailureReason.EMBEDDING_ERROR;
            case UNKNOWN -> IngestProgressUpdate.FailureReason.UNKNOWN;
        };
    }

    /**
     * Convert IndexingJobHistory.FailureReason to SubprocessRestartManager.FailureReason.
     *
     * @param reason the job history failure reason
     * @return the corresponding subprocess restart failure reason
     */
    public SubprocessRestartManager.FailureReason toRestartReason(IndexingJobHistory.FailureReason reason) {
        if (reason == null) {
            return SubprocessRestartManager.FailureReason.UNKNOWN;
        }

        return switch (reason) {
            case NONE -> SubprocessRestartManager.FailureReason.UNKNOWN;
            case OUT_OF_MEMORY -> SubprocessRestartManager.FailureReason.OUT_OF_MEMORY;
            case MEMORY_KILLED -> SubprocessRestartManager.FailureReason.OOM_KILLED;
            case USER_CANCELLED -> SubprocessRestartManager.FailureReason.CANCELLED;
            case TIMEOUT -> SubprocessRestartManager.FailureReason.TIMEOUT;
            case SUBPROCESS_ERROR -> SubprocessRestartManager.FailureReason.NATIVE_CRASH;
            case LOAD_ERROR, CONVERSION_ERROR, CHUNKING_ERROR, EMBEDDING_ERROR,
                 INDEXING_ERROR, IO_ERROR, INVALID_INPUT, MODEL_NOT_FOUND,
                 STAGING_ERROR, UNKNOWN -> SubprocessRestartManager.FailureReason.UNKNOWN;
        };
    }

    /**
     * Determine if a failure reason indicates an OOM-related failure that may benefit from restart.
     *
     * @param reason the failure reason string
     * @return true if OOM-related
     */
    public boolean isOomRelated(String reason) {
        if (reason == null) return false;
        String upper = reason.toUpperCase();
        return upper.contains("OOM") ||
               upper.contains("OUT_OF_MEMORY") ||
               upper.contains("MEMORY_KILLED") ||
               upper.contains("MEMORY_PRESSURE") ||
               (upper.contains("OUT") && upper.contains("MEMORY"));
    }

    /**
     * Determine if a failure reason indicates a model/staging issue that should not trigger restart.
     *
     * @param reason the failure reason string
     * @return true if model/staging related
     */
    public boolean isModelOrStagingIssue(String reason) {
        if (reason == null) return false;
        String upper = reason.toUpperCase();
        return upper.contains("MODEL_NOT_FOUND") ||
               upper.contains("STAGING_ERROR") ||
               upper.contains("MODEL NOT FOUND") ||
               upper.contains("STAGING SERVICE") ||
               upper.contains("REGISTRY");
    }

    /**
     * Determine if a failure is recoverable through restart.
     *
     * @param reason the job history failure reason
     * @return true if potentially recoverable
     */
    public boolean isRecoverable(IndexingJobHistory.FailureReason reason) {
        if (reason == null) return false;

        return switch (reason) {
            case OUT_OF_MEMORY, MEMORY_KILLED, SUBPROCESS_ERROR, TIMEOUT -> true;
            case NONE, USER_CANCELLED, LOAD_ERROR, CONVERSION_ERROR, CHUNKING_ERROR,
                 EMBEDDING_ERROR, INDEXING_ERROR, IO_ERROR, INVALID_INPUT,
                 MODEL_NOT_FOUND, STAGING_ERROR, UNKNOWN -> false;
        };
    }

    /**
     * Get a human-readable description for a failure reason.
     *
     * @param reason the job history failure reason
     * @return human-readable description
     */
    public String getDescription(IndexingJobHistory.FailureReason reason) {
        if (reason == null) return "Unknown error";

        return switch (reason) {
            case NONE -> "No failure";
            case OUT_OF_MEMORY -> "Out of memory error";
            case MEMORY_KILLED -> "Process killed due to memory pressure";
            case USER_CANCELLED -> "Cancelled by user";
            case LOAD_ERROR -> "Failed to load document";
            case CONVERSION_ERROR -> "Failed to convert document";
            case CHUNKING_ERROR -> "Failed to chunk document";
            case EMBEDDING_ERROR -> "Failed to generate embeddings";
            case INDEXING_ERROR -> "Failed to index document";
            case SUBPROCESS_ERROR -> "Subprocess crashed or failed";
            case IO_ERROR -> "I/O or network error";
            case INVALID_INPUT -> "Invalid input or configuration";
            case TIMEOUT -> "Operation timed out";
            case MODEL_NOT_FOUND -> "Embedding model not found in registry";
            case STAGING_ERROR -> "Staging service unavailable or error";
            case UNKNOWN -> "Unknown error";
        };
    }
}
