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

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Stateless helpers for mapping files to languages. All language knowledge lives
 * in {@link LspServerRegistry#builtIns()} (plus the user's overlay) — nothing is
 * hardcoded here.
 */
public final class LspLanguages {

    private LspLanguages() {
    }

    /**
     * The dot-prefixed, lower-cased extension of a file (e.g. {@code .java}), or the
     * empty string if the file has no extension (or is a dotfile like {@code .gitignore}).
     */
    public static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return "";
        }
        return name.substring(dot).toLowerCase(Locale.ROOT);
    }

    /**
     * The configured language for a file's extension, consulting the registry's merged
     * server table. Ignores the {@code enabled} flag — use {@link LspServerRegistry#resolveForFile}
     * for the enabled-aware resolution the tool actually dispatches on.
     */
    public static Optional<String> languageForExtension(LspServerRegistry registry, String extension) {
        if (extension == null || extension.isEmpty()) {
            return Optional.empty();
        }
        for (LspServerConfig cfg : registry.servers().values()) {
            if (cfg.extensions().contains(extension)) {
                return Optional.of(cfg.language());
            }
        }
        return Optional.empty();
    }
}
