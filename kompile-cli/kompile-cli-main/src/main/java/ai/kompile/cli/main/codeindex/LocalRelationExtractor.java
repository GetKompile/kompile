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

package ai.kompile.cli.main.codeindex;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Extracts code relations (CONTAINS, EXTENDS, IMPLEMENTS, IMPORTS, CALLS)
 * from a file's parsed entities and raw source lines. Pure static utility
 * with no state — safe for concurrent use.
 *
 * <p>Call-site patterns mirror the server-side {@code CodeEntityExtractor}
 * to ensure consistency between local and server graph indices.</p>
 */
public class LocalRelationExtractor {

    // --- Call-site patterns (mirrored from server CodeEntityExtractor) ---

    private static final Pattern CALL_BARE = Pattern.compile("\\b(\\w+)\\s*\\(");
    private static final Pattern CALL_DOT = Pattern.compile("\\b(\\w+)\\.(\\w+)\\s*\\(");
    private static final Pattern CALL_SCOPE = Pattern.compile("\\b(\\w+)::(\\w+)\\s*\\(");
    private static final Pattern CALL_ARROW = Pattern.compile("\\b(\\w+)->\\s*(\\w+)\\s*\\(");
    private static final Pattern CALL_RUST_MACRO = Pattern.compile("\\b(\\w+)!\\s*[({\\[]");
    private static final Pattern CALL_PY_DECORATOR = Pattern.compile("^\\s*@(\\w+)");

    private static final Set<String> CALL_KEYWORDS = Set.of(
            "if", "else", "elif", "elseif", "for", "foreach", "while", "do",
            "switch", "case", "match", "when", "select",
            "try", "catch", "finally", "except", "throw", "throws", "raise",
            "return", "break", "continue", "default", "yield", "await",
            "class", "interface", "enum", "struct", "trait", "impl", "record",
            "function", "func", "fun", "def", "fn", "sub", "proc", "lambda",
            "new", "delete", "typeof", "sizeof", "instanceof", "alignof", "nameof",
            "import", "package", "module", "require", "use", "from", "export",
            "public", "private", "protected", "internal", "abstract",
            "static", "final", "const", "let", "var", "val", "mut",
            "synchronized", "volatile", "native", "transient", "override",
            "int", "long", "short", "byte", "char", "float", "double", "boolean",
            "void", "string", "bool", "true", "false", "null", "nil", "none",
            "undefined", "NaN",
            "unless", "until", "loop", "begin", "end", "rescue", "ensure",
            "with", "as", "in", "is", "not", "and", "or",
            "range", "defer", "go", "chan", "map", "make", "append", "copy", "close",
            "unsafe", "where", "move", "ref", "box", "dyn", "async",
            "register", "extern", "inline", "typedef", "union", "auto"
    );

    private static final Set<String> SELF_KEYWORDS = Set.of("this", "self", "super");

    private static final Set<String> CALLABLE_TYPES = Set.of("METHOD", "FUNCTION", "CONSTRUCTOR");

    private LocalRelationExtractor() {}

    /**
     * Extract all relations from a parsed file's entity list.
     *
     * @param filePath   relative path of the source file
     * @param projectId  project identifier
     * @param entities   the full entity list for this file (already parsed by LocalCodeIndexer)
     * @param fileLines  raw source lines (for CALLS scanning)
     * @param language   language string (for language-aware call extraction)
     * @return list of relation maps ready for batch insert into the relations table
     */
    public static List<Map<String, Object>> extract(
            String filePath, String projectId,
            List<Map<String, Object>> entities,
            String[] fileLines,
            String language) {

        List<Map<String, Object>> relations = new ArrayList<>();

        // Build FQN set for this file (used by CONTAINS and CALLS resolution)
        Set<String> fileFqns = entities.stream()
                .map(e -> (String) e.get("fullyQualifiedName"))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Build name→FQN map for same-file call resolution
        Map<String, String> nameToFqn = new LinkedHashMap<>();
        for (Map<String, Object> e : entities) {
            String name = (String) e.get("name");
            String fqn = (String) e.get("fullyQualifiedName");
            if (name != null && fqn != null) {
                nameToFqn.put(name, fqn);
            }
        }

        relations.addAll(extractContains(filePath, projectId, entities, fileFqns));
        relations.addAll(extractExtends(filePath, projectId, entities));
        relations.addAll(extractImplements(filePath, projectId, entities));
        relations.addAll(extractImports(filePath, projectId, entities));
        relations.addAll(extractCalls(filePath, projectId, entities, fileLines, language, nameToFqn));
        relations.addAll(extractSpringRelations(filePath, projectId, entities, fileLines, language));

        return relations;
    }

    // -----------------------------------------------------------------------
    // CONTAINS — parent→child from FQN hierarchy
    // -----------------------------------------------------------------------

    private static List<Map<String, Object>> extractContains(
            String filePath, String projectId,
            List<Map<String, Object>> entities, Set<String> fileFqns) {

        List<Map<String, Object>> rels = new ArrayList<>();

        for (Map<String, Object> entity : entities) {
            String fqn = (String) entity.get("fullyQualifiedName");
            String type = (String) entity.get("entityType");
            if (fqn == null || "FILE".equals(type) || "PACKAGE".equals(type)) continue;

            int line = entity.get("startLine") instanceof Number n ? n.intValue() : 0;

            // Check for explicit parentFqn first (e.g. splan entities)
            String parentFqn = (String) entity.get("parentFqn");
            if (parentFqn != null && !parentFqn.isEmpty()) {
                rels.add(makeRelation(projectId, parentFqn, simpleName(fqn), fqn,
                        "CONTAINS", filePath, line));
                continue;
            }

            // Derive parent from FQN: pkg.Class.method → parent is pkg.Class
            int lastDot = fqn.lastIndexOf('.');
            if (lastDot > 0) {
                String candidateParent = fqn.substring(0, lastDot);
                if (fileFqns.contains(candidateParent)) {
                    rels.add(makeRelation(projectId, candidateParent, simpleName(fqn), fqn,
                            "CONTAINS", filePath, line));
                }
            }

            // FILE → top-level class/interface/enum
            if (Set.of("CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION").contains(type)) {
                // Only emit if no parent was found in the file (i.e., it's top-level)
                if (parentFqn == null || parentFqn.isEmpty()) {
                    int dot = fqn.lastIndexOf('.');
                    String candidate = dot > 0 ? fqn.substring(0, dot) : null;
                    if (candidate == null || !fileFqns.contains(candidate)) {
                        rels.add(makeRelation(projectId, filePath, simpleName(fqn), fqn,
                                "CONTAINS", filePath, line));
                    }
                }
            }
        }
        return rels;
    }

    // -----------------------------------------------------------------------
    // EXTENDS — from inheritedFrom field
    // -----------------------------------------------------------------------

    private static List<Map<String, Object>> extractExtends(
            String filePath, String projectId,
            List<Map<String, Object>> entities) {

        List<Map<String, Object>> rels = new ArrayList<>();
        for (Map<String, Object> entity : entities) {
            String inheritedFrom = (String) entity.get("inheritedFrom");
            if (inheritedFrom == null || inheritedFrom.isBlank()) continue;

            String sourceFqn = (String) entity.get("fullyQualifiedName");
            int line = entity.get("startLine") instanceof Number n ? n.intValue() : 0;

            // May be comma-separated (e.g. Python multiple inheritance)
            for (String target : inheritedFrom.split(",")) {
                target = target.trim();
                if (!target.isEmpty()) {
                    rels.add(makeRelation(projectId, sourceFqn, simpleName(target), target,
                            "EXTENDS", filePath, line));
                }
            }
        }
        return rels;
    }

    // -----------------------------------------------------------------------
    // IMPLEMENTS — from implementsList field
    // -----------------------------------------------------------------------

    private static List<Map<String, Object>> extractImplements(
            String filePath, String projectId,
            List<Map<String, Object>> entities) {

        List<Map<String, Object>> rels = new ArrayList<>();
        for (Map<String, Object> entity : entities) {
            String implementsList = (String) entity.get("implementsList");
            if (implementsList == null || implementsList.isBlank()) continue;

            String sourceFqn = (String) entity.get("fullyQualifiedName");
            int line = entity.get("startLine") instanceof Number n ? n.intValue() : 0;

            for (String iface : implementsList.split(",")) {
                iface = iface.trim();
                if (!iface.isEmpty()) {
                    rels.add(makeRelation(projectId, sourceFqn, simpleName(iface), iface,
                            "IMPLEMENTS", filePath, line));
                }
            }
        }
        return rels;
    }

    // -----------------------------------------------------------------------
    // IMPORTS — from IMPORT-type entities
    // -----------------------------------------------------------------------

    private static List<Map<String, Object>> extractImports(
            String filePath, String projectId,
            List<Map<String, Object>> entities) {

        List<Map<String, Object>> rels = new ArrayList<>();
        for (Map<String, Object> entity : entities) {
            if (!"IMPORT".equals(entity.get("entityType"))) continue;

            String importedFqn = (String) entity.get("fullyQualifiedName");
            if (importedFqn == null || importedFqn.isBlank()) continue;

            int line = entity.get("startLine") instanceof Number n ? n.intValue() : 0;

            rels.add(makeRelation(projectId, filePath, simpleName(importedFqn), importedFqn,
                    "IMPORTS", filePath, line));
        }
        return rels;
    }

    // -----------------------------------------------------------------------
    // CALLS — scan method bodies for call-site patterns
    // -----------------------------------------------------------------------

    private static List<Map<String, Object>> extractCalls(
            String filePath, String projectId,
            List<Map<String, Object>> entities,
            String[] fileLines, String language,
            Map<String, String> nameToFqn) {

        List<Map<String, Object>> rels = new ArrayList<>();

        // Collect callable entities sorted by start line
        List<Map<String, Object>> callables = entities.stream()
                .filter(e -> CALLABLE_TYPES.contains(e.get("entityType")))
                .filter(e -> e.get("startLine") instanceof Number)
                .sorted(Comparator.comparingInt(e -> ((Number) e.get("startLine")).intValue()))
                .collect(Collectors.toList());

        if (callables.isEmpty() || fileLines == null || fileLines.length == 0) return rels;

        // Also collect class/struct start lines to bound method bodies
        List<Integer> boundaryLines = entities.stream()
                .filter(e -> Set.of("CLASS", "INTERFACE", "ENUM", "RECORD", "FUNCTION", "METHOD", "CONSTRUCTOR")
                        .contains(e.get("entityType")))
                .filter(e -> e.get("startLine") instanceof Number)
                .map(e -> ((Number) e.get("startLine")).intValue())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        for (int i = 0; i < callables.size(); i++) {
            Map<String, Object> callable = callables.get(i);
            String callerFqn = (String) callable.get("fullyQualifiedName");
            int startLine = ((Number) callable.get("startLine")).intValue();

            // Heuristic: body runs from startLine to next boundary or file end
            int bodyEnd;
            if (i + 1 < callables.size()) {
                bodyEnd = ((Number) callables.get(i + 1).get("startLine")).intValue() - 1;
            } else {
                bodyEnd = fileLines.length;
            }
            // Clamp
            int bodyStart = Math.min(startLine, fileLines.length); // 1-based
            bodyEnd = Math.min(bodyEnd, fileLines.length);

            // Find parent class FQN for this/self resolution
            String parentClassFqn = findParentClassFqn(callerFqn, entities);

            Set<String> seen = new HashSet<>();

            for (int lineIdx = bodyStart; lineIdx < bodyEnd; lineIdx++) {
                if (lineIdx < 0 || lineIdx >= fileLines.length) continue;
                String line = fileLines[lineIdx];

                // Skip comments
                String trimmed = line.trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("*") ||
                    trimmed.startsWith("/*") || trimmed.startsWith("#")) continue;

                // Track all method names seen via qualified patterns on this line,
                // so bare (unqualified) matches for the same name are suppressed.
                Set<String> lineQualifiedMethods = new HashSet<>();

                // Qualified dot calls: qualifier.method(
                Matcher mDot = CALL_DOT.matcher(line);
                while (mDot.find()) {
                    String qualifier = mDot.group(1);
                    String method = mDot.group(2);
                    if (CALL_KEYWORDS.contains(method)) continue;

                    String resolved;
                    if (SELF_KEYWORDS.contains(qualifier) && parentClassFqn != null) {
                        resolved = parentClassFqn + "." + method;
                    } else {
                        // Try to resolve qualifier via same-file entities
                        String qualFqn = nameToFqn.get(qualifier);
                        resolved = qualFqn != null ? qualFqn + "." + method : method;
                    }

                    if (seen.add("dot:" + qualifier + "." + method)) {
                        rels.add(makeRelation(projectId, callerFqn, method, resolved,
                                "CALLS", filePath, lineIdx + 1));
                    }
                    lineQualifiedMethods.add(method);
                }

                // C++/Rust scope calls: Qualifier::method(
                if ("cpp".equals(language) || "c".equals(language) ||
                    "rust".equals(language)) {
                    Matcher mScope = CALL_SCOPE.matcher(line);
                    while (mScope.find()) {
                        String qualifier = mScope.group(1);
                        String method = mScope.group(2);
                        if (CALL_KEYWORDS.contains(method)) continue;
                        if (seen.add("scope:" + qualifier + "::" + method)) {
                            String resolved = nameToFqn.getOrDefault(method, method);
                            rels.add(makeRelation(projectId, callerFqn, method, resolved,
                                    "CALLS", filePath, lineIdx + 1));
                        }
                        lineQualifiedMethods.add(method);
                    }
                }

                // C++ arrow calls: ptr->method(
                if ("cpp".equals(language) || "c".equals(language)) {
                    Matcher mArrow = CALL_ARROW.matcher(line);
                    while (mArrow.find()) {
                        String method = mArrow.group(2);
                        if (CALL_KEYWORDS.contains(method)) continue;
                        if (seen.add("arrow:" + method)) {
                            String resolved = nameToFqn.getOrDefault(method, method);
                            rels.add(makeRelation(projectId, callerFqn, method, resolved,
                                    "CALLS", filePath, lineIdx + 1));
                        }
                        lineQualifiedMethods.add(method);
                    }
                }

                // Rust macro calls: name!(
                if ("rust".equals(language)) {
                    Matcher mMacro = CALL_RUST_MACRO.matcher(line);
                    while (mMacro.find()) {
                        String macroName = mMacro.group(1);
                        if (!CALL_KEYWORDS.contains(macroName) && seen.add("macro:" + macroName)) {
                            rels.add(makeRelation(projectId, callerFqn, macroName, macroName,
                                    "CALLS", filePath, lineIdx + 1));
                        }
                        lineQualifiedMethods.add(macroName);
                    }
                }

                // Python decorators
                if ("python".equals(language)) {
                    Matcher mDec = CALL_PY_DECORATOR.matcher(line);
                    if (mDec.find()) {
                        String decName = mDec.group(1);
                        if (!CALL_KEYWORDS.contains(decName) && seen.add("dec:" + decName)) {
                            String resolved = nameToFqn.getOrDefault(decName, decName);
                            rels.add(makeRelation(projectId, callerFqn, decName, resolved,
                                    "CALLS", filePath, lineIdx + 1));
                        }
                        lineQualifiedMethods.add(decName);
                    }
                }

                // Bare (unqualified) calls: name(
                // Skip if the method name was already recorded via a qualified pattern
                // on this same line (e.g. embeddingModel.embed(...) should not also
                // produce a bare "embed" relation).
                Matcher mBare = CALL_BARE.matcher(line);
                while (mBare.find()) {
                    String name = mBare.group(1);
                    if (CALL_KEYWORDS.contains(name)) continue;
                    if (name.length() < 2) continue;
                    if (name.matches("[A-Z][A-Z0-9_]+")) continue; // ALL_CAPS constants
                    if (lineQualifiedMethods.contains(name)) continue; // already captured via qualified pattern
                    if (seen.add("bare:" + name)) {
                        String resolved = nameToFqn.getOrDefault(name, name);
                        rels.add(makeRelation(projectId, callerFqn, name, resolved,
                                "CALLS", filePath, lineIdx + 1));
                    }
                }
            }
        }
        return rels;
    }

    // -----------------------------------------------------------------------
    // SPRING DI — extract injection and component relations from annotations
    // -----------------------------------------------------------------------

    private static List<Map<String, Object>> extractSpringRelations(
            String filePath, String projectId,
            List<Map<String, Object>> entities,
            String[] fileLines, String language) {

        // Only process Java/Kotlin/Groovy files
        if (!"java".equals(language) && !"kotlin".equals(language) && !"groovy".equals(language)) {
            return List.of();
        }

        List<Map<String, Object>> rels = new ArrayList<>();

        // Build type name → FQN map from imports
        Map<String, String> importedTypes = new LinkedHashMap<>();
        for (Map<String, Object> e : entities) {
            if ("IMPORT".equals(e.get("entityType"))) {
                String fqn = (String) e.get("fullyQualifiedName");
                if (fqn != null && !fqn.endsWith(".*")) {
                    String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
                    importedTypes.put(simpleName, fqn);
                }
            }
        }

        for (Map<String, Object> entity : entities) {
            String annotations = (String) entity.get("annotations");
            if (annotations == null || annotations.isEmpty()) continue;

            String entityFqn = (String) entity.get("fullyQualifiedName");
            int line = entity.get("startLine") instanceof Number n ? n.intValue() : 0;

            // @Component/@Service/@Repository/@Controller → SPRING_COMPONENT relation
            if (annotations.contains("@Component") || annotations.contains("@Service") ||
                annotations.contains("@Repository") || annotations.contains("@Controller") ||
                annotations.contains("@RestController") || annotations.contains("@Configuration")) {

                // Extract bean name if specified
                String beanName = extractAnnotationValue(annotations, "@Component", "@Service",
                        "@Repository", "@Controller", "@RestController", "@Configuration");

                rels.add(makeRelation(projectId, entityFqn,
                        beanName != null ? beanName : simpleName(entityFqn),
                        entityFqn, "SPRING_COMPONENT", filePath, line));
            }

            // @Primary → SPRING_PRIMARY relation
            if (annotations.contains("@Primary")) {
                rels.add(makeRelation(projectId, entityFqn,
                        simpleName(entityFqn), entityFqn,
                        "SPRING_PRIMARY", filePath, line));
            }

            // @ConditionalOnProperty → SPRING_CONDITIONAL relation
            if (annotations.contains("@ConditionalOn")) {
                rels.add(makeRelation(projectId, entityFqn,
                        extractConditionalValue(annotations),
                        entityFqn, "SPRING_CONDITIONAL", filePath, line));
            }
        }

        // Scan source lines for @Autowired / @Inject field injection patterns
        // Pattern: @Autowired on line N, then field declaration on line N+1 or N+2
        if (fileLines != null) {
            for (int i = 0; i < fileLines.length; i++) {
                String trimmed = fileLines[i].trim();
                if (trimmed.startsWith("@Autowired") || trimmed.startsWith("@Inject")) {
                    // Look at next 1-2 non-annotation lines for the field type
                    for (int j = i + 1; j < Math.min(i + 3, fileLines.length); j++) {
                        String nextLine = fileLines[j].trim();
                        if (nextLine.startsWith("@")) continue; // skip other annotations
                        if (nextLine.isEmpty()) break;

                        // Try to extract injected type: "private TypeName fieldName;"
                        // or constructor parameter: "TypeName paramName"
                        Matcher typeMatcher = Pattern.compile(
                                "(?:private|protected|public)?\\s*(?:final\\s+)?([A-Z]\\w+(?:<[^>]+>)?)\\s+\\w+")
                                .matcher(nextLine);
                        if (typeMatcher.find()) {
                            String injectedType = typeMatcher.group(1);
                            // Strip generics for FQN lookup
                            String baseType = injectedType.contains("<") ?
                                    injectedType.substring(0, injectedType.indexOf('<')) : injectedType;

                            // Find which class this field belongs to
                            String ownerFqn = findOwnerClass(entities, i + 1);

                            if (ownerFqn != null) {
                                String targetFqn = importedTypes.getOrDefault(baseType, baseType);
                                rels.add(makeRelation(projectId, ownerFqn,
                                        baseType, targetFqn,
                                        "SPRING_INJECTS", filePath, i + 1));
                            }
                        }
                        break;
                    }
                }
            }
        }

        return rels;
    }

    /**
     * Extract annotation value from a comma-separated annotations string.
     * E.g., from "@Service(\"myService\"), @Primary" extracts "myService".
     */
    private static String extractAnnotationValue(String annotations, String... annotationNames) {
        for (String name : annotationNames) {
            int idx = annotations.indexOf(name + "(");
            if (idx >= 0) {
                int start = idx + name.length() + 1;
                int end = annotations.indexOf(")", start);
                if (end > start) {
                    String value = annotations.substring(start, end).trim();
                    // Remove quotes
                    value = value.replace("\"", "").replace("'", "");
                    if (!value.isEmpty() && !value.contains("=")) return value;
                }
            }
        }
        return null;
    }

    /**
     * Extract the conditional property/class value from annotations string.
     */
    private static String extractConditionalValue(String annotations) {
        int idx = annotations.indexOf("@ConditionalOn");
        if (idx < 0) return "conditional";
        int parenStart = annotations.indexOf("(", idx);
        int parenEnd = annotations.indexOf(")", parenStart);
        if (parenStart >= 0 && parenEnd > parenStart) {
            return annotations.substring(parenStart + 1, parenEnd).trim();
        }
        return "conditional";
    }

    /**
     * Find the enclosing class FQN for a given line number.
     * Scans class/interface/enum/record entities and returns the one whose
     * startLine is closest to (but not exceeding) the given line.
     */
    private static String findOwnerClass(List<Map<String, Object>> entities, int lineNum) {
        String best = null;
        int bestLine = -1;
        for (Map<String, Object> e : entities) {
            String type = (String) e.get("entityType");
            if (!Set.of("CLASS", "INTERFACE", "ENUM", "RECORD").contains(type)) continue;
            int start = e.get("startLine") instanceof Number n ? n.intValue() : 0;
            if (start > 0 && start <= lineNum && start > bestLine) {
                bestLine = start;
                best = (String) e.get("fullyQualifiedName");
            }
        }
        return best;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Map<String, Object> makeRelation(String projectId, String sourceFqn,
                                                     String targetName, String targetFqn,
                                                     String relationType, String filePath,
                                                     int line) {
        Map<String, Object> rel = new LinkedHashMap<>();
        rel.put("projectId", projectId);
        rel.put("sourceFqn", sourceFqn);
        rel.put("targetName", targetName != null ? targetName : "");
        rel.put("targetFqn", targetFqn);
        rel.put("relationType", relationType);
        rel.put("filePath", filePath);
        rel.put("line", line > 0 ? line : null);
        return rel;
    }

    private static String simpleName(String fqn) {
        if (fqn == null) return "";
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    /**
     * Find the enclosing class FQN for a method entity.
     * Used to resolve this/self calls.
     */
    private static String findParentClassFqn(String methodFqn, List<Map<String, Object>> entities) {
        if (methodFqn == null) return null;
        int lastDot = methodFqn.lastIndexOf('.');
        if (lastDot <= 0) return null;
        String candidate = methodFqn.substring(0, lastDot);

        for (Map<String, Object> e : entities) {
            String type = (String) e.get("entityType");
            if (Set.of("CLASS", "INTERFACE", "ENUM", "RECORD").contains(type)) {
                String fqn = (String) e.get("fullyQualifiedName");
                if (candidate.equals(fqn)) return fqn;
            }
        }
        return null;
    }
}
