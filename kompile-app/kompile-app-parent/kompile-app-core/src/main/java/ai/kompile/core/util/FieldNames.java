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

package ai.kompile.core.util;

/**
 * Shared constants for JSON map keys and other commonly repeated string literals
 * used across the kompile-app modules.
 *
 * <p>Using these constants instead of inline string literals prevents typos that
 * silently produce wrong field names at runtime, and makes large-scale renames
 * safe via a single-point change.</p>
 */
public final class FieldNames {

    private FieldNames() {}

    // ---- Identity / correlation ----
    public static final String TASK_ID = "taskId";
    public static final String SESSION_ID = "sessionId";
    public static final String FACT_SHEET_ID = "factSheetId";
    public static final String MODEL_ID = "modelId";

    // ---- Timing ----
    public static final String TIMESTAMP = "timestamp";
    public static final String DURATION_MS = "durationMs";

    // ---- Retrieval / ranking ----
    public static final String SCORE = "score";

    /**
     * String values for ingest/subprocess job status protocol messages.
     * These must match the strings sent by subprocess callbacks and used in switch dispatch.
     * Prefer the {@code IndexingJobHistory.JobStatus} enum for typed comparisons; use these
     * only when interacting with the string-based HTTP callback protocol.
     */
    public static final class JobStatusValues {
        private JobStatusValues() {}

        public static final String QUEUED = "QUEUED";
        public static final String RUNNING = "RUNNING";
        public static final String COMPLETED = "COMPLETED";
        public static final String FAILED = "FAILED";
        public static final String CANCELLED = "CANCELLED";
        public static final String MEMORY_KILLED = "MEMORY_KILLED";
        public static final String UNKNOWN = "UNKNOWN";
    }
}
