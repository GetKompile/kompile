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

package ai.kompile.codeindexer.service.parsers;

import ai.kompile.codeindexer.domain.CodeEntity;
import ai.kompile.codeindexer.domain.CodeEntityType;
import ai.kompile.codeindexer.domain.CodeRelationType;
import ai.kompile.codeindexer.service.CodeEntityExtractor.RelationTriple;
import ai.kompile.codeindexer.service.LanguageParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-based parser for JVM languages: Java, Kotlin, Scala, and Groovy.
 *
 * <p>Java parsing extracts: packages, imports, classes/interfaces/enums/records/
 * annotations, methods (with visibility, static flag, return type, parameters),
 * fields, constructors, and class-level annotations; tracks extends/implements
 * relationships.
 *
 * <p>Kotlin parsing extracts: packages, imports, class/object/data class/sealed class/
 * enum class/interface declarations, function declarations (including suspend funs),
 * val/var properties, and companion objects.
 *
 * <p>Scala parsing extracts: packages, imports, class/object/trait/case class/sealed
 * trait declarations, def declarations, and val/var bindings.
 *
 * <p>Groovy parsing reuses the Java patterns and additionally handles the {@code def}
 * keyword for dynamically-typed methods and fields.
 */
@Component
public class JvmLanguageParser implements LanguageParser {

    private static final Logger log = LoggerFactory.getLogger(JvmLanguageParser.class);

    // -------------------------------------------------------------------------
    // Java / Groovy patterns
    // -------------------------------------------------------------------------

    private static final Pattern JAVA_PACKAGE = Pattern.compile(
            "^\\s*package\\s+([\\w.]+)\\s*;");

    private static final Pattern JAVA_IMPORT = Pattern.compile(
            "^\\s*import\\s+(static\\s+)?([\\w.*]+)\\s*;");

    /**
     * Matches class/interface/enum/record/@interface declarations.
     * Groups:
     *  1 visibility (public|protected|private)
     *  2 "static " (optional)
     *  3 "abstract " (optional)
     *  4 "final " or "sealed " (optional)
     *  5 keyword (class|interface|enum|record|@interface)
     *  6 simple name
     *  7 superclass after "extends" (optional)
     *  8 implemented interfaces after "implements" (optional)
     */
    private static final Pattern JAVA_TYPE = Pattern.compile(
            "^\\s*(public|protected|private)?\\s*" +
            "(static\\s+)?(abstract\\s+)?(final\\s+|sealed\\s+)?" +
            "(class|interface|enum|record|@interface)\\s+(\\w+)" +
            "(?:<[^>]*>)?" +
            "(?:\\([^)]*\\))?" +
            "(?:\\s+extends\\s+([\\w.<>,\\s]+?))?" +
            "(?:\\s+implements\\s+([\\w.<>,\\s]+?))?\\s*(?:\\{|$)");

    /**
     * Matches method declarations (not constructors — those have no return type token).
     * Groups:
     *  1 visibility
     *  2 "static " (optional)
     *  3 "abstract " (optional)
     *  4 return type (first type-like token sequence before method name)
     *  5 method name
     *  6 parameter list (content inside first parentheses pair)
     */
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "^\\s*(public|protected|private)?\\s*(static\\s+)?(abstract\\s+)?" +
            "(?:(?:synchronized|final|native|default)\\s+)*" +
            "(?:<[^>]+>\\s+)?([\\w<>\\[\\].,?\\s]+?)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*" +
            "(?:throws\\s+[\\w.,\\s]+)?\\s*(?:\\{|;|$)");

    /**
     * Matches constructor declarations: visibility + ClassName + '(' params ')'.
     * Groups: 1 visibility, 2 class name, 3 params.
     */
    private static final Pattern JAVA_CONSTRUCTOR = Pattern.compile(
            "^\\s*(public|protected|private)?\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*" +
            "(?:throws\\s+[\\w.,\\s]+)?\\s*\\{");

    /**
     * Matches field declarations.
     * Groups: 1 visibility, 2 "static ", 3 "final ", 4 type, 5 field name.
     */
    private static final Pattern JAVA_FIELD = Pattern.compile(
            "^\\s*(public|protected|private)?\\s*(static\\s+)?(final\\s+)?" +
            "([\\w<>\\[\\].,?]+)\\s+(\\w+)\\s*[=;]");

    /** Matches annotation usage on its own line, e.g. {@code @Override}. */
    private static final Pattern JAVA_ANNOTATION_USE = Pattern.compile(
            "^\\s*@(\\w+)(?:\\([^)]*\\))?\\s*$");

    /** Groovy {@code def} method: def methodName(params) */
    private static final Pattern GROOVY_DEF_METHOD = Pattern.compile(
            "^\\s*(public|protected|private)?\\s*(static\\s+)?def\\s+(\\w+)\\s*\\(([^)]*)\\)");

    /** Groovy {@code def} field: def fieldName [= ...] */
    private static final Pattern GROOVY_DEF_FIELD = Pattern.compile(
            "^\\s*(public|protected|private)?\\s*(static\\s+)?def\\s+(\\w+)\\s*[=;\\n]");

    // -------------------------------------------------------------------------
    // Kotlin patterns
    // -------------------------------------------------------------------------

    private static final Pattern KT_PACKAGE = Pattern.compile(
            "^\\s*package\\s+([\\w.]+)");

    private static final Pattern KT_IMPORT = Pattern.compile(
            "^\\s*import\\s+([\\w.*]+)");

    /**
     * Kotlin type declarations.
     * Groups: 1 visibility, 2 "data "/"sealed "/"abstract "/"open "/"inner " (optional),
     *  3 keyword (class|object|interface|enum class), 4 simple name,
     *  5 supertype/constructor delegation (optional).
     */
    private static final Pattern KT_TYPE = Pattern.compile(
            "^\\s*(public|private|protected|internal)?\\s*" +
            "(data\\s+|sealed\\s+|abstract\\s+|open\\s+|inner\\s+|enum\\s+)?" +
            "(class|object|interface)\\s+(\\w+)" +
            "(?:<[^>]*>)?" +
            "(?:\\([^)]*\\))?" +
            "(?:[^:]*:\\s*([\\w<>(),\\s.]+?))?\\s*(?:\\{|\\(|$)");

    /**
     * Kotlin function declaration.
     * Groups: 1 visibility, 2 "suspend " (optional), 3 "override " (optional),
     *  4 function name, 5 params.
     */
    private static final Pattern KT_FUN = Pattern.compile(
            "^\\s*(public|private|protected|internal)?\\s*(override\\s+)?(suspend\\s+)?" +
            "(?:inline\\s+|tailrec\\s+|operator\\s+|infix\\s+|external\\s+|open\\s+|abstract\\s+)?" +
            "fun\\s+(?:<[^>]+>\\s+)?(?:[\\w.]+\\.)?(" + "\\w+)\\s*\\(([^)]*)\\)");

    /** Kotlin val/var property. Groups: 1 visibility, 2 val/var, 3 name, 4 type (optional). */
    private static final Pattern KT_PROPERTY = Pattern.compile(
            "^\\s*(public|private|protected|internal)?\\s*(override\\s+)?" +
            "(val|var)\\s+(\\w+)\\s*(?::\\s*([\\w<>?,\\s.]+?))?\\s*[=\\n{]");

    /** Companion object. */
    private static final Pattern KT_COMPANION = Pattern.compile(
            "^\\s*(private\\s+)?companion\\s+object(?:\\s+(\\w+))?");

    // -------------------------------------------------------------------------
    // Scala patterns
    // -------------------------------------------------------------------------

    private static final Pattern SCALA_PACKAGE = Pattern.compile(
            "^\\s*package\\s+([\\w.]+)");

    private static final Pattern SCALA_IMPORT = Pattern.compile(
            "^\\s*import\\s+([\\w.{}*,\\s]+)");

    /**
     * Scala type declarations.
     * Groups: 1 modifiers (optional), 2 keyword (class|object|trait|case class|sealed trait),
     *  3 simple name, 4 extends clause (optional).
     */
    private static final Pattern SCALA_TYPE = Pattern.compile(
            "^\\s*((?:(?:private|protected|abstract|sealed|final|case|implicit|lazy)\\s+)*)?" +
            "(class|object|trait)\\s+(\\w+)" +
            "(?:[^{]*extends\\s+([\\w.<>(),\\s]+?))?\\s*(?:\\{|$)");

    /**
     * Scala def declaration.
     * Groups: 1 visibility, 2 name, 3 params.
     */
    private static final Pattern SCALA_DEF = Pattern.compile(
            "^\\s*((?:(?:private|protected|override|implicit|lazy|final)\\s+)*)?" +
            "def\\s+(\\w+)\\s*(?:\\(([^)]*)\\))?");

    /** Scala val/var. Groups: 1 modifiers, 2 val/var, 3 name. */
    private static final Pattern SCALA_VAL_VAR = Pattern.compile(
            "^\\s*((?:(?:private|protected|override|lazy|implicit|final)\\s+)*)?" +
            "(val|var)\\s+(\\w+)");

    // -------------------------------------------------------------------------
    // LanguageParser contract
    // -------------------------------------------------------------------------

    @Override
    public Set<String> supportedLanguages() {
        return Set.of("java", "kotlin", "scala", "groovy");
    }

    @Override
    public ExtractionOutput parse(String[] lines, String filePath, String projectId, String language) {
        List<CodeEntity> entities = new ArrayList<>();
        List<RelationTriple> relations = new ArrayList<>();

        try {
            switch (language) {
                case "java"   -> extractJava(lines, filePath, projectId, entities, relations, false);
                case "groovy" -> extractJava(lines, filePath, projectId, entities, relations, true);
                case "kotlin" -> extractKotlin(lines, filePath, projectId, entities, relations);
                case "scala"  -> extractScala(lines, filePath, projectId, entities, relations);
                default       -> log.warn("JvmLanguageParser invoked for unsupported language: {}", language);
            }
        } catch (Exception e) {
            log.warn("Error parsing {} ({}): {}", filePath, language, e.getMessage(), e);
        }

        return new ExtractionOutput(entities, relations);
    }

    // =========================================================================
    // Java / Groovy extraction
    // =========================================================================

    private void extractJava(String[] lines, String filePath, String projectId,
                             List<CodeEntity> entities, List<RelationTriple> relations,
                             boolean groovy) {

        String packageName = null;
        // Stack so we can handle nested types properly (simplified: track outermost)
        String currentClassFqn = null;
        String currentClassName = null;
        StringBuilder docComment = null;
        boolean inDocComment = false;
        String pendingAnnotation = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Skip blank lines
            if (trimmed.isEmpty()) {
                continue;
            }

            // Doc-comment accumulation
            if (trimmed.startsWith("/**")) {
                docComment = new StringBuilder(line).append('\n');
                inDocComment = !trimmed.contains("*/");
                continue;
            }
            if (inDocComment) {
                if (docComment != null) docComment.append(line).append('\n');
                if (trimmed.contains("*/")) inDocComment = false;
                continue;
            }

            // Single-line comments
            if (trimmed.startsWith("//")) {
                continue;
            }

            // Annotation tracking (for annotated class/method detection)
            Matcher am = JAVA_ANNOTATION_USE.matcher(line);
            if (am.find()) {
                pendingAnnotation = am.group(1);
                continue;
            }

            // Package
            Matcher m = JAVA_PACKAGE.matcher(line);
            if (m.find()) {
                packageName = m.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.PACKAGE)
                        .name(packageName)
                        .fullyQualifiedName(packageName)
                        .filePath(filePath)
                        .language(groovy ? "groovy" : "java")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .build());
                relations.add(new RelationTriple(filePath, packageName, CodeRelationType.CONTAINS));
                pendingAnnotation = null;
                docComment = null;
                continue;
            }

            // Import
            m = JAVA_IMPORT.matcher(line);
            if (m.find()) {
                String imported = m.group(2);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.IMPORT)
                        .name(imported)
                        .fullyQualifiedName(imported)
                        .filePath(filePath)
                        .language(groovy ? "groovy" : "java")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .metadataJson(buildImportMetadata(imported))
                        .build());
                relations.add(new RelationTriple(filePath, imported, CodeRelationType.IMPORTS));
                pendingAnnotation = null;
                docComment = null;
                continue;
            }

            // Type declaration (class/interface/enum/record/@interface)
            m = JAVA_TYPE.matcher(line);
            if (m.find()) {
                String visibility  = m.group(1);
                boolean isStatic   = m.group(2) != null;
                boolean isAbstract = m.group(3) != null;
                String modifier4   = m.group(4); // "final " or "sealed "
                boolean isFinal    = modifier4 != null && modifier4.trim().equals("final");
                String keyword     = m.group(5);
                String simpleName  = m.group(6);
                String superclass  = m.group(7) != null ? m.group(7).trim() : null;
                String implClause  = m.group(8) != null ? m.group(8).trim() : null;

                CodeEntityType type = switch (keyword) {
                    case "interface"  -> CodeEntityType.INTERFACE;
                    case "enum"       -> CodeEntityType.ENUM;
                    case "record"     -> CodeEntityType.RECORD;
                    case "@interface" -> CodeEntityType.ANNOTATION;
                    default           -> CodeEntityType.CLASS;
                };

                currentClassName  = simpleName;
                currentClassFqn   = (packageName != null ? packageName + "." : "") + simpleName;
                int endLine       = findBlockEnd(lines, i);

                // Build metadataJson for cross-file resolution
                String classMeta = buildTypeMetadata(superclass, implClause);

                CodeEntity classEntity = CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(type)
                        .name(simpleName)
                        .fullyQualifiedName(currentClassFqn)
                        .filePath(filePath)
                        .language(groovy ? "groovy" : "java")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(trimmed)
                        .docComment(docComment != null ? docComment.toString().trim() : null)
                        .contentPreview(buildPreview(lines, i, endLine, 500))
                        .packageName(packageName)
                        .parentFqn(filePath)
                        .visibility(visibility)
                        .isStatic(isStatic)
                        .isAbstract(isAbstract)
                        .metadataJson(classMeta)
                        .build();
                entities.add(classEntity);

                relations.add(new RelationTriple(filePath, currentClassFqn, CodeRelationType.CONTAINS));

                if (superclass != null && !superclass.isEmpty()) {
                    for (String sc : superclass.split(",")) {
                        String scTrimmed = sc.trim();
                        if (!scTrimmed.isEmpty()) {
                            relations.add(new RelationTriple(currentClassFqn, scTrimmed, CodeRelationType.EXTENDS));
                        }
                    }
                }
                if (implClause != null && !implClause.isEmpty()) {
                    for (String iface : implClause.split(",")) {
                        String ifaceTrimmed = iface.trim();
                        if (!ifaceTrimmed.isEmpty()) {
                            relations.add(new RelationTriple(currentClassFqn, ifaceTrimmed, CodeRelationType.IMPLEMENTS));
                        }
                    }
                }
                if (pendingAnnotation != null) {
                    relations.add(new RelationTriple(currentClassFqn, pendingAnnotation, CodeRelationType.ANNOTATED_BY));
                }

                pendingAnnotation = null;
                docComment = null;
                continue;
            }

            // Everything below requires an enclosing type
            if (currentClassFqn == null) {
                pendingAnnotation = null;
                continue;
            }

            // Groovy: def method
            if (groovy) {
                m = GROOVY_DEF_METHOD.matcher(line);
                if (m.find()) {
                    String visibility = m.group(1);
                    boolean isStatic  = m.group(2) != null;
                    String methodName = m.group(3);
                    String params     = m.group(4);
                    String methodFqn  = currentClassFqn + "." + methodName;
                    int endLine       = findBlockEnd(lines, i);

                    entities.add(CodeEntity.builder()
                            .projectId(projectId)
                            .entityType(CodeEntityType.METHOD)
                            .name(methodName)
                            .fullyQualifiedName(methodFqn)
                            .filePath(filePath)
                            .language("groovy")
                            .startLine(i + 1)
                            .endLine(endLine + 1)
                            .signature("def " + methodName + "(" + params + ")")
                            .docComment(docComment != null ? docComment.toString().trim() : null)
                            .contentPreview(buildPreview(lines, i, endLine, 500))
                            .packageName(packageName)
                            .parentFqn(currentClassFqn)
                            .visibility(visibility)
                            .isStatic(isStatic)
                            .build());

                    relations.add(new RelationTriple(currentClassFqn, methodFqn, CodeRelationType.CONTAINS));
                    if (pendingAnnotation != null) {
                        relations.add(new RelationTriple(methodFqn, pendingAnnotation, CodeRelationType.ANNOTATED_BY));
                    }
                    extractCallSites(lines, i, endLine, methodFqn, filePath, relations);
                    pendingAnnotation = null;
                    docComment = null;
                    continue;
                }

                // Groovy: def field
                m = GROOVY_DEF_FIELD.matcher(line);
                if (m.find()) {
                    String visibility = m.group(1);
                    boolean isStatic  = m.group(2) != null;
                    String fieldName  = m.group(3);
                    // Heuristic: if this line also contains '(' it's more likely a method, skip
                    if (!line.contains("(")) {
                        String fieldFqn = currentClassFqn + "." + fieldName;
                        entities.add(CodeEntity.builder()
                                .projectId(projectId)
                                .entityType(CodeEntityType.FIELD)
                                .name(fieldName)
                                .fullyQualifiedName(fieldFqn)
                                .filePath(filePath)
                                .language("groovy")
                                .startLine(i + 1)
                                .endLine(i + 1)
                                .signature("def " + fieldName)
                                .docComment(docComment != null ? docComment.toString().trim() : null)
                                .parentFqn(currentClassFqn)
                                .visibility(visibility)
                                .isStatic(isStatic)
                                .build());

                        relations.add(new RelationTriple(currentClassFqn, fieldFqn, CodeRelationType.CONTAINS));
                        pendingAnnotation = null;
                        docComment = null;
                        continue;
                    }
                }
            }

            // Constructor (must come before method to avoid misclassification)
            m = JAVA_CONSTRUCTOR.matcher(line);
            if (m.find()) {
                String visibility   = m.group(1);
                String ctorName     = m.group(2);
                String params       = m.group(3);
                // Only match if constructor name matches the current class name
                if (ctorName.equals(currentClassName)) {
                    String ctorFqn = currentClassFqn + ".<init>";
                    int endLine    = findBlockEnd(lines, i);

                    entities.add(CodeEntity.builder()
                            .projectId(projectId)
                            .entityType(CodeEntityType.CONSTRUCTOR)
                            .name(ctorName)
                            .fullyQualifiedName(ctorFqn)
                            .filePath(filePath)
                            .language(groovy ? "groovy" : "java")
                            .startLine(i + 1)
                            .endLine(endLine + 1)
                            .signature(ctorName + "(" + params + ")")
                            .docComment(docComment != null ? docComment.toString().trim() : null)
                            .contentPreview(buildPreview(lines, i, endLine, 500))
                            .packageName(packageName)
                            .parentFqn(currentClassFqn)
                            .visibility(visibility)
                            .isStatic(false)
                            .build());

                    relations.add(new RelationTriple(currentClassFqn, ctorFqn, CodeRelationType.CONTAINS));
                    if (pendingAnnotation != null) {
                        relations.add(new RelationTriple(ctorFqn, pendingAnnotation, CodeRelationType.ANNOTATED_BY));
                    }
                    extractCallSites(lines, i, endLine, ctorFqn, filePath, relations);
                    pendingAnnotation = null;
                    docComment = null;
                    continue;
                }
            }

            // Method
            if (!trimmed.startsWith("return") && !trimmed.startsWith("throw")) {
                m = JAVA_METHOD.matcher(line);
                if (m.find()) {
                    String visibility = m.group(1);
                    boolean isStatic  = m.group(2) != null;
                    boolean isAbstract = m.group(3) != null;
                    String returnType = m.group(4) != null ? m.group(4).trim() : "void";
                    String methodName = m.group(5);
                    String params     = m.group(6);

                    // Exclude obvious non-method matches (e.g. "if", "while", "for", "new")
                    if (!isKeyword(methodName) && !isKeyword(returnType.split("\\s+")[0])) {
                        String methodFqn = currentClassFqn + "." + methodName;
                        int endLine      = findBlockEnd(lines, i);

                        entities.add(CodeEntity.builder()
                                .projectId(projectId)
                                .entityType(CodeEntityType.METHOD)
                                .name(methodName)
                                .fullyQualifiedName(methodFqn)
                                .filePath(filePath)
                                .language(groovy ? "groovy" : "java")
                                .startLine(i + 1)
                                .endLine(endLine + 1)
                                .signature(returnType + " " + methodName + "(" + params + ")")
                                .docComment(docComment != null ? docComment.toString().trim() : null)
                                .contentPreview(buildPreview(lines, i, endLine, 500))
                                .packageName(packageName)
                                .parentFqn(currentClassFqn)
                                .visibility(visibility)
                                .isStatic(isStatic)
                                .isAbstract(isAbstract)
                                .build());

                        relations.add(new RelationTriple(currentClassFqn, methodFqn, CodeRelationType.CONTAINS));
                        if (pendingAnnotation != null) {
                            relations.add(new RelationTriple(methodFqn, pendingAnnotation, CodeRelationType.ANNOTATED_BY));
                        }
                        extractCallSites(lines, i, endLine, methodFqn, filePath, relations);
                        pendingAnnotation = null;
                        docComment = null;
                        continue;
                    }
                }
            }

            // Field
            if (!trimmed.startsWith("return") && !trimmed.startsWith("throw") && !trimmed.contains("(")) {
                m = JAVA_FIELD.matcher(line);
                if (m.find()) {
                    String visibility = m.group(1);
                    boolean isStatic  = m.group(2) != null;
                    boolean isFinal   = m.group(3) != null;
                    String fieldType  = m.group(4);
                    String fieldName  = m.group(5);

                    if (!isKeyword(fieldName) && !isKeyword(fieldType)) {
                        String fieldFqn = currentClassFqn + "." + fieldName;
                        CodeEntityType fieldEntityType = isFinal && isStatic
                                ? CodeEntityType.CONSTANT
                                : CodeEntityType.FIELD;

                        entities.add(CodeEntity.builder()
                                .projectId(projectId)
                                .entityType(fieldEntityType)
                                .name(fieldName)
                                .fullyQualifiedName(fieldFqn)
                                .filePath(filePath)
                                .language(groovy ? "groovy" : "java")
                                .startLine(i + 1)
                                .endLine(i + 1)
                                .signature(fieldType + " " + fieldName)
                                .docComment(docComment != null ? docComment.toString().trim() : null)
                                .parentFqn(currentClassFqn)
                                .visibility(visibility)
                                .isStatic(isStatic)
                                .build());

                        relations.add(new RelationTriple(currentClassFqn, fieldFqn, CodeRelationType.CONTAINS));
                        relations.add(new RelationTriple(fieldFqn, fieldType, CodeRelationType.FIELD_TYPE));
                        pendingAnnotation = null;
                        docComment = null;
                        continue;
                    }
                }
            }

            // Clear accumulated state on non-matching non-annotation lines
            if (!trimmed.startsWith("@") && !trimmed.startsWith("//")) {
                pendingAnnotation = null;
                if (!inDocComment) docComment = null;
            }
        }
    }

    // =========================================================================
    // Kotlin extraction
    // =========================================================================

    private void extractKotlin(String[] lines, String filePath, String projectId,
                                List<CodeEntity> entities, List<RelationTriple> relations) {

        String packageName = null;
        String currentClassFqn = null;
        StringBuilder docComment = null;
        boolean inDocComment = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.isEmpty()) continue;

            // Doc-comment
            if (trimmed.startsWith("/**")) {
                docComment = new StringBuilder(line).append('\n');
                inDocComment = !trimmed.contains("*/");
                continue;
            }
            if (inDocComment) {
                if (docComment != null) docComment.append(line).append('\n');
                if (trimmed.contains("*/")) inDocComment = false;
                continue;
            }
            if (trimmed.startsWith("//")) continue;

            // Package
            Matcher m = KT_PACKAGE.matcher(line);
            if (m.find()) {
                packageName = m.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.PACKAGE)
                        .name(packageName)
                        .fullyQualifiedName(packageName)
                        .filePath(filePath)
                        .language("kotlin")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .build());
                relations.add(new RelationTriple(filePath, packageName, CodeRelationType.CONTAINS));
                docComment = null;
                continue;
            }

            // Import
            m = KT_IMPORT.matcher(line);
            if (m.find()) {
                String imported = m.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.IMPORT)
                        .name(imported)
                        .fullyQualifiedName(imported)
                        .filePath(filePath)
                        .language("kotlin")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .metadataJson(buildImportMetadata(imported))
                        .build());
                relations.add(new RelationTriple(filePath, imported, CodeRelationType.IMPORTS));
                docComment = null;
                continue;
            }

            // Companion object
            m = KT_COMPANION.matcher(line);
            if (m.find() && currentClassFqn != null) {
                String companionName = m.group(2) != null ? m.group(2) : "Companion";
                String companionFqn  = currentClassFqn + "." + companionName;
                int endLine          = findBlockEnd(lines, i);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.CLASS)
                        .name(companionName)
                        .fullyQualifiedName(companionFqn)
                        .filePath(filePath)
                        .language("kotlin")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(trimmed)
                        .parentFqn(currentClassFqn)
                        .isStatic(true)
                        .build());
                relations.add(new RelationTriple(currentClassFqn, companionFqn, CodeRelationType.CONTAINS));
                docComment = null;
                continue;
            }

            // Type declaration (class/object/interface/data class/sealed class/enum class)
            m = KT_TYPE.matcher(line);
            if (m.find()) {
                String visibility  = m.group(1);
                String modifier    = m.group(2) != null ? m.group(2).trim() : null;
                String keyword     = m.group(3);
                String simpleName  = m.group(4);
                String supertypes  = m.group(5);

                CodeEntityType type = switch (keyword) {
                    case "interface" -> CodeEntityType.INTERFACE;
                    case "object"    -> CodeEntityType.CLASS; // Kotlin object = singleton class
                    default          -> {
                        if ("enum".equals(modifier)) yield CodeEntityType.ENUM;
                        yield CodeEntityType.CLASS;
                    }
                };

                currentClassFqn = (packageName != null ? packageName + "." : "") + simpleName;
                int endLine     = findBlockEnd(lines, i);

                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(type)
                        .name(simpleName)
                        .fullyQualifiedName(currentClassFqn)
                        .filePath(filePath)
                        .language("kotlin")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(trimmed)
                        .docComment(docComment != null ? docComment.toString().trim() : null)
                        .contentPreview(buildPreview(lines, i, endLine, 500))
                        .packageName(packageName)
                        .parentFqn(filePath)
                        .visibility(visibility)
                        .isAbstract("abstract".equals(modifier))
                        .metadataJson(buildTypeMetadata(supertypes, null))
                        .build());

                relations.add(new RelationTriple(filePath, currentClassFqn, CodeRelationType.CONTAINS));

                if (supertypes != null && !supertypes.isEmpty()) {
                    for (String st : supertypes.split(",")) {
                        String stTrimmed = st.trim().replaceAll("\\(.*\\)", "").trim();
                        if (!stTrimmed.isEmpty()) {
                            relations.add(new RelationTriple(currentClassFqn, stTrimmed, CodeRelationType.EXTENDS));
                        }
                    }
                }

                docComment = null;
                continue;
            }

            // Skip lines outside of a type
            if (currentClassFqn == null) {
                if (!trimmed.startsWith("@")) docComment = null;
                continue;
            }

            // Function
            m = KT_FUN.matcher(line);
            if (m.find()) {
                String visibility  = m.group(1);
                boolean isOverride = m.group(2) != null;
                boolean isSuspend  = m.group(3) != null;
                String funName     = m.group(4);
                String params      = m.group(5);
                String funFqn      = currentClassFqn + "." + funName;
                int endLine        = findBlockEnd(lines, i);

                String sig = (isSuspend ? "suspend " : "") + "fun " + funName + "(" + params + ")";
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.METHOD)
                        .name(funName)
                        .fullyQualifiedName(funFqn)
                        .filePath(filePath)
                        .language("kotlin")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(sig)
                        .docComment(docComment != null ? docComment.toString().trim() : null)
                        .contentPreview(buildPreview(lines, i, endLine, 500))
                        .packageName(packageName)
                        .parentFqn(currentClassFqn)
                        .visibility(visibility)
                        .isStatic(false)
                        .isAbstract(false)
                        .build());

                relations.add(new RelationTriple(currentClassFqn, funFqn, CodeRelationType.CONTAINS));
                if (isOverride) {
                    relations.add(new RelationTriple(funFqn, funFqn, CodeRelationType.OVERRIDES));
                }
                extractCallSites(lines, i, endLine, funFqn, filePath, relations);
                docComment = null;
                continue;
            }

            // Property (val/var)
            m = KT_PROPERTY.matcher(line);
            if (m.find()) {
                String visibility = m.group(1);
                String valVar     = m.group(3);
                String propName   = m.group(4);
                String propType   = m.group(5);
                String propFqn    = currentClassFqn + "." + propName;
                boolean isFinal   = "val".equals(valVar);

                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.FIELD)
                        .name(propName)
                        .fullyQualifiedName(propFqn)
                        .filePath(filePath)
                        .language("kotlin")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature(valVar + " " + propName + (propType != null ? ": " + propType : ""))
                        .docComment(docComment != null ? docComment.toString().trim() : null)
                        .parentFqn(currentClassFqn)
                        .visibility(visibility)
                        .isStatic(false)
                        .build());

                relations.add(new RelationTriple(currentClassFqn, propFqn, CodeRelationType.CONTAINS));
                if (propType != null && !propType.isEmpty()) {
                    relations.add(new RelationTriple(propFqn, propType.trim(), CodeRelationType.FIELD_TYPE));
                }
                docComment = null;
                continue;
            }

            if (!trimmed.startsWith("@") && !trimmed.startsWith("//")) {
                if (!inDocComment) docComment = null;
            }
        }
    }

    // =========================================================================
    // Scala extraction
    // =========================================================================

    private void extractScala(String[] lines, String filePath, String projectId,
                               List<CodeEntity> entities, List<RelationTriple> relations) {

        String packageName = null;
        String currentClassFqn = null;
        StringBuilder docComment = null;
        boolean inDocComment = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.isEmpty()) continue;

            // Doc-comment
            if (trimmed.startsWith("/**")) {
                docComment = new StringBuilder(line).append('\n');
                inDocComment = !trimmed.contains("*/");
                continue;
            }
            if (inDocComment) {
                if (docComment != null) docComment.append(line).append('\n');
                if (trimmed.contains("*/")) inDocComment = false;
                continue;
            }
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue;

            // Package
            Matcher m = SCALA_PACKAGE.matcher(line);
            if (m.find()) {
                packageName = m.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.PACKAGE)
                        .name(packageName)
                        .fullyQualifiedName(packageName)
                        .filePath(filePath)
                        .language("scala")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .build());
                relations.add(new RelationTriple(filePath, packageName, CodeRelationType.CONTAINS));
                docComment = null;
                continue;
            }

            // Import
            m = SCALA_IMPORT.matcher(line);
            if (m.find()) {
                String imported = m.group(1).trim();
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.IMPORT)
                        .name(imported)
                        .fullyQualifiedName(imported)
                        .filePath(filePath)
                        .language("scala")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .metadataJson(buildImportMetadata(imported))
                        .build());
                relations.add(new RelationTriple(filePath, imported, CodeRelationType.IMPORTS));
                docComment = null;
                continue;
            }

            // Type declaration
            m = SCALA_TYPE.matcher(line);
            if (m.find()) {
                String modifiers  = m.group(1) != null ? m.group(1).trim() : "";
                String keyword    = m.group(2);
                String simpleName = m.group(3);
                String extendsClause = m.group(4);

                boolean isAbstract = modifiers.contains("abstract");
                boolean isCase     = modifiers.contains("case");
                boolean isSealed   = modifiers.contains("sealed");

                // Scala: first part is extends, rest are with (implements)
                String scalaExtends = null;
                String scalaImpls = null;
                if (extendsClause != null && !extendsClause.isEmpty()) {
                    String[] parts = extendsClause.split("(?<!\\w)with(?!\\w)");
                    if (parts.length > 0) scalaExtends = parts[0].trim().replaceAll("\\(.*\\)", "").trim();
                    if (parts.length > 1) {
                        StringBuilder sb2 = new StringBuilder();
                        for (int p = 1; p < parts.length; p++) {
                            if (sb2.length() > 0) sb2.append(",");
                            sb2.append(parts[p].trim().replaceAll("\\(.*\\)", "").trim());
                        }
                        scalaImpls = sb2.toString();
                    }
                }

                CodeEntityType type = switch (keyword) {
                    case "trait"  -> CodeEntityType.INTERFACE;
                    case "object" -> CodeEntityType.CLASS;
                    default       -> CodeEntityType.CLASS;
                };

                currentClassFqn = (packageName != null ? packageName + "." : "") + simpleName;
                int endLine     = findBlockEnd(lines, i);

                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(type)
                        .name(simpleName)
                        .fullyQualifiedName(currentClassFqn)
                        .filePath(filePath)
                        .language("scala")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(trimmed)
                        .docComment(docComment != null ? docComment.toString().trim() : null)
                        .contentPreview(buildPreview(lines, i, endLine, 500))
                        .packageName(packageName)
                        .parentFqn(filePath)
                        .isAbstract(isAbstract)
                        .metadataJson(buildTypeMetadata(scalaExtends, scalaImpls))
                        .build());

                relations.add(new RelationTriple(filePath, currentClassFqn, CodeRelationType.CONTAINS));

                if (extendsClause != null && !extendsClause.isEmpty()) {
                    // Scala: "extends A with B with C"
                    String[] parts = extendsClause.split("(?<!\\w)with(?!\\w)");
                    for (int p = 0; p < parts.length; p++) {
                        String part = parts[p].trim().replaceAll("\\(.*\\)", "").trim();
                        if (!part.isEmpty()) {
                            CodeRelationType rel = (p == 0) ? CodeRelationType.EXTENDS : CodeRelationType.IMPLEMENTS;
                            relations.add(new RelationTriple(currentClassFqn, part, rel));
                        }
                    }
                }

                docComment = null;
                continue;
            }

            if (currentClassFqn == null) {
                if (!trimmed.startsWith("@")) docComment = null;
                continue;
            }

            // def declaration
            m = SCALA_DEF.matcher(line);
            if (m.find()) {
                String mods    = m.group(1) != null ? m.group(1).trim() : "";
                String defName = m.group(2);
                String params  = m.group(3) != null ? m.group(3) : "";
                String defFqn  = currentClassFqn + "." + defName;
                int endLine    = findBlockEnd(lines, i);

                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.METHOD)
                        .name(defName)
                        .fullyQualifiedName(defFqn)
                        .filePath(filePath)
                        .language("scala")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature("def " + defName + "(" + params + ")")
                        .docComment(docComment != null ? docComment.toString().trim() : null)
                        .contentPreview(buildPreview(lines, i, endLine, 500))
                        .packageName(packageName)
                        .parentFqn(currentClassFqn)
                        .isAbstract(mods.contains("abstract"))
                        .build());

                relations.add(new RelationTriple(currentClassFqn, defFqn, CodeRelationType.CONTAINS));
                if (mods.contains("override")) {
                    relations.add(new RelationTriple(defFqn, defFqn, CodeRelationType.OVERRIDES));
                }
                extractCallSites(lines, i, endLine, defFqn, filePath, relations);
                docComment = null;
                continue;
            }

            // val/var binding
            m = SCALA_VAL_VAR.matcher(line);
            if (m.find()) {
                String mods     = m.group(1) != null ? m.group(1).trim() : "";
                String valVar   = m.group(2);
                String valName  = m.group(3);
                String valFqn   = currentClassFqn + "." + valName;
                boolean isFinal = "val".equals(valVar) || mods.contains("final");

                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.FIELD)
                        .name(valName)
                        .fullyQualifiedName(valFqn)
                        .filePath(filePath)
                        .language("scala")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature(valVar + " " + valName)
                        .docComment(docComment != null ? docComment.toString().trim() : null)
                        .parentFqn(currentClassFqn)
                        .build());

                relations.add(new RelationTriple(currentClassFqn, valFqn, CodeRelationType.CONTAINS));
                docComment = null;
                continue;
            }

            if (!trimmed.startsWith("@") && !trimmed.startsWith("//")) {
                if (!inDocComment) docComment = null;
            }
        }
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    /**
     * Finds the line index of the closing brace that matches the first opening
     * brace at or after {@code startLine}.  Returns a safe fallback if no
     * matching brace is found within the file.
     *
     * @param lines     source lines array
     * @param startLine index of the line where the block header begins
     * @return 0-based line index of the closing '}', or a best-effort estimate
     */
    int findBlockEnd(String[] lines, int startLine) {
        int braceCount = 0;
        boolean foundOpen = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = startLine; i < lines.length; i++) {
            String l = lines[i];
            inLineComment = false;
            for (int j = 0; j < l.length(); j++) {
                char c = l.charAt(j);
                char next = (j + 1 < l.length()) ? l.charAt(j + 1) : 0;

                if (inBlockComment) {
                    if (c == '*' && next == '/') {
                        inBlockComment = false;
                        j++; // skip '/'
                    }
                    continue;
                }
                if (inLineComment) break;

                if (c == '/' && next == '/') {
                    inLineComment = true;
                    break;
                }
                if (c == '/' && next == '*') {
                    inBlockComment = true;
                    j++;
                    continue;
                }
                // Ignore characters inside string literals (simple approximation)
                if (c == '"') {
                    j++;
                    while (j < l.length() && l.charAt(j) != '"') {
                        if (l.charAt(j) == '\\') j++; // skip escape
                        j++;
                    }
                    continue;
                }
                if (c == '\'') {
                    j++;
                    while (j < l.length() && l.charAt(j) != '\'') {
                        if (l.charAt(j) == '\\') j++;
                        j++;
                    }
                    continue;
                }

                if (c == '{') {
                    braceCount++;
                    foundOpen = true;
                } else if (c == '}') {
                    braceCount--;
                    if (foundOpen && braceCount == 0) return i;
                }
            }
        }
        // No matching brace found — fall back to a reasonable limit
        return Math.min(startLine + 100, lines.length - 1);
    }

    /**
     * Build a content preview from source lines, starting at {@code startLine}
     * up to {@code endLine}, capped at {@code maxChars} characters.
     */
    static String buildPreview(String[] lines, int startLine, int endLine, int maxChars) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(endLine + 1, lines.length);
        for (int i = startLine; i < limit && sb.length() < maxChars; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        if (sb.length() > maxChars) {
            return sb.substring(0, maxChars);
        }
        return sb.toString();
    }

    /**
     * Builds a JSON metadata string with extendsType and implementsTypes keys
     * for cross-file graph edge resolution by CodeGraphBuilder.
     */
    private static String buildTypeMetadata(String superclass, String implementsClause) {
        String extendsType = null;
        List<String> implTypes = new ArrayList<>();

        if (superclass != null && !superclass.isBlank()) {
            String first = superclass.split(",")[0].trim();
            if (!first.isEmpty()) extendsType = first;
        }
        if (implementsClause != null && !implementsClause.isBlank()) {
            for (String iface : implementsClause.split(",")) {
                String t = iface.trim();
                if (!t.isEmpty()) implTypes.add(t);
            }
        }
        if (extendsType == null && implTypes.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("{");
        if (extendsType != null) {
            sb.append("\"extendsType\":\"").append(escapeJson(extendsType)).append("\"");
            if (!implTypes.isEmpty()) sb.append(",");
        }
        if (!implTypes.isEmpty()) {
            sb.append("\"implementsTypes\":[");
            for (int j = 0; j < implTypes.size(); j++) {
                if (j > 0) sb.append(",");
                sb.append("\"").append(escapeJson(implTypes.get(j))).append("\"");
            }
            sb.append("]");
        }
        sb.append("}");
        return sb.toString();
    }

    /** Builds a JSON metadata string with importedType key for cross-file resolution. */
    private static String buildImportMetadata(String importedFqn) {
        if (importedFqn == null || importedFqn.isBlank()) return null;
        return "{\"importedType\":\"" + escapeJson(importedFqn.trim()) + "\"}";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Scan a method body for call-site patterns and emit CALLS relations.
     * Matches: {@code identifier.methodName(} and standalone {@code methodName(}.
     * Skips keywords, constructors (new Xxx), and string literals.
     */
    private static final Pattern CALL_SITE = Pattern.compile(
            "(?:(?:[a-zA-Z_]\\w*)\\.)?([a-zA-Z_]\\w*)\\s*\\(");

    static void extractCallSites(String[] lines, int startLine, int endLine,
                                 String callerFqn, String filePath,
                                 List<RelationTriple> relations) {
        Set<String> seen = new HashSet<>();
        for (int i = startLine + 1; i <= Math.min(endLine, lines.length - 1); i++) {
            String line = lines[i].trim();
            // Skip comment lines
            if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) continue;

            Matcher m = CALL_SITE.matcher(line);
            while (m.find()) {
                String calledName = m.group(1);
                // Skip keywords, constructors, common patterns
                if (isKeyword(calledName) || isControlFlow(calledName)) continue;
                // Skip if it's a constructor-style call (new ClassName)
                int pos = m.start();
                if (pos >= 4) {
                    String before = line.substring(Math.max(0, pos - 4), pos).trim();
                    if (before.endsWith("new")) continue;
                }
                // Deduplicate within same method
                if (seen.add(calledName)) {
                    relations.add(new RelationTriple(callerFqn, calledName, CodeRelationType.CALLS));
                }
            }
        }
    }

    private static boolean isControlFlow(String word) {
        return switch (word) {
            case "if", "else", "for", "while", "do", "switch", "case",
                 "try", "catch", "finally", "throw", "return", "break",
                 "continue", "assert", "yield", "println", "print",
                 "toString", "hashCode", "equals", "getClass",
                 "valueOf", "of", "get", "set", "put", "add", "remove",
                 "size", "isEmpty", "contains", "stream", "map", "filter",
                 "forEach", "collect", "orElse", "orElseThrow",
                 "format", "join", "split", "trim", "length",
                 "parseInt", "parseDouble", "parseLong" -> true;
            default -> false;
        };
    }

    /** Returns true for Java/Groovy/Kotlin/Scala keywords that cannot be identifiers. */
    private static boolean isKeyword(String word) {
        if (word == null || word.isEmpty()) return false;
        return switch (word) {
            case "if", "else", "for", "while", "do", "switch", "case",
                 "try", "catch", "finally", "throw", "throws", "new",
                 "return", "break", "continue", "default", "synchronized",
                 "instanceof", "import", "package", "class", "interface",
                 "enum", "extends", "implements", "super", "this",
                 "true", "false", "null" -> true;
            default -> false;
        };
    }
}
