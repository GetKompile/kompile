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

package ai.kompile.cli.main.lsp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable configuration for a single language server: how to launch it, which
 * files it handles, and the timeouts that bound its lifecycle.
 *
 * <p>Extensions are stored dot-prefixed and lower-cased (e.g. {@code .java}).
 * {@link #languageIds} maps an extension to the LSP {@code languageId} the server
 * expects (e.g. {@code .cu} → {@code cuda-cpp}); extensions absent from that map
 * fall back to {@link #language}.</p>
 */
public record LspServerConfig(
        String language,
        List<String> command,
        Set<String> extensions,
        Map<String, String> languageIds,
        List<String> rootMarkers,
        boolean enabled,
        long startupTimeoutMs,
        long requestTimeoutMs,
        Map<String, String> env,
        JsonNode initializationOptions,
        String installHint
) {

    /** {@code jdtls -data <dir>} placeholder, expanded per-root by the connection. */
    public static final String DATA_DIR_PLACEHOLDER = "{dataDir}";

    public LspServerConfig {
        command = command == null ? List.of() : List.copyOf(command);
        extensions = extensions == null ? Set.of() : Set.copyOf(extensions);
        languageIds = languageIds == null ? Map.of() : Map.copyOf(languageIds);
        rootMarkers = rootMarkers == null ? List.of() : List.copyOf(rootMarkers);
        env = env == null ? Map.of() : Map.copyOf(env);
        installHint = installHint == null ? "" : installHint;
    }

    /** The LSP {@code languageId} for a (dot-prefixed, lower-cased) extension. */
    public String languageIdFor(String extension) {
        String id = languageIds.get(extension);
        return id != null ? id : language;
    }

    /** True if this config's launch command uses the {@code {dataDir}} placeholder (jdtls). */
    public boolean usesDataDir() {
        return command.stream().anyMatch(arg -> arg.contains(DATA_DIR_PLACEHOLDER));
    }

    public static Builder builder(String language) {
        return new Builder().language(language);
    }

    public Builder toBuilder() {
        return new Builder()
                .language(language)
                .command(command)
                .extensions(extensions)
                .languageIds(languageIds)
                .rootMarkers(rootMarkers)
                .enabled(enabled)
                .startupTimeoutMs(startupTimeoutMs)
                .requestTimeoutMs(requestTimeoutMs)
                .env(env)
                .initializationOptions(initializationOptions)
                .installHint(installHint);
    }

    /** Mutable builder — used for defaults assembly and JSON-overlay merges. */
    public static final class Builder {
        private String language;
        private List<String> command = List.of();
        private Set<String> extensions = Set.of();
        private Map<String, String> languageIds = Map.of();
        private List<String> rootMarkers = List.of();
        private boolean enabled = true;
        private long startupTimeoutMs = 30_000L;
        private long requestTimeoutMs = 15_000L;
        private Map<String, String> env = Map.of();
        private JsonNode initializationOptions;
        private String installHint = "";

        public Builder language(String v) { this.language = v; return this; }
        public Builder command(List<String> v) { this.command = v; return this; }
        public Builder extensions(Set<String> v) { this.extensions = v; return this; }
        public Builder languageIds(Map<String, String> v) { this.languageIds = v; return this; }
        public Builder rootMarkers(List<String> v) { this.rootMarkers = v; return this; }
        public Builder enabled(boolean v) { this.enabled = v; return this; }
        public Builder startupTimeoutMs(long v) { this.startupTimeoutMs = v; return this; }
        public Builder requestTimeoutMs(long v) { this.requestTimeoutMs = v; return this; }
        public Builder env(Map<String, String> v) { this.env = v; return this; }
        public Builder initializationOptions(JsonNode v) { this.initializationOptions = v; return this; }
        public Builder installHint(String v) { this.installHint = v; return this; }

        public LspServerConfig build() {
            return new LspServerConfig(language, command, extensions, languageIds, rootMarkers,
                    enabled, startupTimeoutMs, requestTimeoutMs, env, initializationOptions, installHint);
        }
    }
}
