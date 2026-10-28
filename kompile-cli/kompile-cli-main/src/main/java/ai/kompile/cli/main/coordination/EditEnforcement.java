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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.coordination;

/**
 * Harness-level enforcement mode of edit coordination for every file-mutating native tool
 * call ({@code edit}, {@code write}, {@code edit_batch}, {@code edit_patch},
 * {@code patch}, LSP rename, shell file-writes).
 *
 * <ul>
 *   <li>{@link #OFF} — mutation tools do no coordination work at all: no guard critical
 *       section, no auto-locks, no warnings. Pure solo fast path.</li>
 *   <li>{@link #ADVISORY} — the historical behaviour, byte for byte: writes always apply,
 *       a lock another live session holds is reported as a WARNING after the write
 *       (agent-identity callers still get the pre-write refusal of
 *       {@link CoordinationStateManager#guardAgentEdit}).</li>
 *   <li>{@link #ENFORCE} — the default. Every mutation is arbitrated by the coordinator
 *       BEFORE it touches the file system: a file held by another live owner (another
 *       session, or a sibling agent) refuses the write with CONFLICT, and a successful
 *       write auto-acquires or refreshes this session's lock on the target so subsequent
 *       calls — from any host — refuse too. Agent-identity behaviour is unchanged.</li>
 * </ul>
 *
 * <p>Sourced from {@code ~/.kompile/harness-config.json}
 * ({@code editCoordinationMode}; default {@code enforce}) with the environment override
 * {@code KOMPILE_EDIT_COORDINATION} for CI and one-shot recovery. Resolved once per
 * coordinator instance, at construction: restarting a session to change the mode avoids
 * "the lock I checked a second ago" races.
 */
public enum EditEnforcement {

    OFF,
    ADVISORY,
    ENFORCE;

    /** Environment override for the configured mode; unchecked values are ignored. */
    public static final String ENV_PROPERTY = "KOMPILE_EDIT_COORDINATION";

    /** Harness-config key this mode is read from. */
    public static final String CONFIG_PROPERTY = "editCoordinationMode";

    /** The default for a fresh config: coordination is a correctness invariant. */
    public static final EditEnforcement DEFAULT = ENFORCE;

    /** Parses a config/env value; blank means {@link #DEFAULT}, unknown values are null. */
    public static EditEnforcement parse(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT;
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "off" -> OFF;
            case "advisory" -> ADVISORY;
            case "enforce" -> ENFORCE;
            default -> null;
        };
    }

    /**
     * Resolves the mode: the {@link #ENV_PROPERTY} environment variable wins over the
     * {@link #CONFIG_PROPERTY} field of {@code ~/.kompile/harness-config.json}, which wins
     * over {@link #DEFAULT}. Unrecognised values fall through to the next source.
     */
    public static EditEnforcement fromConfig(String configuredValue) {
        String env = System.getenv(ENV_PROPERTY);
        EditEnforcement fromEnv = env == null ? null : parse(env);
        if (fromEnv != null) return fromEnv;
        EditEnforcement fromConfig = parse(configuredValue);
        return fromConfig != null ? fromConfig : DEFAULT;
    }
}
