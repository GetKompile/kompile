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

package ai.kompile.embedding.anserini.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Snapshot of the embedding-subprocess restart-governor state, for the status REST endpoint
 * and UI. Combines persisted config (the toggle + threshold) with live runtime state
 * (paused flag, consecutive native-crash count, last crash reason, subprocess liveness).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddingRestartStatus {

    /** Master switch (persisted): are automatic restarts enabled at all. */
    private boolean autoRestartEnabled;

    /** Consecutive native crashes tolerated before the breaker trips (persisted). */
    private int nativeCrashThreshold;

    /** Whether restarts are currently paused (breaker tripped or manually disabled after a crash). */
    private boolean restartsPaused;

    /** Consecutive native crashes observed since the last healthy load. */
    private int consecutiveNativeCrashes;

    /** Human-readable reason restarts are paused, or null. */
    private String pausedReason;

    /** Last observed crash reason, or null. */
    private String lastCrashReason;

    /** Whether the embedding subprocess is currently alive. */
    private boolean subprocessRunning;

    /** Whether the embedding model bean exists in this app (false if embedding is disabled/uncreated). */
    private boolean modelAvailable;
}
