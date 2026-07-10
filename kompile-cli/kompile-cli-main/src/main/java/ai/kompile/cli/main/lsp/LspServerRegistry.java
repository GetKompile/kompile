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

import java.util.ArrayList;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The catalog of configured language servers: the built-in mainstream-language
 * defaults, deep-merged with an optional user overlay at {@code ~/.kompile/lsp-servers.json}.
 *
 * <p>Overlay format: {@code {"servers": {"<language>": {<partial overrides>}}}}. Every
 * key present in an override replaces the corresponding built-in value; {@code "enabled":
 * false} disables a server; a language absent from the built-ins is added wholesale.</p>
 */
public class LspServerRegistry {

    private final Path configFile;
    private final ObjectMapper mapper;
    private final Map<String, LspServerConfig> servers;

    public LspServerRegistry(Path configFile) {
        this(configFile, JsonUtils.standardMapper());
    }

    public LspServerRegistry(Path configFile, ObjectMapper mapper) {
        this.configFile = configFile;
        this.mapper = mapper;
        this.servers = loadMerged();
    }

    /** Production factory — reads the overlay from {@code ~/.kompile/lsp-servers.json}. */
    public static LspServerRegistry forHome() {
        Path cfg = KompileHome.homeDirectory().toPath().resolve("lsp-servers.json");
        return new LspServerRegistry(cfg);
    }

    /** The merged server table, keyed by language. Unmodifiable. */
    public Map<String, LspServerConfig> servers() {
        return servers;
    }

    public LspServerConfig forLanguage(String language) {
        return servers.get(language);
    }

    // ── Resolution ─────────────────────────────────────────────────────────

    /** The enabled server config that handles {@code file}'s extension, if any. */
    public Optional<LspServerConfig> resolveForFile(Path file) {
        String ext = LspLanguages.extensionOf(file);
        if (ext.isEmpty()) {
            return Optional.empty();
        }
        for (LspServerConfig cfg : servers.values()) {
            if (cfg.enabled() && cfg.extensions().contains(ext)) {
                return Optional.of(cfg);
            }
        }
        return Optional.empty();
    }

    /** Convenience: {@link #resolveRoot(Path, LspServerConfig, Path)} with the file's parent as fallback. */
    public Path resolveRoot(Path file, LspServerConfig cfg) {
        Path parent = file.toAbsolutePath().normalize().getParent();
        return resolveRoot(file, cfg, parent);
    }

    /**
     * Walk parent directories from {@code file} up to (and stopping at) the user home
     * or the filesystem root; the first directory containing any {@code rootMarkers}
     * entry wins. Falls back to {@code fallback} when nothing matches.
     */
    public Path resolveRoot(Path file, LspServerConfig cfg, Path fallback) {
        Path start = Files.isDirectory(file) ? file : file.getParent();
        if (start == null) {
            return fallback;
        }
        Path home = userHome();
        Path dir = start.toAbsolutePath().normalize();
        while (dir != null) {
            for (String marker : cfg.rootMarkers()) {
                if (Files.exists(dir.resolve(marker))) {
                    return dir;
                }
            }
            if (home != null && dir.equals(home)) {
                break;
            }
            dir = dir.getParent();
        }
        return fallback != null ? fallback : start.toAbsolutePath().normalize();
    }

    // ── Availability ───────────────────────────────────────────────────────

    /** True if the server's launch binary exists and is executable (absolute path or on {@code PATH}). */
    public boolean isAvailable(LspServerConfig cfg) {
        return isAvailable(cfg, System.getenv("PATH"));
    }

    /** Availability against an explicit {@code PATH} value (test seam). */
    public boolean isAvailable(LspServerConfig cfg, String pathEnv) {
        if (cfg.command().isEmpty()) {
            return false;
        }
        return isExecutable(cfg.command().get(0), pathEnv);
    }

    static boolean isExecutable(String exe, String pathEnv) {
        if (exe == null || exe.isEmpty()) {
            return false;
        }
        Path direct = Path.of(exe);
        if (direct.isAbsolute()) {
            return Files.isRegularFile(direct) && Files.isExecutable(direct);
        }
        if (exe.indexOf(File.separatorChar) >= 0 || exe.indexOf('/') >= 0) {
            Path rel = direct.toAbsolutePath();
            return Files.isRegularFile(rel) && Files.isExecutable(rel);
        }
        if (pathEnv == null || pathEnv.isEmpty()) {
            return false;
        }
        for (String pathDir : pathEnv.split(File.pathSeparator)) {
            if (pathDir.isEmpty()) {
                continue;
            }
            Path candidate = Path.of(pathDir).resolve(exe);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }

    // ── Merge ──────────────────────────────────────────────────────────────

    private Map<String, LspServerConfig> loadMerged() {
        Map<String, LspServerConfig> merged = new LinkedHashMap<>(builtIns());
        if (configFile == null || !Files.exists(configFile)) {
            return Collections.unmodifiableMap(merged);
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(configFile));
            JsonNode serversNode = root.path("servers");
            if (serversNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = serversNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String language = field.getKey();
                    LspServerConfig base = merged.get(language);
                    merged.put(language, mergeOverlay(language, base, field.getValue()));
                }
            }
        } catch (Exception e) {
            System.err.println("[LSP] Failed to read overlay " + configFile + ": " + e.getMessage());
        }
        return Collections.unmodifiableMap(merged);
    }

    private LspServerConfig mergeOverlay(String language, LspServerConfig base, JsonNode overlay) {
        LspServerConfig.Builder b = base != null ? base.toBuilder() : LspServerConfig.builder(language);
        if (overlay.has("command")) {
            b.command(toStringList(overlay.get("command")));
        }
        if (overlay.has("extensions")) {
            b.extensions(toStringSet(overlay.get("extensions")));
        }
        if (overlay.has("languageIds")) {
            b.languageIds(toStringMap(overlay.get("languageIds")));
        }
        if (overlay.has("rootMarkers")) {
            b.rootMarkers(toStringList(overlay.get("rootMarkers")));
        }
        if (overlay.has("enabled")) {
            b.enabled(overlay.get("enabled").asBoolean(true));
        }
        if (overlay.has("startupTimeoutMs")) {
            b.startupTimeoutMs(overlay.get("startupTimeoutMs").asLong());
        }
        if (overlay.has("requestTimeoutMs")) {
            b.requestTimeoutMs(overlay.get("requestTimeoutMs").asLong());
        }
        if (overlay.has("env")) {
            b.env(toStringMap(overlay.get("env")));
        }
        if (overlay.has("initializationOptions")) {
            b.initializationOptions(overlay.get("initializationOptions"));
        }
        if (overlay.has("installHint")) {
            b.installHint(overlay.get("installHint").asText(""));
        }
        return b.build();
    }

    private static List<String> toStringList(JsonNode node) {
        ArrayList<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    private static Set<String> toStringSet(JsonNode node) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    private static Map<String, String> toStringMap(JsonNode node) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        }
        return out;
    }

    private static Path userHome() {
        String home = System.getProperty("user.home");
        return home != null ? Path.of(home).toAbsolutePath().normalize() : null;
    }

    // ── Built-in defaults (the deliverable language set) ─────────────────────

    /** The mainstream-language default server table, keyed by language. */
    public static Map<String, LspServerConfig> builtIns() {
        Map<String, LspServerConfig> m = new LinkedHashMap<>();

        m.put("java", LspServerConfig.builder("java")
                .command(List.of("jdtls", "-data", LspServerConfig.DATA_DIR_PLACEHOLDER))
                .extensions(Set.of(".java"))
                .rootMarkers(List.of("pom.xml", "build.gradle", "build.gradle.kts", ".git"))
                .startupTimeoutMs(90_000L)
                .installHint("install Eclipse JDT LS; ensure the `jdtls` launcher is on PATH (needs a JDK 17+)")
                .build());

        m.put("cpp", LspServerConfig.builder("cpp")
                .command(List.of("clangd", "--background-index", "--log=error"))
                .extensions(Set.of(".c", ".h", ".cpp", ".hpp", ".cc", ".hh", ".cxx", ".hxx", ".cu", ".cuh"))
                .languageIds(Map.ofEntries(
                        Map.entry(".c", "c"),
                        Map.entry(".h", "cpp"),
                        Map.entry(".cpp", "cpp"),
                        Map.entry(".hpp", "cpp"),
                        Map.entry(".cc", "cpp"),
                        Map.entry(".hh", "cpp"),
                        Map.entry(".cxx", "cpp"),
                        Map.entry(".hxx", "cpp"),
                        Map.entry(".cu", "cuda-cpp"),
                        Map.entry(".cuh", "cuda-cpp")))
                .rootMarkers(List.of("compile_commands.json", ".clangd", ".git"))
                .startupTimeoutMs(30_000L)
                .installHint("install clangd (apt/dnf `clangd` or LLVM release); "
                        + "CUDA needs compile flags in compile_commands.json")
                .build());

        m.put("rust", LspServerConfig.builder("rust")
                .command(List.of("rust-analyzer"))
                .extensions(Set.of(".rs"))
                .rootMarkers(List.of("Cargo.toml", ".git"))
                .startupTimeoutMs(45_000L)
                .installHint("`rustup component add rust-analyzer`")
                .build());

        m.put("python", LspServerConfig.builder("python")
                .command(List.of("pyright-langserver", "--stdio"))
                .extensions(Set.of(".py", ".pyi"))
                .rootMarkers(List.of("pyproject.toml", "setup.py", "requirements.txt", ".git"))
                .startupTimeoutMs(30_000L)
                .installHint("`npm i -g pyright`")
                .build());

        m.put("typescript", LspServerConfig.builder("typescript")
                .command(List.of("typescript-language-server", "--stdio"))
                .extensions(Set.of(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs"))
                .languageIds(Map.ofEntries(
                        Map.entry(".ts", "typescript"),
                        Map.entry(".tsx", "typescriptreact"),
                        Map.entry(".js", "javascript"),
                        Map.entry(".mjs", "javascript"),
                        Map.entry(".cjs", "javascript"),
                        Map.entry(".jsx", "javascriptreact")))
                .rootMarkers(List.of("tsconfig.json", "package.json", ".git"))
                .startupTimeoutMs(30_000L)
                .installHint("`npm i -g typescript-language-server typescript`")
                .build());

        m.put("go", LspServerConfig.builder("go")
                .command(List.of("gopls"))
                .extensions(Set.of(".go"))
                .rootMarkers(List.of("go.mod", ".git"))
                .startupTimeoutMs(30_000L)
                .installHint("`go install golang.org/x/tools/gopls@latest`")
                .build());

        return m;
    }
}
