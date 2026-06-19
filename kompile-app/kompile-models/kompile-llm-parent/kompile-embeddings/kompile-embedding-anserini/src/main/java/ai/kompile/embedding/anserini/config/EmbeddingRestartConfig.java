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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persisted configuration for the embedding-subprocess restart governor.
 *
 * <p>Persisted to {@code ~/.kompile/config/embedding-restart-config.json} (kompile JSON
 * config — surfaced via the UI and CLI, never via Spring properties).</p>
 *
 * <ul>
 *   <li>{@code autoRestartEnabled} — master switch. When {@code false}, a crashed embedding
 *       subprocess is never automatically restarted/respawned.</li>
 *   <li>{@code nativeCrashThreshold} — number of <em>consecutive</em> native crashes
 *       (SIGABRT/SIGSEGV, exit 134/136/139) tolerated before the circuit breaker trips and
 *       pauses further respawns until manually resumed.</li>
 * </ul>
 *
 * <p>Fields are boxed so missing keys in older/partial config files deserialize to
 * {@code null} and fall back to defaults via the {@code *OrDefault} accessors rather than
 * silently becoming {@code false}/{@code 0}.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmbeddingRestartConfig {

    public static final boolean DEFAULT_AUTO_RESTART_ENABLED = true;
    public static final int DEFAULT_NATIVE_CRASH_THRESHOLD = 3;

    @JsonProperty("autoRestartEnabled")
    private Boolean autoRestartEnabled;

    @JsonProperty("nativeCrashThreshold")
    private Integer nativeCrashThreshold;

    public static EmbeddingRestartConfig defaults() {
        return EmbeddingRestartConfig.builder()
                .autoRestartEnabled(DEFAULT_AUTO_RESTART_ENABLED)
                .nativeCrashThreshold(DEFAULT_NATIVE_CRASH_THRESHOLD)
                .build();
    }

    /** Null-safe master switch (missing/unset → enabled). */
    @JsonIgnore
    public boolean isAutoRestartEnabledOrDefault() {
        return autoRestartEnabled == null ? DEFAULT_AUTO_RESTART_ENABLED : autoRestartEnabled;
    }

    /** Null-safe threshold, clamped to a sane minimum of 1. */
    @JsonIgnore
    public int nativeCrashThresholdOrDefault() {
        return (nativeCrashThreshold == null || nativeCrashThreshold < 1)
                ? DEFAULT_NATIVE_CRASH_THRESHOLD
                : nativeCrashThreshold;
    }
}
