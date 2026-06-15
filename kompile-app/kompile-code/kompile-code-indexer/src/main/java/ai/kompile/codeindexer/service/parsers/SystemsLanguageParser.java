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
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Language parser for systems and native languages: C, C++, Rust, Go, Swift, Zig, and Dart.
 *
 * Uses regex-based heuristic extraction to pull out declarations, definitions,
 * and structural elements from source files without requiring a full grammar.
 * Covers the most common patterns found in real-world codebases.
 */
@Component
public class SystemsLanguageParser implements LanguageParser {

    // -------------------------------------------------------------------------
    // C patterns
    // -------------------------------------------------------------------------

    /** #include "file.h" or <file.h> */
    private static final Pattern C_INCLUDE = Pattern.compile(
            "^\\s*#include\\s+[<\"]([^>\"]+)[>\"]");

    /** #define MACRO_NAME (value or nothing) — captures name only */
    private static final Pattern C_DEFINE = Pattern.compile(
            "^\\s*#define\\s+(\\w+)(?:\\s|\\(|$)");

    /** Function declaration/definition: return_type name(params) */
    private static final Pattern C_FUNCTION = Pattern.compile(
            "^(?!\\s*#)(?!\\s*/[/*])" +
            "\\s*(?:static\\s+|extern\\s+|inline\\s+|__attribute__\\s*\\(\\([^)]*\\)\\)\\s*)*" +
            "([\\w*\\s]+?[*\\s])(\\w+)\\s*\\(([^;{]*)\\)\\s*(?:\\{|;)");

    /** struct / union / enum / typedef keyword */
    private static final Pattern C_STRUCT = Pattern.compile(
            "^\\s*(typedef\\s+)?(struct|union|enum)\\s+(\\w+)?");

    /** Global variable: type name = ... ; (outside function body, best-effort) */
    private static final Pattern C_GLOBAL_VAR = Pattern.compile(
            "^(?!\\s*#)(?!\\s*/[/*])(?!\\s*typedef)(?!\\s*static\\s+(?:void|int|char|float|double|struct|enum|union)\\s+\\w+\\s*\\()" +
            "\\s*(?:static\\s+|extern\\s+|const\\s+)*" +
            "([\\w*]+(?:\\s+[\\w*]+)*)\\s+(\\w+)\\s*(?:=|;)");

    // -------------------------------------------------------------------------
    // C++ patterns (in addition to C patterns above)
    // -------------------------------------------------------------------------

    /** class/struct Name [: access Base, ...] { */
    private static final Pattern CPP_CLASS = Pattern.compile(
            "^\\s*(?:template\\s*<[^>]*>\\s*)?" +
            "(class|struct)\\s+(\\w+)" +
            "(?:\\s*:\\s*((?:(?:public|protected|private)\\s+)?[\\w:,\\s<>]+))?\\s*(?:\\{|$)");

    /** namespace Name { */
    private static final Pattern CPP_NAMESPACE = Pattern.compile(
            "^\\s*namespace\\s+(\\w+)\\s*\\{?");

    /** template<...> */
    private static final Pattern CPP_TEMPLATE = Pattern.compile(
            "^\\s*template\\s*<([^>]*)>");

    /** using Name = Type ; */
    private static final Pattern CPP_USING = Pattern.compile(
            "^\\s*using\\s+(\\w+)\\s*=\\s*([^;]+)\\s*;");

    /** virtual / override methods inside a class */
    private static final Pattern CPP_METHOD = Pattern.compile(
            "^\\s*(?:virtual\\s+|explicit\\s+|static\\s+|inline\\s+|override\\s+|const\\s+)*" +
            "([\\w:*&<>\\[\\]\\s]+?)\\s+(\\w+)\\s*\\(([^;{]*)\\)\\s*(?:const\\s*)?(?:override\\s*)?(?:=\\s*0\\s*)?(?:\\{|;|override|final)");

    // -------------------------------------------------------------------------
    // Rust patterns
    // -------------------------------------------------------------------------

    /** use path::item; */
    private static final Pattern RUST_USE = Pattern.compile(
            "^\\s*(?:pub\\s+)?use\\s+([^;]+);");

    /** mod name; or mod name { */
    private static final Pattern RUST_MOD = Pattern.compile(
            "^\\s*(?:pub\\s+)?mod\\s+(\\w+)\\s*(?:\\{|;)");

    /** pub fn / async fn / pub async fn / fn */
    private static final Pattern RUST_FN = Pattern.compile(
            "^\\s*(pub(?:\\([^)]*\\))?\\s+)?(?:async\\s+)?(?:unsafe\\s+)?fn\\s+(\\w+)" +
            "(?:<[^>]*>)?\\s*\\(([^)]*)\\)(?:\\s*->\\s*([^{;]+))?\\s*(?:\\{|;|where)");

    /** struct Name / enum Name / trait Name / impl Name / impl Trait for Name */
    private static final Pattern RUST_STRUCT = Pattern.compile(
            "^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?struct\\s+(\\w+)(?:<[^>]*>)?");

    private static final Pattern RUST_ENUM = Pattern.compile(
            "^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?enum\\s+(\\w+)(?:<[^>]*>)?");

    private static final Pattern RUST_TRAIT = Pattern.compile(
            "^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:unsafe\\s+)?trait\\s+(\\w+)(?:<[^>]*>)?");

    private static final Pattern RUST_IMPL = Pattern.compile(
            "^\\s*impl(?:<[^>]*>)?\\s+(?:([\\w:]+(?:<[^>]*>)?)\\s+for\\s+)?(\\w+(?:<[^>]*>)?)\\s*(?:where[^{]*)?\\{");

    /** type Alias = Type; */
    private static final Pattern RUST_TYPE_ALIAS = Pattern.compile(
            "^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?type\\s+(\\w+)(?:<[^>]*>)?\\s*=\\s*([^;]+);");

    /** const NAME: Type = value; */
    private static final Pattern RUST_CONST = Pattern.compile(
            "^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?const\\s+(\\w+)\\s*:");

    /** static NAME: Type = value; */
    private static final Pattern RUST_STATIC = Pattern.compile(
            "^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?static\\s+(?:mut\\s+)?(\\w+)\\s*:");

    /** macro_rules! name { */
    private static final Pattern RUST_MACRO = Pattern.compile(
            "^\\s*(#\\[macro_export\\]\\s*)?\\s*macro_rules!\\s+(\\w+)\\s*\\{");

    // -------------------------------------------------------------------------
    // Go patterns
    // -------------------------------------------------------------------------

    /** package name */
    private static final Pattern GO_PACKAGE = Pattern.compile(
            "^\\s*package\\s+(\\w+)");

    /** import ( ... ) or import "path" */
    private static final Pattern GO_IMPORT_SINGLE = Pattern.compile(
            "^\\s*import\\s+(?:(\\w+)\\s+)?\"([^\"]+)\"");

    /** line inside grouped import block: [alias] "path" */
    private static final Pattern GO_IMPORT_LINE = Pattern.compile(
            "^\\s+(?:(\\w+)\\s+)?\"([^\"]+)\"");

    /** func [( receiver *Type )] Name (params) [returnType] { */
    private static final Pattern GO_FUNC = Pattern.compile(
            "^\\s*func\\s+(?:\\(\\s*(\\w+)\\s+\\*?(\\w+)\\s*\\)\\s+)?(\\w+)\\s*\\(([^)]*)\\)");

    /** type Name struct / type Name interface */
    private static final Pattern GO_TYPE_STRUCT = Pattern.compile(
            "^\\s*type\\s+(\\w+)\\s+(struct|interface)\\s*\\{");

    /** const ( ... ) or const Name Type = ... */
    private static final Pattern GO_CONST_SINGLE = Pattern.compile(
            "^\\s*const\\s+(\\w+)");

    /** var ( ... ) or var Name Type ... */
    private static final Pattern GO_VAR_SINGLE = Pattern.compile(
            "^\\s*var\\s+(\\w+)");

    // -------------------------------------------------------------------------
    // Swift patterns
    // -------------------------------------------------------------------------

    /** import Module */
    private static final Pattern SWIFT_IMPORT = Pattern.compile(
            "^\\s*import\\s+(\\w[\\w.]*)" );

    /** [access] class/struct/protocol/enum/extension Name [: Base] */
    private static final Pattern SWIFT_TYPE = Pattern.compile(
            "^\\s*(?:(?:public|private|internal|fileprivate|open)\\s+)?" +
            "(?:final\\s+)?(class|struct|protocol|enum|extension)\\s+(\\w+)" +
            "(?:\\s*:\\s*([\\w,\\s<>]+))?\\s*(?:\\{|$)");

    /** [access] [static|class] func name(params) [-> ReturnType] */
    private static final Pattern SWIFT_FUNC = Pattern.compile(
            "^\\s*(?:(?:public|private|internal|fileprivate|open)\\s+)?" +
            "(?:(?:static|class|override|mutating|nonmutating)\\s+)*" +
            "func\\s+(\\w+)\\s*(?:<[^>]*>)?\\s*\\(([^)]*)\\)");

    /** [access] [static] let/var name [: Type] */
    private static final Pattern SWIFT_PROPERTY = Pattern.compile(
            "^\\s*(?:(?:public|private|internal|fileprivate|open)\\s+)?" +
            "(?:static\\s+|class\\s+|lazy\\s+|weak\\s+|unowned\\s+)*" +
            "(let|var)\\s+(\\w+)\\s*(?::\\s*([\\w<>\\[\\]?,\\s]+))?\\s*(?:=|\\{|$)");

    // -------------------------------------------------------------------------
    // Zig patterns
    // -------------------------------------------------------------------------

    /** pub const Name = ... or const Name = ... */
    private static final Pattern ZIG_CONST = Pattern.compile(
            "^\\s*(?:pub\\s+)?const\\s+(\\w+)\\s*(?::|=)");

    /** pub fn name(params) ReturnType or fn name(params) ReturnType */
    private static final Pattern ZIG_FN = Pattern.compile(
            "^\\s*(?:pub\\s+)?(?:export\\s+)?(?:extern\\s+)?fn\\s+(\\w+)\\s*\\(([^)]*)\\)");

    /** struct definition assigned to const: const Name = struct { */
    private static final Pattern ZIG_STRUCT_DECL = Pattern.compile(
            "^\\s*(?:pub\\s+)?const\\s+(\\w+)\\s*=\\s*struct\\s*\\{");

    /** test "description" { */
    private static final Pattern ZIG_TEST = Pattern.compile(
            "^\\s*test\\s+\"([^\"]+)\"\\s*\\{");

    // -------------------------------------------------------------------------
    // Dart patterns
    // -------------------------------------------------------------------------

    /** import 'package:...' as alias; or import "..."; */
    private static final Pattern DART_IMPORT = Pattern.compile(
            "^\\s*import\\s+['\"]([^'\"]+)['\"](?:\\s+as\\s+(\\w+))?\\s*;");

    /** export 'uri'; */
    private static final Pattern DART_EXPORT = Pattern.compile(
            "^\\s*export\\s+['\"]([^'\"]+)['\"]\\s*;");

    /** part 'file.dart'; / part of 'file.dart'; */
    private static final Pattern DART_PART = Pattern.compile(
            "^\\s*part(?:\\s+of)?\\s+['\"]([^'\"]+)['\"]\\s*;");

    /** [abstract] class / mixin / extension Name [extends/implements/on ...] */
    private static final Pattern DART_CLASS = Pattern.compile(
            "^\\s*(?:abstract\\s+)?(?:base\\s+)?(?:final\\s+)?(?:sealed\\s+)?" +
            "(class|mixin|extension)\\s+(\\w+)(?:<[^>]*>)?" +
            "(?:\\s+extends\\s+([\\w<>,\\s]+))?" +
            "(?:\\s+with\\s+([\\w<>,\\s]+))?" +
            "(?:\\s+implements\\s+([\\w<>,\\s]+))?" +
            "(?:\\s+on\\s+([\\w<>,\\s]+))?\\s*(?:\\{|$)");

    /** [static] [return_type] functionName(params) { or => */
    private static final Pattern DART_FUNCTION = Pattern.compile(
            "^\\s*(?:(?:static|external|abstract)\\s+)?" +
            "(?:[\\w<>?\\[\\]]+\\s+)?" +
            "(\\w+)\\s*(?:<[^>]*>)?\\s*\\(([^)]*)\\)\\s*(?:\\{|=>|async|sync)");

    /** Standalone top-level function (no enclosing class on same line) */
    private static final Pattern DART_TOP_FUNCTION = Pattern.compile(
            "^(?!\\s*/[/*])(?!\\s*(?:class|mixin|extension|abstract|import|export|part|library|@))" +
            "\\s*(?:[\\w<>?\\[\\]]+\\s+)+(\\w+)\\s*(?:<[^>]*>)?\\s*\\(([^)]*)\\)\\s*(?:\\{|=>|async)");

    // -------------------------------------------------------------------------
    // Implementation
    // -------------------------------------------------------------------------

    @Override
    public Set<String> supportedLanguages() {
        return Set.of("c", "cpp", "rust", "go", "swift", "zig", "dart");
    }

    @Override
    public ExtractionOutput parse(String[] lines, String filePath, String projectId, String language) {
        List<CodeEntity> entities = new ArrayList<>();
        List<RelationTriple> relations = new ArrayList<>();

        // FILE entity is created by CodeEntityExtractor — not duplicated here.

        switch (language) {
            case "c"     -> extractC(lines, filePath, projectId, entities, relations);
            case "cpp"   -> extractCpp(lines, filePath, projectId, entities, relations);
            case "rust"  -> extractRust(lines, filePath, projectId, entities, relations);
            case "go"    -> extractGo(lines, filePath, projectId, entities, relations);
            case "swift" -> extractSwift(lines, filePath, projectId, entities, relations);
            case "zig"   -> extractZig(lines, filePath, projectId, entities, relations);
            case "dart"  -> extractDart(lines, filePath, projectId, entities, relations);
            default      -> {} // unknown — file entity only
        }

        return new ExtractionOutput(entities, relations);
    }

    // =========================================================================
    // C extraction
    // =========================================================================

    private void extractC(String[] lines, String filePath, String projectId,
                          List<CodeEntity> entities, List<RelationTriple> relations) {
        StringBuilder docComment = null;
        boolean inBlockComment = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Doc-comment accumulation: Doxygen /** ... */ or /*! ... */
            if (trimmed.startsWith("/**") || trimmed.startsWith("/*!")) {
                docComment = new StringBuilder(line).append('\n');
                inBlockComment = !trimmed.contains("*/");
                continue;
            }
            if (inBlockComment) {
                if (docComment != null) docComment.append(line).append('\n');
                if (trimmed.contains("*/")) inBlockComment = false;
                continue;
            }
            // Doxygen line comments: /// or //!
            if (trimmed.startsWith("///") || trimmed.startsWith("//!")) {
                if (docComment == null) docComment = new StringBuilder();
                docComment.append(line).append('\n');
                continue;
            }
            if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                continue;
            }

            String doc = docComment != null ? docComment.toString().trim() : null;

            // #include
            Matcher m = C_INCLUDE.matcher(line);
            if (m.find()) {
                String header = m.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.IMPORT)
                        .name(header)
                        .fullyQualifiedName(header)
                        .filePath(filePath)
                        .language("c")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature("#include " + header)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, header, CodeRelationType.IMPORTS));
                docComment = null;
                continue;
            }

            // #define macro → CONSTANT
            m = C_DEFINE.matcher(line);
            if (m.find()) {
                String macroName = m.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.CONSTANT)
                        .name(macroName)
                        .fullyQualifiedName(filePath + "::" + macroName)
                        .filePath(filePath)
                        .language("c")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature(trimmed)
                        .docComment(doc)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, filePath + "::" + macroName, CodeRelationType.CONTAINS));
                docComment = null;
                continue;
            }

            // struct/union/enum/typedef
            m = C_STRUCT.matcher(line);
            if (m.find()) {
                String typedef = m.group(1);
                String keyword = m.group(2);
                String name = m.group(3);
                if (name != null && !name.isEmpty()) {
                    CodeEntityType type = "enum".equals(keyword) ? CodeEntityType.ENUM : CodeEntityType.CLASS;
                    String fqn = filePath + "::" + name;
                    int endLine = findBlockEnd(lines, i);
                    entities.add(CodeEntity.builder()
                            .projectId(projectId)
                            .entityType(type)
                            .name(name)
                            .fullyQualifiedName(fqn)
                            .filePath(filePath)
                            .language("c")
                            .startLine(i + 1)
                            .endLine(endLine + 1)
                            .signature(trimmed)
                            .docComment(doc)
                            .parentFqn(filePath)
                            .build());
                    relations.add(new RelationTriple(filePath, fqn, CodeRelationType.CONTAINS));
                    docComment = null;
                    i = endLine;
                    continue;
                }
                // Anonymous struct/union/enum — clear doc comment so it doesn't leak
                docComment = null;
            }

            // Function declaration / definition
            m = C_FUNCTION.matcher(line);
            if (m.find()) {
                String returnType = m.group(1).trim();
                String funcName = m.group(2);
                String params = m.group(3).trim();
                // Skip known false positives
                if (isReservedCKeyword(funcName)) { docComment = null; continue; }
                String fqn = filePath + "::" + funcName;
                int endLine = trimmed.endsWith("{") ? findBlockEnd(lines, i) : i;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.FUNCTION)
                        .name(funcName)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("c")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(returnType + " " + funcName + "(" + params + ")")
                        .docComment(doc)
                        .contentPreview(buildPreview(lines, i, endLine, 500))
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, fqn, CodeRelationType.CONTAINS));
                docComment = null;
                if (endLine > i) i = endLine;
                continue;
            }

            // Global variables (very broad, best-effort, outside struct/function blocks)
            m = C_GLOBAL_VAR.matcher(line);
            if (m.find()) {
                String varType = m.group(1).trim();
                String varName = m.group(2);
                if (!isReservedCKeyword(varName) && !varType.isBlank()) {
                    String fqn = filePath + "::" + varName;
                    entities.add(CodeEntity.builder()
                            .projectId(projectId)
                            .entityType(CodeEntityType.VARIABLE)
                            .name(varName)
                            .fullyQualifiedName(fqn)
                            .filePath(filePath)
                            .language("c")
                            .startLine(i + 1)
                            .endLine(i + 1)
                            .signature(varType + " " + varName)
                            .docComment(doc)
                            .parentFqn(filePath)
                            .build());
                    relations.add(new RelationTriple(filePath, fqn, CodeRelationType.CONTAINS));
                }
            }
            docComment = null;
        }
    }

    // =========================================================================
    // C++ extraction (extends C)
    // =========================================================================

    private void extractCpp(String[] lines, String filePath, String projectId,
                            List<CodeEntity> entities, List<RelationTriple> relations) {

        String currentNamespace = null;
        String currentClass = null;
        String currentClassFqn = null;
        boolean nextLineIsTemplate = false;
        StringBuilder docComment = null;
        boolean inBlockComment = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Doc-comment accumulation: Doxygen /** ... */ or /*! ... */
            if (trimmed.startsWith("/**") || trimmed.startsWith("/*!")) {
                docComment = new StringBuilder(line).append('\n');
                inBlockComment = !trimmed.contains("*/");
                continue;
            }
            if (inBlockComment) {
                if (docComment != null) docComment.append(line).append('\n');
                if (trimmed.contains("*/")) inBlockComment = false;
                continue;
            }
            // Doxygen line comments: /// or //!
            if (trimmed.startsWith("///") || trimmed.startsWith("//!")) {
                if (docComment == null) docComment = new StringBuilder();
                docComment.append(line).append('\n');
                continue;
            }
            if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                continue;
            }

            String doc = docComment != null ? docComment.toString().trim() : null;

            // Track template on previous line
            boolean isTemplate = nextLineIsTemplate;
            nextLineIsTemplate = CPP_TEMPLATE.matcher(line).find();

            // #include
            Matcher m = C_INCLUDE.matcher(line);
            if (m.find()) {
                String header = m.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.IMPORT)
                        .name(header)
                        .fullyQualifiedName(header)
                        .filePath(filePath)
                        .language("cpp")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature("#include " + header)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, header, CodeRelationType.IMPORTS));
                docComment = null;
                continue;
            }

            // #define
            m = C_DEFINE.matcher(line);
            if (m.find()) {
                String macroName = m.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.CONSTANT)
                        .name(macroName)
                        .fullyQualifiedName(filePath + "::" + macroName)
                        .filePath(filePath)
                        .language("cpp")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature(trimmed)
                        .docComment(doc)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, filePath + "::" + macroName, CodeRelationType.CONTAINS));
                docComment = null;
                continue;
            }

            // namespace
            m = CPP_NAMESPACE.matcher(line);
            if (m.find()) {
                currentNamespace = m.group(1);
                String fqn = currentNamespace;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.MODULE)
                        .name(currentNamespace)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("cpp")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature(trimmed)
                        .docComment(doc)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, fqn, CodeRelationType.CONTAINS));
                docComment = null;
                continue;
            }

            // using alias = type
            m = CPP_USING.matcher(line);
            if (m.find()) {
                String alias = m.group(1);
                String targetType = m.group(2).trim();
                String parentFqn = currentClassFqn != null ? currentClassFqn : filePath;
                String fqn = parentFqn + "::" + alias;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.TYPE_ALIAS)
                        .name(alias)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("cpp")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature("using " + alias + " = " + targetType)
                        .docComment(doc)
                        .parentFqn(parentFqn)
                        .build());
                relations.add(new RelationTriple(parentFqn, fqn, CodeRelationType.CONTAINS));
                relations.add(new RelationTriple(fqn, targetType, CodeRelationType.DEPENDS_ON));
                docComment = null;
                continue;
            }

            // class/struct
            m = CPP_CLASS.matcher(line);
            if (m.find()) {
                String keyword = m.group(1);
                String name = m.group(2);
                String bases = m.group(3);
                currentClass = name;
                String ns = currentNamespace != null ? currentNamespace + "::" : "";
                currentClassFqn = ns + name;
                int endLine = findBlockEnd(lines, i);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.CLASS)
                        .name(name)
                        .fullyQualifiedName(currentClassFqn)
                        .filePath(filePath)
                        .language("cpp")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(trimmed)
                        .docComment(doc)
                        .parentFqn(filePath)
                        .build());
                String parent = currentNamespace != null ? currentNamespace : filePath;
                relations.add(new RelationTriple(parent, currentClassFqn, CodeRelationType.CONTAINS));
                if (bases != null) {
                    for (String base : bases.split(",")) {
                        String baseName = base.replaceAll("(public|protected|private)\\s+", "").trim();
                        if (!baseName.isEmpty()) {
                            relations.add(new RelationTriple(currentClassFqn, baseName, CodeRelationType.EXTENDS));
                        }
                    }
                }
                // Don't skip to endLine — we want methods inside the class body
                docComment = null;
                continue;
            }

            // C struct/union/enum/typedef (kept for C-style use in C++)
            m = C_STRUCT.matcher(line);
            if (m.find() && currentClass == null) {
                String keyword = m.group(2);
                String name = m.group(3);
                if (name != null && !name.isEmpty()) {
                    CodeEntityType type = "enum".equals(keyword) ? CodeEntityType.ENUM : CodeEntityType.CLASS;
                    String fqn = (currentNamespace != null ? currentNamespace + "::" : "") + name;
                    int endLine = findBlockEnd(lines, i);
                    entities.add(CodeEntity.builder()
                            .projectId(projectId)
                            .entityType(type)
                            .name(name)
                            .fullyQualifiedName(fqn)
                            .filePath(filePath)
                            .language("cpp")
                            .startLine(i + 1)
                            .endLine(endLine + 1)
                            .signature(trimmed)
                            .docComment(doc)
                            .parentFqn(filePath)
                            .build());
                    relations.add(new RelationTriple(filePath, fqn, CodeRelationType.CONTAINS));
                    docComment = null;
                    continue;
                }
                // Anonymous struct/union/enum — clear doc comment so it doesn't leak
                docComment = null;
            }

            // Methods inside a class
            if (currentClassFqn != null) {
                m = CPP_METHOD.matcher(line);
                if (m.find()) {
                    String returnType = m.group(1) != null ? m.group(1).trim() : "void";
                    String methodName = m.group(2);
                    String params = m.group(3) != null ? m.group(3).trim() : "";
                    if (!isReservedCKeyword(methodName) && !methodName.equals(currentClass)) {
                        boolean isVirtual = trimmed.startsWith("virtual") || trimmed.contains("override");
                        String methodFqn = currentClassFqn + "::" + methodName;
                        int endLine = trimmed.endsWith("{") ? findBlockEnd(lines, i) : i;
                        entities.add(CodeEntity.builder()
                                .projectId(projectId)
                                .entityType(CodeEntityType.METHOD)
                                .name(methodName)
                                .fullyQualifiedName(methodFqn)
                                .filePath(filePath)
                                .language("cpp")
                                .startLine(i + 1)
                                .endLine(endLine + 1)
                                .signature(returnType + " " + methodName + "(" + params + ")")
                                .docComment(doc)
                                .parentFqn(currentClassFqn)
                                .build());
                        relations.add(new RelationTriple(currentClassFqn, methodFqn, CodeRelationType.CONTAINS));
                        if (isVirtual && trimmed.contains("override")) {
                            relations.add(new RelationTriple(methodFqn, methodFqn, CodeRelationType.OVERRIDES));
                        }
                        docComment = null;
                        if (endLine > i) i = endLine;
                        continue;
                    }
                }
            } else {
                // Free functions at file/namespace scope
                m = C_FUNCTION.matcher(line);
                if (m.find()) {
                    String returnType = m.group(1).trim();
                    String funcName = m.group(2);
                    String params = m.group(3).trim();
                    if (!isReservedCKeyword(funcName)) {
                        String ns = currentNamespace != null ? currentNamespace + "::" : "";
                        String fqn = filePath + "::" + ns + funcName;
                        int endLine = trimmed.endsWith("{") ? findBlockEnd(lines, i) : i;
                        entities.add(CodeEntity.builder()
                                .projectId(projectId)
                                .entityType(CodeEntityType.FUNCTION)
                                .name(funcName)
                                .fullyQualifiedName(fqn)
                                .filePath(filePath)
                                .language("cpp")
                                .startLine(i + 1)
                                .endLine(endLine + 1)
                                .signature(returnType + " " + funcName + "(" + params + ")")
                                .docComment(doc)
                                .parentFqn(currentNamespace != null ? currentNamespace : filePath)
                                .build());
                        String parent = currentNamespace != null ? currentNamespace : filePath;
                        relations.add(new RelationTriple(parent, fqn, CodeRelationType.CONTAINS));
                        docComment = null;
                        if (endLine > i) i = endLine;
                    }
                }
            }
            docComment = null;
        }
    }

    // =========================================================================
    // Rust extraction
    // =========================================================================

    private void extractRust(String[] lines, String filePath, String projectId,
                             List<CodeEntity> entities, List<RelationTriple> relations) {

        String currentMod = null;
        String currentImpl = null; // type being impl'd
        StringBuilder docComment = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Rust doc comments: /// (outer) or //! (inner/module-level)
            if (trimmed.startsWith("///") || trimmed.startsWith("//!")) {
                if (docComment == null) docComment = new StringBuilder();
                docComment.append(line).append('\n');
                continue;
            }
            if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                continue;
            }

            String doc = docComment != null ? docComment.toString().trim() : null;

            // use statement
            Matcher m = RUST_USE.matcher(line);
            if (m.find()) {
                String usePath = m.group(1).trim();
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.IMPORT)
                        .name(usePath)
                        .fullyQualifiedName(usePath)
                        .filePath(filePath)
                        .language("rust")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature("use " + usePath)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, usePath, CodeRelationType.IMPORTS));
                docComment = null;
                continue;
            }

            // mod declaration
            m = RUST_MOD.matcher(line);
            if (m.find()) {
                String modName = m.group(1);
                currentMod = modName;
                int endLine = trimmed.endsWith("{") ? findBlockEnd(lines, i) : i;
                String fqn = (currentMod != null ? currentMod + "::" : "") + modName;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.MODULE)
                        .name(modName)
                        .fullyQualifiedName(filePath + "::" + modName)
                        .filePath(filePath)
                        .language("rust")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(trimmed)
                        .docComment(doc)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, filePath + "::" + modName, CodeRelationType.CONTAINS));
                docComment = null;
                continue;
            }

            // macro_rules!
            m = RUST_MACRO.matcher(line);
            if (m.find()) {
                String macroName = m.group(2);
                int endLine = findBlockEnd(lines, i);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.FUNCTION) // macros are function-like
                        .name(macroName + "!")
                        .fullyQualifiedName(filePath + "::" + macroName)
                        .filePath(filePath)
                        .language("rust")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature("macro_rules! " + macroName)
                        .docComment(doc)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, filePath + "::" + macroName, CodeRelationType.CONTAINS));
                docComment = null;
                i = endLine;
                continue;
            }

            // struct
            m = RUST_STRUCT.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                int endLine = findBlockEnd(lines, i);
                String fqn = filePath + "::" + name;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.CLASS)
                        .name(name)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("rust")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(trimmed)
                        .docComment(doc)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, fqn, CodeRelationType.CONTAINS));
                docComment = null;
                continue;
            }

            // enum
            m = RUST_ENUM.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                int endLine = findBlockEnd(lines, i);
                String fqn = filePath + "::" + name;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.ENUM)
                        .name(name)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("rust")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(trimmed)
                        .docComment(doc)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, fqn, CodeRelationType.CONTAINS));
                docComment = null;
                continue;
            }

            // trait
            m = RUST_TRAIT.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                int endLine = findBlockEnd(lines, i);
                String fqn = filePath + "::" + name;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.INTERFACE)
                        .name(name)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("rust")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(trimmed)
                        .docComment(doc)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, fqn, CodeRelationType.CONTAINS));
                docComment = null;
                continue;
            }

            // impl block
            m = RUST_IMPL.matcher(line);
            if (m.find()) {
                String traitName = m.group(1); // nullable — present only for "impl Trait for Type"
                String typeName = m.group(2);
                currentImpl = typeName;
                if (traitName != null) {
                    // impl Trait for Type → IMPLEMENTS relation
                    String typeFqn = filePath + "::" + typeName;
                    relations.add(new RelationTriple(typeFqn, traitName, CodeRelationType.IMPLEMENTS));
                }
                docComment = null;
                continue;
            }

            // type alias
            m = RUST_TYPE_ALIAS.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String target = m.group(2).trim();
                String fqn = filePath + "::" + name;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.TYPE_ALIAS)
                        .name(name)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("rust")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature("type " + name + " = " + target)
                        .docComment(doc)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, fqn, CodeRelationType.CONTAINS));
                docComment = null;
                continue;
            }

            // const
            m = RUST_CONST.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String parentFqn = currentImpl != null ? filePath + "::" + currentImpl : filePath;
                String fqn = parentFqn + "::" + name;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.CONSTANT)
                        .name(name)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("rust")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature(trimmed)
                        .docComment(doc)
                        .parentFqn(parentFqn)
                        .build());
                relations.add(new RelationTriple(parentFqn, fqn, CodeRelationType.CONTAINS));
                docComment = null;
                continue;
            }

            // static
            m = RUST_STATIC.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String fqn = filePath + "::" + name;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.VARIABLE)
                        .name(name)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("rust")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature(trimmed)
                        .docComment(doc)
                        .parentFqn(filePath)
                        .isStatic(true)
                        .build());
                relations.add(new RelationTriple(filePath, fqn, CodeRelationType.CONTAINS));
                docComment = null;
                continue;
            }

            // fn (must come after struct/enum/trait/impl checks)
            m = RUST_FN.matcher(line);
            if (m.find()) {
                String pubQual = m.group(1);
                String fnName = m.group(2);
                String params = m.group(3) != null ? m.group(3).trim() : "";
                String returnType = m.group(4) != null ? m.group(4).trim() : "()";
                String parentFqn = currentImpl != null ? filePath + "::" + currentImpl : filePath;
                String fqn = parentFqn + "::" + fnName;
                boolean isPublic = pubQual != null && !pubQual.isBlank();
                int endLine = trimmed.endsWith(";") ? i : findBlockEnd(lines, i);
                CodeEntityType fnType = currentImpl != null ? CodeEntityType.METHOD : CodeEntityType.FUNCTION;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(fnType)
                        .name(fnName)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("rust")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature("fn " + fnName + "(" + params + ") -> " + returnType)
                        .docComment(doc)
                        .parentFqn(parentFqn)
                        .visibility(isPublic ? "pub" : null)
                        .build());
                relations.add(new RelationTriple(parentFqn, fqn, CodeRelationType.CONTAINS));
                docComment = null;
                i = endLine;
            }
            docComment = null;
        }
    }

    // =========================================================================
    // Go extraction
    // =========================================================================

    private void extractGo(String[] lines, String filePath, String projectId,
                           List<CodeEntity> entities, List<RelationTriple> relations) {

        boolean inImportBlock = false;
        StringBuilder docComment = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Go godoc: accumulate consecutive // comment lines immediately before a declaration.
            // A blank line resets the accumulated comment.
            if (trimmed.startsWith("//")) {
                if (docComment == null) docComment = new StringBuilder();
                docComment.append(line).append('\n');
                continue;
            }
            if (trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                continue;
            }
            if (trimmed.isEmpty()) {
                docComment = null;
                continue;
            }

            String doc = docComment != null ? docComment.toString().trim() : null;

            // package
            Matcher m = GO_PACKAGE.matcher(line);
            if (m.find()) {
                String pkgName = m.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.PACKAGE)
                        .name(pkgName)
                        .fullyQualifiedName(pkgName)
                        .filePath(filePath)
                        .language("go")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .build());
                relations.add(new RelationTriple(filePath, pkgName, CodeRelationType.CONTAINS));
                docComment = null;
                continue;
            }

            // import block open
            if (trimmed.equals("import (")) {
                inImportBlock = true;
                docComment = null;
                continue;
            }
            if (inImportBlock) {
                if (trimmed.equals(")")) {
                    inImportBlock = false;
                    continue;
                }
                m = GO_IMPORT_LINE.matcher(line);
                if (m.find()) {
                    String alias = m.group(1);
                    String path = m.group(2);
                    String importName = alias != null ? alias : path.substring(path.lastIndexOf('/') + 1);
                    entities.add(CodeEntity.builder()
                            .projectId(projectId)
                            .entityType(CodeEntityType.IMPORT)
                            .name(importName)
                            .fullyQualifiedName(path)
                            .filePath(filePath)
                            .language("go")
                            .startLine(i + 1)
                            .endLine(i + 1)
                            .signature("import \"" + path + "\"")
                            .parentFqn(filePath)
                            .build());
                    relations.add(new RelationTriple(filePath, path, CodeRelationType.IMPORTS));
                }
                continue;
            }

            // single import
            m = GO_IMPORT_SINGLE.matcher(line);
            if (m.find()) {
                String alias = m.group(1);
                String path = m.group(2);
                String importName = alias != null ? alias : path.substring(path.lastIndexOf('/') + 1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.IMPORT)
                        .name(importName)
                        .fullyQualifiedName(path)
                        .filePath(filePath)
                        .language("go")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature("import \"" + path + "\"")
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, path, CodeRelationType.IMPORTS));
                docComment = null;
                continue;
            }

            // type struct / interface
            m = GO_TYPE_STRUCT.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String kind = m.group(2);
                CodeEntityType type = "interface".equals(kind) ? CodeEntityType.INTERFACE : CodeEntityType.CLASS;
                int endLine = findBlockEnd(lines, i);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(type)
                        .name(name)
                        .fullyQualifiedName(name)
                        .filePath(filePath)
                        .language("go")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(trimmed)
                        .docComment(doc)
                        .contentPreview(buildPreview(lines, i, endLine, 500))
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, name, CodeRelationType.CONTAINS));

                // Extract fields from struct body (not interface)
                if ("struct".equals(kind)) {
                    for (int f = i + 1; f < endLine; f++) {
                        String fieldLine = lines[f].trim();
                        if (fieldLine.isEmpty() || fieldLine.startsWith("//") || fieldLine.startsWith("/*")
                                || fieldLine.startsWith("*") || fieldLine.equals("}")) continue;
                        // Go struct field: "FieldName Type" or "FieldName Type `tag`"
                        // Skip embedded types (single word with no type following)
                        Matcher fm = Pattern.compile("^(\\w+)\\s+(\\*?[\\w.\\[\\]]+)").matcher(fieldLine);
                        if (fm.find()) {
                            String fieldName = fm.group(1);
                            // Skip lowercase-only names that are Go keywords
                            if (fieldName.equals("func") || fieldName.equals("type") || fieldName.equals("map")
                                    || fieldName.equals("chan") || fieldName.equals("interface")) continue;
                            String fieldFqn = name + "." + fieldName;
                            entities.add(CodeEntity.builder()
                                    .projectId(projectId)
                                    .entityType(CodeEntityType.FIELD)
                                    .name(fieldName)
                                    .fullyQualifiedName(fieldFqn)
                                    .filePath(filePath)
                                    .language("go")
                                    .startLine(f + 1)
                                    .endLine(f + 1)
                                    .signature(fieldLine)
                                    .parentFqn(name)
                                    .build());
                            relations.add(new RelationTriple(name, fieldFqn, CodeRelationType.CONTAINS));
                        }
                    }
                }

                // Extract method signatures from interface body
                if ("interface".equals(kind)) {
                    for (int f = i + 1; f < endLine; f++) {
                        String ifaceLine = lines[f].trim();
                        if (ifaceLine.isEmpty() || ifaceLine.startsWith("//") || ifaceLine.startsWith("/*")
                                || ifaceLine.startsWith("*") || ifaceLine.equals("}")) continue;
                        // Go interface method: "MethodName(params) returnType"
                        Matcher im = Pattern.compile("^(\\w+)\\s*\\(([^)]*)\\)").matcher(ifaceLine);
                        if (im.find()) {
                            String methName = im.group(1);
                            String methParams = im.group(2).trim();
                            // Skip Go keywords that could appear in embedded interfaces
                            if (methName.equals("type") || methName.equals("interface")
                                    || methName.equals("struct") || methName.equals("func")) continue;
                            String methFqn = name + "." + methName;
                            entities.add(CodeEntity.builder()
                                    .projectId(projectId)
                                    .entityType(CodeEntityType.METHOD)
                                    .name(methName)
                                    .fullyQualifiedName(methFqn)
                                    .filePath(filePath)
                                    .language("go")
                                    .startLine(f + 1)
                                    .endLine(f + 1)
                                    .signature(methName + "(" + methParams + ")")
                                    .parentFqn(name)
                                    .isAbstract(true)
                                    .build());
                            relations.add(new RelationTriple(name, methFqn, CodeRelationType.CONTAINS));
                        }
                    }
                }
                docComment = null;
                i = endLine;
                continue;
            }

            // func (with or without receiver)
            m = GO_FUNC.matcher(line);
            if (m.find()) {
                String receiverVar = m.group(1);
                String receiverType = m.group(2);
                String funcName = m.group(3);
                String params = m.group(4) != null ? m.group(4).trim() : "";
                boolean isMethod = receiverType != null;
                String fqn = isMethod ? receiverType + "." + funcName : funcName;
                CodeEntityType funcType = isMethod ? CodeEntityType.METHOD : CodeEntityType.FUNCTION;
                String parentFqn = isMethod ? receiverType : filePath;
                int endLine = findBlockEnd(lines, i);
                String sig = isMethod
                        ? "func (" + receiverVar + " *" + receiverType + ") " + funcName + "(" + params + ")"
                        : "func " + funcName + "(" + params + ")";
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(funcType)
                        .name(funcName)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("go")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(sig)
                        .docComment(doc)
                        .contentPreview(buildPreview(lines, i, endLine, 500))
                        .parentFqn(parentFqn)
                        .build());
                relations.add(new RelationTriple(parentFqn, fqn, CodeRelationType.CONTAINS));
                docComment = null;
                i = endLine;
                continue;
            }

            // const single
            m = GO_CONST_SINGLE.matcher(line);
            if (m.find() && !trimmed.contains("(")) {
                String name = m.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.CONSTANT)
                        .name(name)
                        .fullyQualifiedName(name)
                        .filePath(filePath)
                        .language("go")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature(trimmed)
                        .docComment(doc)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, name, CodeRelationType.CONTAINS));
                docComment = null;
                continue;
            }

            // var single
            m = GO_VAR_SINGLE.matcher(line);
            if (m.find() && !trimmed.contains("(")) {
                String name = m.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.VARIABLE)
                        .name(name)
                        .fullyQualifiedName(name)
                        .filePath(filePath)
                        .language("go")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature(trimmed)
                        .docComment(doc)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, name, CodeRelationType.CONTAINS));
            }
            docComment = null;
        }
    }

    // =========================================================================
    // Swift extraction
    // =========================================================================

    private void extractSwift(String[] lines, String filePath, String projectId,
                              List<CodeEntity> entities, List<RelationTriple> relations) {

        String currentType = null;
        String currentTypeFqn = null;
        StringBuilder docComment = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Swift doc comments: /// (doc) vs // (regular comment)
            if (trimmed.startsWith("///")) {
                if (docComment == null) docComment = new StringBuilder();
                docComment.append(line).append('\n');
                continue;
            }
            if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                continue;
            }

            String doc = docComment != null ? docComment.toString().trim() : null;

            // import
            Matcher m = SWIFT_IMPORT.matcher(line);
            if (m.find()) {
                String module = m.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.IMPORT)
                        .name(module)
                        .fullyQualifiedName(module)
                        .filePath(filePath)
                        .language("swift")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature("import " + module)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, module, CodeRelationType.IMPORTS));
                docComment = null;
                continue;
            }

            // class / struct / protocol / enum / extension
            m = SWIFT_TYPE.matcher(line);
            if (m.find()) {
                String keyword = m.group(1);
                String name = m.group(2);
                String conformances = m.group(3);
                currentType = name;
                currentTypeFqn = name;
                CodeEntityType type = switch (keyword) {
                    case "protocol" -> CodeEntityType.INTERFACE;
                    case "enum" -> CodeEntityType.ENUM;
                    case "extension" -> CodeEntityType.CLASS; // extensions don't create new types
                    default -> CodeEntityType.CLASS;
                };
                int endLine = findBlockEnd(lines, i);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(type)
                        .name(name)
                        .fullyQualifiedName(currentTypeFqn)
                        .filePath(filePath)
                        .language("swift")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(trimmed)
                        .docComment(doc)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, currentTypeFqn, CodeRelationType.CONTAINS));
                if (conformances != null) {
                    String[] parts = conformances.split(",");
                    for (int pi = 0; pi < parts.length; pi++) {
                        String c = parts[pi].trim();
                        if (!c.isEmpty()) {
                            // First conformance is often the superclass for class, rest are protocols
                            if (pi == 0 && "class".equals(keyword)) {
                                relations.add(new RelationTriple(currentTypeFqn, c, CodeRelationType.EXTENDS));
                            } else {
                                relations.add(new RelationTriple(currentTypeFqn, c, CodeRelationType.IMPLEMENTS));
                            }
                        }
                    }
                }
                // Don't advance i to endLine — we want members inside
                docComment = null;
                continue;
            }

            // func
            m = SWIFT_FUNC.matcher(line);
            if (m.find()) {
                String funcName = m.group(1);
                String params = m.group(2) != null ? m.group(2).trim() : "";
                String parentFqn = currentTypeFqn != null ? currentTypeFqn : filePath;
                String fqn = parentFqn + "." + funcName;
                CodeEntityType funcType = currentTypeFqn != null ? CodeEntityType.METHOD : CodeEntityType.FUNCTION;
                int endLine = findBlockEnd(lines, i);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(funcType)
                        .name(funcName)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("swift")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature("func " + funcName + "(" + params + ")")
                        .docComment(doc)
                        .parentFqn(parentFqn)
                        .build());
                relations.add(new RelationTriple(parentFqn, fqn, CodeRelationType.CONTAINS));
                docComment = null;
                i = endLine;
                continue;
            }

            // let/var property
            m = SWIFT_PROPERTY.matcher(line);
            if (m.find() && currentTypeFqn != null) {
                String keyword = m.group(1);
                String propName = m.group(2);
                String propType = m.group(3);
                String fqn = currentTypeFqn + "." + propName;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.FIELD)
                        .name(propName)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("swift")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature(keyword + " " + propName + (propType != null ? ": " + propType : ""))
                        .docComment(doc)
                        .parentFqn(currentTypeFqn)
                        .build());
                relations.add(new RelationTriple(currentTypeFqn, fqn, CodeRelationType.CONTAINS));
                if (propType != null) {
                    relations.add(new RelationTriple(fqn, propType.trim(), CodeRelationType.FIELD_TYPE));
                }
            }
            docComment = null;
        }
    }

    // =========================================================================
    // Zig extraction
    // =========================================================================

    private void extractZig(String[] lines, String filePath, String projectId,
                            List<CodeEntity> entities, List<RelationTriple> relations) {

        StringBuilder docComment = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Zig doc comments: /// (doc) vs // (regular comment)
            if (trimmed.startsWith("///")) {
                if (docComment == null) docComment = new StringBuilder();
                docComment.append(line).append('\n');
                continue;
            }
            if (trimmed.startsWith("//")) {
                continue;
            }

            String doc = docComment != null ? docComment.toString().trim() : null;

            // struct assigned to const: pub const Name = struct {
            Matcher m = ZIG_STRUCT_DECL.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                int endLine = findBlockEnd(lines, i);
                String fqn = filePath + "::" + name;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.CLASS)
                        .name(name)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("zig")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(trimmed)
                        .docComment(doc)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, fqn, CodeRelationType.CONTAINS));
                docComment = null;
                i = endLine;
                continue;
            }

            // pub const or const — only simple non-struct ones here
            m = ZIG_CONST.matcher(line);
            if (m.find() && !trimmed.contains("= struct")) {
                String name = m.group(1);
                String fqn = filePath + "::" + name;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.CONSTANT)
                        .name(name)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("zig")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature(trimmed)
                        .docComment(doc)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, fqn, CodeRelationType.CONTAINS));
                docComment = null;
                continue;
            }

            // fn declaration
            m = ZIG_FN.matcher(line);
            if (m.find()) {
                String fnName = m.group(1);
                String params = m.group(2) != null ? m.group(2).trim() : "";
                int endLine = findBlockEnd(lines, i);
                String fqn = filePath + "::" + fnName;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.FUNCTION)
                        .name(fnName)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("zig")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature("fn " + fnName + "(" + params + ")")
                        .docComment(doc)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, fqn, CodeRelationType.CONTAINS));
                docComment = null;
                i = endLine;
                continue;
            }

            // test block
            m = ZIG_TEST.matcher(line);
            if (m.find()) {
                String testDesc = m.group(1);
                int endLine = findBlockEnd(lines, i);
                String fqn = filePath + "::test::" + testDesc.replaceAll("\\s+", "_");
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.FUNCTION)
                        .name("test: " + testDesc)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("zig")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature("test \"" + testDesc + "\"")
                        .docComment(doc)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, fqn, CodeRelationType.CONTAINS));
                docComment = null;
                i = endLine;
            }
            docComment = null;
        }
    }

    // =========================================================================
    // Dart extraction
    // =========================================================================

    private void extractDart(String[] lines, String filePath, String projectId,
                             List<CodeEntity> entities, List<RelationTriple> relations) {

        String currentClass = null;
        String currentClassFqn = null;
        StringBuilder docComment = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Dart doc comments: /// (doc) vs // (regular comment)
            if (trimmed.startsWith("///")) {
                if (docComment == null) docComment = new StringBuilder();
                docComment.append(line).append('\n');
                continue;
            }
            if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                continue;
            }

            String doc = docComment != null ? docComment.toString().trim() : null;

            // import
            Matcher m = DART_IMPORT.matcher(line);
            if (m.find()) {
                String uri = m.group(1);
                String alias = m.group(2);
                String importName = alias != null ? alias : uri.substring(uri.lastIndexOf('/') + 1).replace(".dart", "");
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.IMPORT)
                        .name(importName)
                        .fullyQualifiedName(uri)
                        .filePath(filePath)
                        .language("dart")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature("import '" + uri + "'" + (alias != null ? " as " + alias : ""))
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, uri, CodeRelationType.IMPORTS));
                docComment = null;
                continue;
            }

            // export
            m = DART_EXPORT.matcher(line);
            if (m.find()) {
                String uri = m.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.IMPORT)
                        .name(uri)
                        .fullyQualifiedName(uri)
                        .filePath(filePath)
                        .language("dart")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature("export '" + uri + "'")
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, uri, CodeRelationType.DEPENDS_ON));
                docComment = null;
                continue;
            }

            // part / part of
            m = DART_PART.matcher(line);
            if (m.find()) {
                String uri = m.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.IMPORT)
                        .name(uri)
                        .fullyQualifiedName(uri)
                        .filePath(filePath)
                        .language("dart")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature(trimmed)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, uri, CodeRelationType.DEPENDS_ON));
                docComment = null;
                continue;
            }

            // class / mixin / extension
            m = DART_CLASS.matcher(line);
            if (m.find()) {
                String keyword = m.group(1);
                String name = m.group(2);
                String superclass = m.group(3);
                String mixins = m.group(4);
                String interfaces = m.group(5);
                currentClass = name;
                currentClassFqn = name;
                CodeEntityType type = "mixin".equals(keyword) ? CodeEntityType.CLASS : CodeEntityType.CLASS;
                int endLine = findBlockEnd(lines, i);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(type)
                        .name(name)
                        .fullyQualifiedName(currentClassFqn)
                        .filePath(filePath)
                        .language("dart")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(trimmed)
                        .docComment(doc)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, currentClassFqn, CodeRelationType.CONTAINS));
                if (superclass != null) {
                    relations.add(new RelationTriple(currentClassFqn, superclass.trim(), CodeRelationType.EXTENDS));
                }
                if (interfaces != null) {
                    for (String iface : interfaces.split(",")) {
                        String ifaceTrimmed = iface.trim();
                        if (!ifaceTrimmed.isEmpty()) {
                            relations.add(new RelationTriple(currentClassFqn, ifaceTrimmed, CodeRelationType.IMPLEMENTS));
                        }
                    }
                }
                if (mixins != null) {
                    for (String mixin : mixins.split(",")) {
                        String mixinTrimmed = mixin.trim();
                        if (!mixinTrimmed.isEmpty()) {
                            relations.add(new RelationTriple(currentClassFqn, mixinTrimmed, CodeRelationType.DEPENDS_ON));
                        }
                    }
                }
                // Don't skip to endLine — members inside still need extraction
                docComment = null;
                continue;
            }

            // methods and functions
            if (currentClassFqn != null) {
                // Method inside a class
                m = DART_FUNCTION.matcher(line);
                if (m.find()) {
                    String methodName = m.group(1);
                    String params = m.group(2) != null ? m.group(2).trim() : "";
                    if (!isReservedDartKeyword(methodName)) {
                        String fqn = currentClassFqn + "." + methodName;
                        int endLine = findBlockEnd(lines, i);
                        entities.add(CodeEntity.builder()
                                .projectId(projectId)
                                .entityType(CodeEntityType.METHOD)
                                .name(methodName)
                                .fullyQualifiedName(fqn)
                                .filePath(filePath)
                                .language("dart")
                                .startLine(i + 1)
                                .endLine(endLine + 1)
                                .signature(methodName + "(" + params + ")")
                                .docComment(doc)
                                .parentFqn(currentClassFqn)
                                .build());
                        relations.add(new RelationTriple(currentClassFqn, fqn, CodeRelationType.CONTAINS));
                        docComment = null;
                        i = endLine;
                    }
                }
            } else {
                // Top-level function
                m = DART_TOP_FUNCTION.matcher(line);
                if (m.find()) {
                    String funcName = m.group(1);
                    String params = m.group(2) != null ? m.group(2).trim() : "";
                    if (!isReservedDartKeyword(funcName)) {
                        String fqn = filePath + "::" + funcName;
                        int endLine = findBlockEnd(lines, i);
                        entities.add(CodeEntity.builder()
                                .projectId(projectId)
                                .entityType(CodeEntityType.FUNCTION)
                                .name(funcName)
                                .fullyQualifiedName(fqn)
                                .filePath(filePath)
                                .language("dart")
                                .startLine(i + 1)
                                .endLine(endLine + 1)
                                .signature(funcName + "(" + params + ")")
                                .docComment(doc)
                                .parentFqn(filePath)
                                .build());
                        relations.add(new RelationTriple(filePath, fqn, CodeRelationType.CONTAINS));
                        docComment = null;
                        i = endLine;
                    }
                }
            }
            docComment = null;
        }
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    /**
     * Finds the line index (0-based) where the brace-delimited block that starts
     * at or after {@code startLine} ends. Handles nested braces.
     *
     * Falls back to {@code startLine + 50} (or end of file) if no matching close
     * brace is found, which keeps the parser resilient against malformed input.
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

    private int findBlockEnd(String[] lines, int startLine) {
        int braceCount = 0;
        boolean foundOpen = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inString = false;
        char stringChar = 0;

        for (int i = startLine; i < lines.length; i++) {
            String line = lines[i];
            inLineComment = false; // reset per line

            for (int ci = 0; ci < line.length(); ci++) {
                char c = line.charAt(ci);
                char next = ci + 1 < line.length() ? line.charAt(ci + 1) : 0;

                if (inLineComment) break;

                if (inBlockComment) {
                    if (c == '*' && next == '/') {
                        inBlockComment = false;
                        ci++; // skip '/'
                    }
                    continue;
                }

                if (inString) {
                    if (c == '\\') {
                        ci++; // skip escaped char
                        continue;
                    }
                    if (c == stringChar) inString = false;
                    continue;
                }

                if (c == '/' && next == '/') { inLineComment = true; break; }
                if (c == '/' && next == '*') { inBlockComment = true; ci++; continue; }
                if (c == '"' || c == '\'') { inString = true; stringChar = c; continue; }

                if (c == '{') { braceCount++; foundOpen = true; }
                if (c == '}') {
                    braceCount--;
                    if (foundOpen && braceCount == 0) return i;
                }
            }
        }
        return Math.min(startLine + 50, lines.length - 1);
    }

    /** Build a short content preview from the first few lines */
    private String buildPreview(String[] lines) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(lines.length, 20);
        for (int i = 0; i < limit; i++) {
            sb.append(lines[i]).append('\n');
            if (sb.length() > 500) break;
        }
        return sb.length() > 500 ? sb.substring(0, 500) : sb.toString();
    }

    /** C/C++ keywords that should not be treated as function/variable names */
    private boolean isReservedCKeyword(String name) {
        return switch (name) {
            case "if", "else", "for", "while", "do", "switch", "case", "break",
                    "continue", "return", "goto", "sizeof", "typedef", "struct",
                    "union", "enum", "void", "int", "char", "float", "double",
                    "short", "long", "unsigned", "signed", "const", "static",
                    "extern", "volatile", "auto", "register", "inline", "new",
                    "delete", "class", "namespace", "template", "virtual",
                    "override", "public", "protected", "private", "operator" -> true;
            default -> false;
        };
    }

    /** Dart keywords that should not be treated as function/method names */
    private boolean isReservedDartKeyword(String name) {
        return switch (name) {
            case "if", "else", "for", "while", "do", "switch", "case", "break",
                    "continue", "return", "new", "null", "true", "false",
                    "var", "final", "const", "dynamic", "void", "int", "double",
                    "bool", "String", "List", "Map", "Set", "super", "this",
                    "class", "extends", "implements", "abstract", "static",
                    "get", "set", "async", "await", "try", "catch", "finally",
                    "throw", "import", "export", "library", "part" -> true;
            default -> false;
        };
    }
}
