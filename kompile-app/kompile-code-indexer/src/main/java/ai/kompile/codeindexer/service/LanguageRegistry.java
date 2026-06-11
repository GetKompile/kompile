/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.codeindexer.service;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry mapping file extensions and glob patterns to language identifiers.
 * Supports per-file overrides so users can control language inference.
 *
 * Covers all major programming languages that have ANTLR grammars in grammars-v4.
 */
@Component
public class LanguageRegistry {

    /** Extension → language ID (immutable defaults) */
    private static final Map<String, String> DEFAULT_EXTENSIONS;

    /** Per-file overrides: absolute path → forced language */
    private final Map<String, String> fileOverrides = new ConcurrentHashMap<>();

    /** Glob pattern overrides: pattern → language */
    private final Map<String, String> patternOverrides = new ConcurrentHashMap<>();

    static {
        Map<String, String> m = new LinkedHashMap<>();

        // JVM languages
        m.put(".java", "java");
        m.put(".kt", "kotlin");
        m.put(".kts", "kotlin");
        m.put(".scala", "scala");
        m.put(".groovy", "groovy");
        m.put(".gradle", "groovy");
        m.put(".clj", "clojure");
        m.put(".cljs", "clojure");

        // C-family
        m.put(".c", "c");
        m.put(".h", "c");
        m.put(".cpp", "cpp");
        m.put(".cc", "cpp");
        m.put(".cxx", "cpp");
        m.put(".hpp", "cpp");
        m.put(".hxx", "cpp");
        m.put(".cs", "csharp");
        m.put(".m", "objectivec");
        m.put(".mm", "objectivec");

        // Web / JavaScript family
        m.put(".js", "javascript");
        m.put(".jsx", "javascript");
        m.put(".mjs", "javascript");
        m.put(".cjs", "javascript");
        m.put(".ts", "typescript");
        m.put(".tsx", "typescript");
        m.put(".vue", "vue");
        m.put(".svelte", "svelte");

        // Python
        m.put(".py", "python");
        m.put(".pyi", "python");
        m.put(".pyx", "python");

        // Ruby
        m.put(".rb", "ruby");
        m.put(".rake", "ruby");
        m.put(".gemspec", "ruby");

        // Go
        m.put(".go", "go");

        // Rust
        m.put(".rs", "rust");

        // Swift
        m.put(".swift", "swift");

        // PHP
        m.put(".php", "php");
        m.put(".phtml", "php");

        // Shell
        m.put(".sh", "bash");
        m.put(".bash", "bash");
        m.put(".zsh", "bash");
        m.put(".fish", "fish");
        m.put(".ps1", "powershell");
        m.put(".psm1", "powershell");
        m.put(".bat", "batch");
        m.put(".cmd", "batch");

        // Data / config
        m.put(".sql", "sql");
        m.put(".json", "json");
        m.put(".yaml", "yaml");
        m.put(".yml", "yaml");
        m.put(".toml", "toml");
        m.put(".xml", "xml");
        m.put(".html", "html");
        m.put(".htm", "html");
        m.put(".css", "css");
        m.put(".scss", "scss");
        m.put(".less", "less");

        // Functional / other
        m.put(".hs", "haskell");
        m.put(".lhs", "haskell");
        m.put(".ml", "ocaml");
        m.put(".mli", "ocaml");
        m.put(".ex", "elixir");
        m.put(".exs", "elixir");
        m.put(".erl", "erlang");
        m.put(".hrl", "erlang");
        m.put(".lua", "lua");
        m.put(".r", "r");
        m.put(".R", "r");
        m.put(".jl", "julia");
        m.put(".dart", "dart");
        m.put(".zig", "zig");
        m.put(".nim", "nim");
        m.put(".v", "v");
        m.put(".pl", "perl");
        m.put(".pm", "perl");
        m.put(".tcl", "tcl");

        // Splan planning language
        m.put(".splan", "splan");

        // Build / infrastructure
        m.put(".tf", "terraform");
        m.put(".hcl", "hcl");
        m.put(".proto", "protobuf");
        m.put(".thrift", "thrift");
        m.put(".graphql", "graphql");
        m.put(".gql", "graphql");
        m.put(".g4", "antlr");
        m.put(".cmake", "cmake");

        // Markup / docs
        m.put(".md", "markdown");
        m.put(".rst", "restructuredtext");
        m.put(".tex", "latex");
        m.put(".adoc", "asciidoc");

        // Assembly
        m.put(".asm", "assembly");
        m.put(".s", "assembly");
        m.put(".S", "assembly");

        DEFAULT_EXTENSIONS = Collections.unmodifiableMap(m);
    }

    /**
     * Detect the language of a file.
     * Priority: file override > pattern override > extension lookup.
     */
    public String detectLanguage(Path filePath) {
        String absPath = filePath.toAbsolutePath().toString();

        // Check file-specific override
        String override = fileOverrides.get(absPath);
        if (override != null) return override;

        // Check pattern overrides
        String fileName = filePath.getFileName().toString();
        for (Map.Entry<String, String> entry : patternOverrides.entrySet()) {
            if (matchesPattern(fileName, absPath, entry.getKey())) {
                return entry.getValue();
            }
        }

        // Extension lookup
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0) {
            String ext = fileName.substring(dot);
            String lang = DEFAULT_EXTENSIONS.get(ext);
            if (lang != null) return lang;
        }

        // Special filenames
        return detectByFilename(fileName);
    }

    public boolean isSupported(Path filePath) {
        return detectLanguage(filePath) != null;
    }

    /** Override the language for a specific file path */
    public void setFileLanguage(String absolutePath, String language) {
        fileOverrides.put(Path.of(absolutePath).toAbsolutePath().toString(), language);
    }

    /** Override the language for a glob pattern (e.g. "*.jsx" → "typescript") */
    public void setPatternLanguage(String pattern, String language) {
        patternOverrides.put(pattern, language);
    }

    /** Remove a file-level override */
    public void removeFileOverride(String absolutePath) {
        fileOverrides.remove(Path.of(absolutePath).toAbsolutePath().toString());
    }

    /** Remove a pattern override */
    public void removePatternOverride(String pattern) {
        patternOverrides.remove(pattern);
    }

    /** Get all known extensions and their languages */
    public Map<String, String> getDefaultExtensions() {
        return DEFAULT_EXTENSIONS;
    }

    /** Get all active overrides */
    public Map<String, String> getFileOverrides() {
        return Collections.unmodifiableMap(fileOverrides);
    }

    public Map<String, String> getPatternOverrides() {
        return Collections.unmodifiableMap(patternOverrides);
    }

    /** Get all supported language IDs */
    public Set<String> getSupportedLanguages() {
        Set<String> langs = new TreeSet<>(DEFAULT_EXTENSIONS.values());
        langs.addAll(fileOverrides.values());
        langs.addAll(patternOverrides.values());
        return langs;
    }

    private boolean matchesPattern(String fileName, String absPath, String pattern) {
        if (pattern.startsWith("*")) {
            return fileName.endsWith(pattern.substring(1));
        }
        if (pattern.endsWith("*")) {
            return fileName.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return fileName.equals(pattern) || absPath.contains(pattern);
    }

    private String detectByFilename(String fileName) {
        return switch (fileName.toLowerCase()) {
            case "makefile", "gnumakefile" -> "make";
            case "dockerfile" -> "dockerfile";
            case "jenkinsfile" -> "groovy";
            case "vagrantfile" -> "ruby";
            case "rakefile" -> "ruby";
            case "gemfile" -> "ruby";
            case "podfile" -> "ruby";
            case "cmakelists.txt" -> "cmake";
            case "build.gradle", "settings.gradle" -> "groovy";
            case "build.gradle.kts", "settings.gradle.kts" -> "kotlin";
            case "cargo.toml" -> "toml";
            case "go.mod", "go.sum" -> "go";
            case "package.json", "tsconfig.json" -> "json";
            default -> null;
        };
    }
}
