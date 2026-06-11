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

import ai.kompile.codeindexer.domain.CodeEntity;
import ai.kompile.codeindexer.domain.CodeEntityType;
import ai.kompile.codeindexer.domain.CodeRelationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts code entities from source files by delegating to language-specific
 * {@link LanguageParser} implementations discovered via Spring component scan.
 *
 * Uses {@link LanguageRegistry} for language detection so that per-file and
 * per-pattern overrides are respected.
 */
@Component
public class CodeEntityExtractor {

    private static final Logger log = LoggerFactory.getLogger(CodeEntityExtractor.class);

    private final LanguageRegistry languageRegistry;
    private final Map<String, LanguageParser> parsersByLanguage = new HashMap<>();

    public record ExtractionResult(
            List<CodeEntity> entities,
            List<RelationTriple> relations
    ) {}

    public record RelationTriple(
            String sourceFqn,
            String targetFqn,
            CodeRelationType relationType
    ) {}

    @Autowired
    public CodeEntityExtractor(LanguageRegistry languageRegistry,
                               @Autowired(required = false) List<LanguageParser> parsers) {
        this.languageRegistry = languageRegistry;
        if (parsers != null) {
            for (LanguageParser parser : parsers) {
                for (String lang : parser.supportedLanguages()) {
                    parsersByLanguage.put(lang, parser);
                }
            }
        }
        log.info("CodeEntityExtractor initialized with {} parsers covering {} languages",
                parsers != null ? parsers.size() : 0, parsersByLanguage.size());
    }

    public String detectLanguage(Path filePath) {
        return languageRegistry.detectLanguage(filePath);
    }

    public boolean isSupported(Path filePath) {
        return languageRegistry.isSupported(filePath);
    }

    /**
     * Extract code entities from a file.
     * Delegates to the appropriate LanguageParser based on detected language.
     */
    public ExtractionResult extract(Path filePath, String projectId) throws IOException {
        String language = languageRegistry.detectLanguage(filePath);
        if (language == null) {
            return new ExtractionResult(List.of(), List.of());
        }

        String content = Files.readString(filePath);
        String[] lines = content.split("\n", -1);
        String relativePath = filePath.toString();

        // Always create a FILE entity
        List<CodeEntity> entities = new ArrayList<>();
        List<RelationTriple> relations = new ArrayList<>();

        CodeEntity fileEntity = CodeEntity.builder()
                .projectId(projectId)
                .entityType(CodeEntityType.FILE)
                .name(filePath.getFileName().toString())
                .fullyQualifiedName(relativePath)
                .filePath(relativePath)
                .language(language)
                .startLine(1)
                .endLine(lines.length)
                .contentPreview(content.length() > 500 ? content.substring(0, 500) : content)
                .build();
        entities.add(fileEntity);

        // Delegate to language-specific parser
        LanguageParser parser = parsersByLanguage.get(language);
        if (parser != null) {
            try {
                LanguageParser.ExtractionOutput output = parser.parse(lines, relativePath, projectId, language);
                entities.addAll(output.entities());
                relations.addAll(output.relations());
            } catch (Exception e) {
                log.warn("Parser error for {} ({}): {}", filePath, language, e.getMessage());
            }
        } else {
            log.debug("No parser registered for language '{}', file entity only: {}", language, filePath);
        }

        // Post-processing: extract call sites within method/function bodies
        List<RelationTriple> callRelations = extractCallSites(lines, entities, language);
        relations.addAll(callRelations);

        // Post-processing: extract design rationale from tagged comments
        extractDesignRationale(lines, entities, relations, relativePath, projectId, language);

        return new ExtractionResult(entities, relations);
    }

    /**
     * Extract with explicit language override (ignores registry detection).
     */
    public ExtractionResult extract(Path filePath, String projectId, String languageOverride) throws IOException {
        if (languageOverride != null) {
            languageRegistry.setFileLanguage(filePath.toAbsolutePath().toString(), languageOverride);
        }
        return extract(filePath, projectId);
    }

    /** Get the set of languages that have registered parsers */
    public Set<String> getParsedLanguages() {
        return Collections.unmodifiableSet(parsersByLanguage.keySet());
    }

    /** Get the language registry for configuration */
    public LanguageRegistry getLanguageRegistry() {
        return languageRegistry;
    }

    // =========================================================================
    // Call-site extraction (language-aware)
    // =========================================================================

    /** Unqualified call: word( */
    private static final Pattern CALL_BARE = Pattern.compile("\\b(\\w+)\\s*\\(");

    /** Qualified call via dot: qualifier.method(  — captures qualifier and method */
    private static final Pattern CALL_DOT = Pattern.compile("\\b(\\w+)\\.(\\w+)\\s*\\(");

    /** C++/Rust scope-resolution call: Qualifier::method( */
    private static final Pattern CALL_SCOPE = Pattern.compile("\\b(\\w+)::(\\w+)\\s*\\(");

    /** C++ arrow call: ptr->method( */
    private static final Pattern CALL_ARROW = Pattern.compile("\\b(\\w+)->\\s*(\\w+)\\s*\\(");

    /** Rust macro call: name!( or name![ or name!{ */
    private static final Pattern CALL_RUST_MACRO = Pattern.compile("\\b(\\w+)!\\s*[({\\[]");

    /** Python decorator: @decorator or @decorator(...) */
    private static final Pattern CALL_PY_DECORATOR = Pattern.compile("^\\s*@(\\w+)");

    /**
     * Language keywords and built-ins that look like calls but aren't.
     * Merged across all supported languages.
     */
    private static final Set<String> CALL_KEYWORDS = Set.of(
            // Control flow
            "if", "else", "elif", "elseif", "for", "foreach", "while", "do",
            "switch", "case", "match", "when", "select",
            "try", "catch", "finally", "except", "throw", "throws", "raise",
            "return", "break", "continue", "default", "yield", "await",
            // Declarations & modifiers
            "class", "interface", "enum", "struct", "trait", "impl", "record",
            "function", "func", "fun", "def", "fn", "sub", "proc", "lambda",
            "new", "delete", "typeof", "sizeof", "instanceof", "alignof", "nameof",
            "import", "package", "module", "require", "use", "from", "export",
            "public", "private", "protected", "internal", "abstract",
            "static", "final", "const", "let", "var", "val", "mut",
            "synchronized", "volatile", "native", "transient", "override",
            // Types & literals
            "int", "long", "short", "byte", "char", "float", "double", "boolean",
            "void", "string", "bool", "true", "false", "null", "nil", "none",
            "undefined", "NaN",
            // Loop/conditional constructs in various languages
            "unless", "until", "loop", "begin", "end", "rescue", "ensure",
            "with", "as", "in", "is", "not", "and", "or",
            // Go
            "range", "defer", "go", "chan", "map", "make", "append", "copy", "close",
            // Rust
            "unsafe", "where", "move", "ref", "box", "dyn", "async",
            // C/C++
            "register", "extern", "inline", "typedef", "union", "auto"
    );

    /** self/this keywords per language — calls on these resolve to the parent class. */
    private static final Set<String> SELF_KEYWORDS = Set.of(
            "this", "self", "super"
    );

    /**
     * Scans the body of each METHOD/FUNCTION/CONSTRUCTOR entity for call-site
     * patterns and returns CALLS relation triples. Language-aware: handles
     * dot-qualified calls ({@code obj.method()}), scope-resolution calls
     * ({@code Type::method()}), arrow calls ({@code ptr->method()}),
     * Rust macro invocations ({@code name!()}), Python decorators, and
     * resolves {@code this.}/{@code self.} calls to the parent class FQN.
     *
     * <p>Within the same file, call targets are resolved to their FQN when a
     * matching callable entity exists. Cross-file targets use the simple name.
     */
    List<RelationTriple> extractCallSites(String[] lines, List<CodeEntity> entities,
                                          String language) {
        List<RelationTriple> calls = new ArrayList<>();

        // Build lookup: simple name → FQN for same-file resolution
        Map<String, String> nameToFqn = new HashMap<>();
        for (CodeEntity e : entities) {
            if (isCallable(e.getEntityType())) {
                nameToFqn.putIfAbsent(e.getName(), e.getFullyQualifiedName());
            }
        }

        // Build lookup: class/struct simple name → FQN for qualified call resolution
        Map<String, String> typeToFqn = new HashMap<>();
        for (CodeEntity e : entities) {
            CodeEntityType t = e.getEntityType();
            if (t == CodeEntityType.CLASS || t == CodeEntityType.INTERFACE ||
                t == CodeEntityType.ENUM || t == CodeEntityType.RECORD ||
                t == CodeEntityType.MODULE) {
                typeToFqn.putIfAbsent(e.getName(), e.getFullyQualifiedName());
            }
        }

        for (CodeEntity entity : entities) {
            if (!isCallable(entity.getEntityType())) continue;
            if (entity.getStartLine() == null || entity.getEndLine() == null) continue;

            // The parent class FQN — for resolving this.method() / self.method()
            String parentClassFqn = entity.getParentFqn();

            int start = entity.getStartLine();  // 1-based inclusive
            int end = Math.min(entity.getEndLine(), lines.length);

            Set<String> seen = new HashSet<>();

            for (int i = start; i < end; i++) {
                String line = lines[i];
                String trimmed = line.trim();

                // Skip comment lines
                if (isCommentLine(trimmed, language)) continue;

                // --- Qualified dot calls: qualifier.method( ---
                Matcher mDot = CALL_DOT.matcher(line);
                while (mDot.find()) {
                    String qualifier = mDot.group(1);
                    String method = mDot.group(2);
                    if (CALL_KEYWORDS.contains(method)) continue;

                    if (SELF_KEYWORDS.contains(qualifier) && parentClassFqn != null) {
                        // this.foo() / self.foo() → resolve to parent class
                        String resolved = nameToFqn.getOrDefault(method, parentClassFqn + "." + method);
                        if (seen.add("dot:" + method)) {
                            calls.add(new RelationTriple(entity.getFullyQualifiedName(),
                                    resolved, CodeRelationType.CALLS));
                        }
                    } else {
                        // obj.method() — record the method name
                        if (seen.add("dot:" + method)) {
                            String resolved = resolveQualifiedCall(qualifier, method, typeToFqn, nameToFqn);
                            calls.add(new RelationTriple(entity.getFullyQualifiedName(),
                                    resolved, CodeRelationType.CALLS));
                        }
                    }
                }

                // --- C++/Rust scope-resolution: Qualifier::method( ---
                if ("cpp".equals(language) || "c".equals(language) ||
                    "rust".equals(language) || "zig".equals(language)) {
                    Matcher mScope = CALL_SCOPE.matcher(line);
                    while (mScope.find()) {
                        String qualifier = mScope.group(1);
                        String method = mScope.group(2);
                        if (CALL_KEYWORDS.contains(method)) continue;
                        if (seen.add("scope:" + qualifier + "::" + method)) {
                            String resolved = resolveQualifiedCall(qualifier, method, typeToFqn, nameToFqn);
                            calls.add(new RelationTriple(entity.getFullyQualifiedName(),
                                    resolved, CodeRelationType.CALLS));
                        }
                    }
                }

                // --- C++ arrow calls: ptr->method( ---
                if ("cpp".equals(language) || "c".equals(language)) {
                    Matcher mArrow = CALL_ARROW.matcher(line);
                    while (mArrow.find()) {
                        String method = mArrow.group(2);
                        if (CALL_KEYWORDS.contains(method)) continue;
                        if (seen.add("arrow:" + method)) {
                            String resolved = nameToFqn.getOrDefault(method, method);
                            calls.add(new RelationTriple(entity.getFullyQualifiedName(),
                                    resolved, CodeRelationType.CALLS));
                        }
                    }
                }

                // --- Rust macro calls: name!( ---
                if ("rust".equals(language)) {
                    Matcher mMacro = CALL_RUST_MACRO.matcher(line);
                    while (mMacro.find()) {
                        String macroName = mMacro.group(1);
                        if (seen.add("macro:" + macroName)) {
                            String resolved = nameToFqn.getOrDefault(macroName + "!", macroName);
                            calls.add(new RelationTriple(entity.getFullyQualifiedName(),
                                    resolved, CodeRelationType.CALLS));
                        }
                    }
                }

                // --- Python decorators referencing callable names ---
                if ("python".equals(language)) {
                    Matcher mDec = CALL_PY_DECORATOR.matcher(line);
                    if (mDec.find()) {
                        String decName = mDec.group(1);
                        if (!CALL_KEYWORDS.contains(decName) && seen.add("dec:" + decName)) {
                            String resolved = nameToFqn.getOrDefault(decName, decName);
                            calls.add(new RelationTriple(entity.getFullyQualifiedName(),
                                    resolved, CodeRelationType.CALLS));
                        }
                    }
                }

                // --- Bare (unqualified) calls: name( ---
                Matcher mBare = CALL_BARE.matcher(line);
                while (mBare.find()) {
                    String name = mBare.group(1);
                    if (CALL_KEYWORDS.contains(name)) continue;
                    if (name.length() < 2) continue;
                    if (name.matches("[A-Z][A-Z0-9_]+")) continue;  // ALL_CAPS constants
                    if (name.matches("\\d+")) continue;

                    // Skip if this was already captured by a qualified pattern
                    if (seen.contains("dot:" + name) || seen.contains("scope:" + name) ||
                        seen.contains("arrow:" + name)) continue;

                    if (seen.add(name)) {
                        String targetFqn = nameToFqn.getOrDefault(name, name);
                        calls.add(new RelationTriple(entity.getFullyQualifiedName(),
                                targetFqn, CodeRelationType.CALLS));
                    }
                }
            }
        }

        return calls;
    }

    /**
     * Resolve a qualified call like {@code ClassName.method} or {@code pkg::func}
     * using the type and callable lookups from the current file.
     */
    private String resolveQualifiedCall(String qualifier, String method,
                                        Map<String, String> typeToFqn,
                                        Map<String, String> nameToFqn) {
        // Try resolving qualifier to a known type, then produce Type.method FQN
        String typeFqn = typeToFqn.get(qualifier);
        if (typeFqn != null) {
            return typeFqn + "." + method;
        }
        // Fall back: check if the method name alone matches a known callable
        return nameToFqn.getOrDefault(method, method);
    }

    /**
     * Language-aware comment line detection.
     */
    private static boolean isCommentLine(String trimmed, String language) {
        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
            return true;
        }
        switch (language) {
            case "python", "ruby", "perl", "php":
                if (trimmed.startsWith("#")) return true;
                break;
            case "lua":
                if (trimmed.startsWith("--")) return true;
                break;
            default:
                break;
        }
        if (trimmed.startsWith("'''") || trimmed.startsWith("\"\"\"")) return true;
        return false;
    }

    private static boolean isCallable(CodeEntityType type) {
        return type == CodeEntityType.METHOD ||
               type == CodeEntityType.FUNCTION ||
               type == CodeEntityType.CONSTRUCTOR;
    }

    // =========================================================================
    // Design rationale extraction from tagged comments
    // =========================================================================

    /**
     * Matches tagged comments like {@code // NOTE:}, {@code # HACK:},
     * {@code // WHY:}, {@code // IMPORTANT:}, {@code // TODO:}, {@code // FIXME:}.
     * The tag is captured in group 1, the rationale text in group 2.
     */
    private static final Pattern RATIONALE_TAG = Pattern.compile(
            "(?://|#|--|/\\*\\*?|\\*)\\s*(?i)(NOTE|HACK|WHY|IMPORTANT|TODO|FIXME|WARNING|WORKAROUND|REVIEW|SAFETY)\\s*:?\\s*(.+)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Scans all lines for tagged comments (NOTE, HACK, WHY, IMPORTANT, TODO,
     * FIXME, etc.) and creates RATIONALE entities linked to the nearest enclosing
     * code entity via a {@code DEPENDS_ON} relation (conceptually "rationale_for").
     */
    void extractDesignRationale(String[] lines, List<CodeEntity> entities,
                                List<RelationTriple> relations,
                                String filePath, String projectId, String language) {
        int rationaleCount = 0;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = RATIONALE_TAG.matcher(lines[i]);
            if (!m.find()) continue;

            String tag = m.group(1).toUpperCase();
            String text = m.group(2).trim();
            if (text.isEmpty()) continue;

            // Collect continuation lines (next lines that are also comments with no new tag)
            StringBuilder full = new StringBuilder(text);
            int endLine = i;
            for (int j = i + 1; j < lines.length; j++) {
                String trimmed = lines[j].trim();
                // Must be a continuation comment line (starts with // or # or * but no new tag)
                if ((trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("*"))
                        && !RATIONALE_TAG.matcher(trimmed).find()) {
                    String cont = trimmed.replaceFirst("^(?://|#|\\*+)\\s*", "").trim();
                    if (!cont.isEmpty()) {
                        full.append(" ").append(cont);
                        endLine = j;
                    }
                } else {
                    break;
                }
            }

            int lineNum = i + 1; // 1-based
            String name = tag + "_" + filePath.replaceAll("[/\\\\]", "_") + "_L" + lineNum;
            String fqn = filePath + "::" + tag + "_L" + lineNum;

            CodeEntity rationale = CodeEntity.builder()
                    .projectId(projectId)
                    .entityType(CodeEntityType.RATIONALE)
                    .name(tag)
                    .fullyQualifiedName(fqn)
                    .filePath(filePath)
                    .language(language)
                    .startLine(lineNum)
                    .endLine(endLine + 1)
                    .docComment(full.toString())
                    .contentPreview(tag + ": " + (full.length() > 200 ? full.substring(0, 200) : full.toString()))
                    .build();
            entities.add(rationale);
            rationaleCount++;

            // Link to nearest enclosing entity (method/class/file)
            String parentFqn = findEnclosingEntity(lineNum, entities);
            if (parentFqn != null) {
                relations.add(new RelationTriple(fqn, parentFqn, CodeRelationType.DEPENDS_ON));
            }
        }
        if (rationaleCount > 0) {
            log.debug("Extracted {} design rationale comments from {}", rationaleCount, filePath);
        }
    }

    /**
     * Finds the nearest enclosing entity for a given line number. Prefers
     * method/function > class > file, choosing the tightest containing range.
     */
    private String findEnclosingEntity(int line, List<CodeEntity> entities) {
        CodeEntity best = null;
        int bestSpan = Integer.MAX_VALUE;
        for (CodeEntity e : entities) {
            if (e.getEntityType() == CodeEntityType.RATIONALE) continue;
            if (e.getStartLine() == null || e.getEndLine() == null) continue;
            if (e.getStartLine() <= line && e.getEndLine() >= line) {
                int span = e.getEndLine() - e.getStartLine();
                // Prefer tighter spans (methods over classes over files)
                if (span < bestSpan) {
                    bestSpan = span;
                    best = e;
                }
            }
        }
        return best != null ? best.getFullyQualifiedName() : null;
    }
}
