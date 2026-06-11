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

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Language parser for web technologies: JavaScript, TypeScript, Vue, Svelte,
 * CSS, SCSS, and HTML.
 *
 * <p>JavaScript/TypeScript extraction covers:
 * <ul>
 *   <li>Import statements (named, default, namespace)</li>
 *   <li>Export statements (default, const, function, class)</li>
 *   <li>Class declarations with optional extends/implements</li>
 *   <li>Interface and type alias declarations (TypeScript)</li>
 *   <li>Enum declarations (TypeScript)</li>
 *   <li>Function declarations (including async)</li>
 *   <li>Arrow function assignments (const/let/var)</li>
 *   <li>Method declarations inside classes</li>
 *   <li>Decorators (experimental)</li>
 * </ul>
 *
 * <p>Vue/Svelte files are handled by extracting the content of {@code <script>}
 * blocks and delegating to the JS/TS parser. The component name is derived
 * from the filename.
 *
 * <p>CSS/SCSS extraction covers selectors (class, id, tag), {@code @mixin},
 * {@code @include}, CSS custom properties ({@code --var-name}), and
 * {@code @import} rules.
 *
 * <p>HTML extraction locates inline {@code <script>} blocks and forwards them
 * to the JS parser. Custom elements (tags containing a hyphen) are also
 * recorded as MODULE entities.
 */
@Component
public class WebLanguageParser implements LanguageParser {

    private static final Logger log = LoggerFactory.getLogger(WebLanguageParser.class);

    // -------------------------------------------------------------------------
    // JavaScript / TypeScript patterns
    // -------------------------------------------------------------------------

    /** import { X, Y } from 'module'  |  import X from 'module'  |  import * as X from 'module' */
    private static final Pattern JS_IMPORT_NAMED =
            Pattern.compile("^\\s*import\\s+\\{([^}]+)\\}\\s+from\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern JS_IMPORT_DEFAULT =
            Pattern.compile("^\\s*import\\s+(\\w+)\\s+from\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern JS_IMPORT_NAMESPACE =
            Pattern.compile("^\\s*import\\s+\\*\\s+as\\s+(\\w+)\\s+from\\s+['\"]([^'\"]+)['\"]");
    /** Side-effect import: import 'module' */
    private static final Pattern JS_IMPORT_SIDE_EFFECT =
            Pattern.compile("^\\s*import\\s+['\"]([^'\"]+)['\"]");

    /** export default <expr> */
    private static final Pattern JS_EXPORT_DEFAULT =
            Pattern.compile("^\\s*export\\s+default\\s+(.+)");
    /** export const/let/var name = ... */
    private static final Pattern JS_EXPORT_CONST =
            Pattern.compile("^\\s*export\\s+(const|let|var)\\s+(\\w+)");
    /** export function name(...) */
    private static final Pattern JS_EXPORT_FUNCTION =
            Pattern.compile("^\\s*export\\s+(?:async\\s+)?function\\s+(\\w+)\\s*(?:<[^>]+>)?\\s*\\(([^)]*)\\)");
    /** export class name */
    private static final Pattern JS_EXPORT_CLASS =
            Pattern.compile("^\\s*export\\s+(?:abstract\\s+)?class\\s+(\\w+)");

    /** class Name extends Super implements I1, I2 */
    private static final Pattern JS_CLASS =
            Pattern.compile("^\\s*(?:export\\s+)?(?:abstract\\s+)?class\\s+(\\w+)" +
                    "(?:\\s+extends\\s+([\\w.]+(?:<[^>]+>)?))?" +
                    "(?:\\s+implements\\s+([\\w,\\s<>]+))?");

    /** interface Name extends I1, I2 (TypeScript) */
    private static final Pattern TS_INTERFACE =
            Pattern.compile("^\\s*(?:export\\s+)?interface\\s+(\\w+)(?:\\s+extends\\s+([\\w,\\s]+))?");

    /** type Foo = ... (TypeScript) */
    private static final Pattern TS_TYPE_ALIAS =
            Pattern.compile("^\\s*(?:export\\s+)?type\\s+(\\w+)\\s*(?:<[^>]+>)?\\s*=");

    /** enum Color { ... } (TypeScript) */
    private static final Pattern TS_ENUM =
            Pattern.compile("^\\s*(?:export\\s+)?(?:const\\s+)?enum\\s+(\\w+)");

    /** function name(...) */
    private static final Pattern JS_FUNCTION =
            Pattern.compile("^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+(\\w+)\\s*(?:<[^>]+>)?\\s*\\(([^)]*)\\)");

    /** const/let/var name = (...) => or name = async (...) => */
    private static final Pattern JS_ARROW =
            Pattern.compile("^\\s*(?:export\\s+)?(?:const|let|var)\\s+(\\w+)\\s*(?::[^=]+)?=\\s*(?:async\\s+)?(?:\\([^)]*\\)|\\w+)\\s*=>");

    /** Method inside a class: [visibility] [static] [async] name(...) */
    private static final Pattern JS_METHOD =
            Pattern.compile("^\\s*(?:(?:public|private|protected|static|async|override|abstract|readonly)\\s+)*" +
                    "(?:get\\s+|set\\s+)?(\\w+)\\s*(?:<[^>]+>)?\\s*\\(([^)]*)\\)\\s*(?::\\s*[\\w<>\\[\\]|&.,\\s]+)?\\s*\\{");

    /** Decorator: @DecoratorName or @DecoratorName(...) */
    private static final Pattern JS_DECORATOR =
            Pattern.compile("^\\s*@(\\w+)(?:\\([^)]*\\))?");

    // -------------------------------------------------------------------------
    // CSS / SCSS patterns
    // -------------------------------------------------------------------------

    /** .className, #idName, tagName, or compound selectors — triggers on lines ending with { */
    private static final Pattern CSS_SELECTOR =
            Pattern.compile("^([.#]?[\\w-][\\w\\s,>~+:()\\[\\]=\"'.#-]*)\\s*\\{");

    /** @mixin mixinName (SCSS) */
    private static final Pattern SCSS_MIXIN =
            Pattern.compile("^\\s*@mixin\\s+([\\w-]+)");

    /** @include mixinName (SCSS) */
    private static final Pattern SCSS_INCLUDE =
            Pattern.compile("^\\s*@include\\s+([\\w-]+)");

    /** CSS custom property declaration: --variable-name: value; */
    private static final Pattern CSS_CUSTOM_PROP =
            Pattern.compile("^\\s*(--[\\w-]+)\\s*:");

    /** @import 'file' or @import url('file') */
    private static final Pattern CSS_IMPORT =
            Pattern.compile("^\\s*@import\\s+(?:url\\()?['\"]?([^'\")]+)['\"]?\\)?");

    // -------------------------------------------------------------------------
    // HTML patterns
    // -------------------------------------------------------------------------

    /** Opening <script ...> tag (captures optional lang attribute) */
    private static final Pattern HTML_SCRIPT_OPEN =
            Pattern.compile("<script(?:\\s+[^>]*lang=['\"]([^'\"]*)['\"])?[^>]*>");

    /** Closing </script> tag */
    private static final Pattern HTML_SCRIPT_CLOSE =
            Pattern.compile("</script>");

    /** Custom element usage: <my-component or <MyComponent */
    private static final Pattern HTML_CUSTOM_ELEMENT =
            Pattern.compile("<([a-zA-Z][a-zA-Z0-9]*-[a-zA-Z0-9][a-zA-Z0-9-]*)");

    // -------------------------------------------------------------------------
    // Vue / Svelte — script block extraction
    // -------------------------------------------------------------------------

    /** <script> or <script lang="ts"> in Vue/Svelte */
    private static final Pattern VUE_SCRIPT_OPEN =
            Pattern.compile("<script(?:\\s+[^>]*lang=['\"]([^'\"]*)['\"])?(?:\\s+setup)?[^>]*>");

    private static final Pattern VUE_SCRIPT_CLOSE =
            Pattern.compile("</script>");

    // -------------------------------------------------------------------------
    // LanguageParser contract
    // -------------------------------------------------------------------------

    @Override
    public Set<String> supportedLanguages() {
        return Set.of("javascript", "typescript", "vue", "svelte", "css", "scss", "html");
    }

    @Override
    public ExtractionOutput parse(String[] lines, String filePath, String projectId, String language) {
        List<CodeEntity> entities = new ArrayList<>();
        List<RelationTriple> relations = new ArrayList<>();

        // FILE entity is created by CodeEntityExtractor — not duplicated here.

        switch (language) {
            case "javascript", "typescript" ->
                    extractJsTs(lines, filePath, projectId, language, null, 0, entities, relations);
            case "vue", "svelte" ->
                    extractComponent(lines, filePath, projectId, language, entities, relations);
            case "css", "scss" ->
                    extractCss(lines, filePath, projectId, language, entities, relations);
            case "html" ->
                    extractHtml(lines, filePath, projectId, entities, relations);
            default -> log.debug("WebLanguageParser: unhandled language {}", language);
        }

        return new ExtractionOutput(entities, relations);
    }

    // =========================================================================
    // JavaScript / TypeScript extraction
    // =========================================================================

    /**
     * Extracts entities from JS/TS source lines.
     *
     * @param lines       source lines (may be a subset extracted from a script block)
     * @param filePath    canonical file path used for FQNs
     * @param projectId   project identifier
     * @param language    "javascript" or "typescript"
     * @param parentClass FQN of enclosing class (null at top level)
     * @param lineOffset  line number offset when processing embedded script blocks
     * @param entities    accumulator
     * @param relations   accumulator
     */
    private void extractJsTs(String[] lines, String filePath, String projectId,
                             String language, String parentClass, int lineOffset,
                             List<CodeEntity> entities, List<RelationTriple> relations) {

        String currentClassFqn = parentClass;
        String pendingDecorator = null;
        StringBuilder jsDocBuffer = new StringBuilder();
        boolean inJsDocBlock = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            int absoluteLine = i + 1 + lineOffset;

            // ---- JSDoc / TSDoc block comment accumulation ----
            if (!inJsDocBlock && trimmed.startsWith("/**")) {
                jsDocBuffer.setLength(0);
                inJsDocBlock = true;
                String afterOpen = trimmed.substring(3);
                if (afterOpen.contains("*/")) {
                    // Single-line /** ... */
                    String content = afterOpen.substring(0, afterOpen.indexOf("*/")).trim();
                    content = content.startsWith("*") ? content.substring(1).trim() : content;
                    jsDocBuffer.append(content);
                    inJsDocBlock = false;
                } else if (!afterOpen.trim().isEmpty()) {
                    jsDocBuffer.append(afterOpen.trim());
                }
                continue;
            }
            if (inJsDocBlock) {
                if (trimmed.contains("*/")) {
                    String before = trimmed.substring(0, trimmed.indexOf("*/")).trim();
                    before = before.startsWith("*") ? before.substring(1).trim() : before;
                    if (!before.isEmpty()) {
                        if (jsDocBuffer.length() > 0) jsDocBuffer.append('\n');
                        jsDocBuffer.append(before);
                    }
                    inJsDocBlock = false;
                } else {
                    String content = trimmed.startsWith("*") ? trimmed.substring(1).trim() : trimmed;
                    if (jsDocBuffer.length() > 0) jsDocBuffer.append('\n');
                    jsDocBuffer.append(content);
                }
                continue;
            }

            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*")) {
                // Reset doc buffer on blank lines (not inside a block)
                if (trimmed.isEmpty()) jsDocBuffer.setLength(0);
                continue;
            }

            // Capture accumulated JSDoc for the next entity
            String jsDocRaw = jsDocBuffer.toString().trim();
            String jsDoc = jsDocRaw.isEmpty() ? null : jsDocRaw;

            // ---- Decorator ----
            // Don't clear jsDocBuffer yet — a decorator between JSDoc and
            // the decorated entity should preserve the doc comment.
            Matcher mDec = JS_DECORATOR.matcher(line);
            if (mDec.find()) {
                pendingDecorator = mDec.group(1);
                String decoratorFqn = filePath + "@" + pendingDecorator;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.ANNOTATION)
                        .name(pendingDecorator)
                        .fullyQualifiedName(decoratorFqn)
                        .filePath(filePath)
                        .language(language)
                        .startLine(absoluteLine)
                        .endLine(absoluteLine)
                        .signature(trimmed)
                        .parentFqn(filePath)
                        .build());
                continue;
            }

            // Reset doc buffer now that we're past decorators
            jsDocBuffer.setLength(0);

            // ---- Named import ----
            Matcher m = JS_IMPORT_NAMED.matcher(line);
            if (m.find()) {
                String named = m.group(1);
                String from = m.group(2);
                for (String symbol : named.split(",")) {
                    String sym = symbol.trim().replaceAll("\\s+as\\s+\\w+", "").trim();
                    if (sym.isEmpty()) continue;
                    String importFqn = from + ":" + sym;
                    entities.add(CodeEntity.builder()
                            .projectId(projectId)
                            .entityType(CodeEntityType.IMPORT)
                            .name(sym)
                            .fullyQualifiedName(importFqn)
                            .filePath(filePath)
                            .language(language)
                            .startLine(absoluteLine)
                            .endLine(absoluteLine)
                            .signature(trimmed)
                            .parentFqn(filePath)
                            .build());
                    relations.add(new RelationTriple(filePath, from, CodeRelationType.IMPORTS));
                }
                pendingDecorator = null;
                continue;
            }

            // ---- Namespace import: import * as X from 'y' ----
            m = JS_IMPORT_NAMESPACE.matcher(line);
            if (m.find()) {
                String alias = m.group(1);
                String from = m.group(2);
                String importFqn = from + ":*as:" + alias;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.IMPORT)
                        .name(alias)
                        .fullyQualifiedName(importFqn)
                        .filePath(filePath)
                        .language(language)
                        .startLine(absoluteLine)
                        .endLine(absoluteLine)
                        .signature(trimmed)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, from, CodeRelationType.IMPORTS));
                pendingDecorator = null;
                continue;
            }

            // ---- Default import: import X from 'y' ----
            m = JS_IMPORT_DEFAULT.matcher(line);
            if (m.find()) {
                String defaultName = m.group(1);
                String from = m.group(2);
                String importFqn = from + ":" + defaultName;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.IMPORT)
                        .name(defaultName)
                        .fullyQualifiedName(importFqn)
                        .filePath(filePath)
                        .language(language)
                        .startLine(absoluteLine)
                        .endLine(absoluteLine)
                        .signature(trimmed)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, from, CodeRelationType.IMPORTS));
                pendingDecorator = null;
                continue;
            }

            // ---- Side-effect import: import 'y' ----
            m = JS_IMPORT_SIDE_EFFECT.matcher(line);
            if (m.find()) {
                String from = m.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.IMPORT)
                        .name(from)
                        .fullyQualifiedName(from)
                        .filePath(filePath)
                        .language(language)
                        .startLine(absoluteLine)
                        .endLine(absoluteLine)
                        .signature(trimmed)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, from, CodeRelationType.IMPORTS));
                pendingDecorator = null;
                continue;
            }

            // ---- TypeScript interface ----
            m = TS_INTERFACE.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String extendsList = m.group(2);
                int endLine = findBlockEnd(lines, i);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.INTERFACE)
                        .name(name)
                        .fullyQualifiedName(name)
                        .filePath(filePath)
                        .language(language)
                        .startLine(absoluteLine)
                        .endLine(endLine + 1 + lineOffset)
                        .signature(trimmed)
                        .parentFqn(filePath)
                        .docComment(jsDoc)
                        .build());
                relations.add(new RelationTriple(filePath, name, CodeRelationType.CONTAINS));
                if (extendsList != null) {
                    for (String ext : extendsList.split(",")) {
                        relations.add(new RelationTriple(name, ext.trim(), CodeRelationType.EXTENDS));
                    }
                }
                pendingDecorator = null;
                continue;
            }

            // ---- TypeScript type alias ----
            m = TS_TYPE_ALIAS.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.TYPE_ALIAS)
                        .name(name)
                        .fullyQualifiedName(name)
                        .filePath(filePath)
                        .language(language)
                        .startLine(absoluteLine)
                        .endLine(absoluteLine)
                        .signature(trimmed)
                        .parentFqn(filePath)
                        .docComment(jsDoc)
                        .build());
                relations.add(new RelationTriple(filePath, name, CodeRelationType.CONTAINS));
                pendingDecorator = null;
                continue;
            }

            // ---- TypeScript enum ----
            m = TS_ENUM.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                int endLine = findBlockEnd(lines, i);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.ENUM)
                        .name(name)
                        .fullyQualifiedName(name)
                        .filePath(filePath)
                        .language(language)
                        .startLine(absoluteLine)
                        .endLine(endLine + 1 + lineOffset)
                        .signature(trimmed)
                        .parentFqn(filePath)
                        .docComment(jsDoc)
                        .build());
                relations.add(new RelationTriple(filePath, name, CodeRelationType.CONTAINS));
                pendingDecorator = null;
                continue;
            }

            // ---- Class declaration ----
            m = JS_CLASS.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String superclass = m.group(2);
                String implementsList = m.group(3);
                currentClassFqn = name;
                int endLine = findBlockEnd(lines, i);
                CodeEntity classEntity = CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.CLASS)
                        .name(name)
                        .fullyQualifiedName(name)
                        .filePath(filePath)
                        .language(language)
                        .startLine(absoluteLine)
                        .endLine(endLine + 1 + lineOffset)
                        .signature(trimmed)
                        .parentFqn(filePath)
                        .docComment(jsDoc)
                        .build();
                entities.add(classEntity);
                relations.add(new RelationTriple(filePath, name, CodeRelationType.CONTAINS));
                if (pendingDecorator != null) {
                    relations.add(new RelationTriple(name, pendingDecorator, CodeRelationType.ANNOTATED_BY));
                }
                if (superclass != null) {
                    // Strip generic parameters for the relation FQN
                    String superFqn = superclass.replaceAll("<[^>]+>", "").trim();
                    relations.add(new RelationTriple(name, superFqn, CodeRelationType.EXTENDS));
                }
                if (implementsList != null) {
                    for (String iface : implementsList.split(",")) {
                        String ifaceName = iface.replaceAll("<[^>]+>", "").trim();
                        if (!ifaceName.isEmpty()) {
                            relations.add(new RelationTriple(name, ifaceName, CodeRelationType.IMPLEMENTS));
                        }
                    }
                }
                pendingDecorator = null;
                continue;
            }

            // ---- Function declaration ----
            m = JS_FUNCTION.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String params = m.group(2) != null ? m.group(2) : "";
                String parentFqn = currentClassFqn != null ? currentClassFqn : filePath;
                String fqn = currentClassFqn != null ? currentClassFqn + "." + name : name;
                int endLine = findBlockEnd(lines, i);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(currentClassFqn != null ? CodeEntityType.METHOD : CodeEntityType.FUNCTION)
                        .name(name)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language(language)
                        .startLine(absoluteLine)
                        .endLine(endLine + 1 + lineOffset)
                        .signature("function " + name + "(" + params + ")")
                        .parentFqn(parentFqn)
                        .docComment(jsDoc)
                        .build());
                relations.add(new RelationTriple(parentFqn, fqn, CodeRelationType.CONTAINS));
                if (pendingDecorator != null) {
                    relations.add(new RelationTriple(fqn, pendingDecorator, CodeRelationType.ANNOTATED_BY));
                }
                pendingDecorator = null;
                continue;
            }

            // ---- Arrow function assignment ----
            m = JS_ARROW.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String parentFqn = currentClassFqn != null ? currentClassFqn : filePath;
                String fqn = currentClassFqn != null ? currentClassFqn + "." + name : name;
                int endLine = findBlockEnd(lines, i);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(currentClassFqn != null ? CodeEntityType.FIELD : CodeEntityType.FUNCTION)
                        .name(name)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language(language)
                        .startLine(absoluteLine)
                        .endLine(endLine + 1 + lineOffset)
                        .signature(trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed)
                        .parentFqn(parentFqn)
                        .docComment(jsDoc)
                        .build());
                relations.add(new RelationTriple(parentFqn, fqn, CodeRelationType.CONTAINS));
                pendingDecorator = null;
                continue;
            }

            // ---- Method inside a class (must have an open brace on the same line) ----
            if (currentClassFqn != null) {
                m = JS_METHOD.matcher(line);
                if (m.find()) {
                    String name = m.group(1);
                    // Skip keywords that look like method names
                    if (!isKeyword(name)) {
                        String params = m.group(2) != null ? m.group(2) : "";
                        String fqn = currentClassFqn + "." + name;
                        int endLine = findBlockEnd(lines, i);
                        entities.add(CodeEntity.builder()
                                .projectId(projectId)
                                .entityType(CodeEntityType.METHOD)
                                .name(name)
                                .fullyQualifiedName(fqn)
                                .filePath(filePath)
                                .language(language)
                                .startLine(absoluteLine)
                                .endLine(endLine + 1 + lineOffset)
                                .signature(name + "(" + params + ")")
                                .parentFqn(currentClassFqn)
                                .docComment(jsDoc)
                                .build());
                        relations.add(new RelationTriple(currentClassFqn, fqn, CodeRelationType.CONTAINS));
                        if (pendingDecorator != null) {
                            relations.add(new RelationTriple(fqn, pendingDecorator, CodeRelationType.ANNOTATED_BY));
                        }
                        pendingDecorator = null;
                        continue;
                    }
                }
            }

            // If we get here with a pending decorator and an unrecognised line, clear it
            // only when we hit a non-decorator, non-empty token line
            if (!trimmed.startsWith("@")) {
                pendingDecorator = null;
            }
        }
    }

    // =========================================================================
    // Vue / Svelte extraction
    // =========================================================================

    private void extractComponent(String[] lines, String filePath, String projectId,
                                  String language, List<CodeEntity> entities, List<RelationTriple> relations) {

        // Derive component name from the file name (strip extension)
        String fileName = Paths.get(filePath).getFileName().toString();
        String componentName = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : fileName;

        entities.add(CodeEntity.builder()
                .projectId(projectId)
                .entityType(CodeEntityType.MODULE)
                .name(componentName)
                .fullyQualifiedName(componentName)
                .filePath(filePath)
                .language(language)
                .startLine(1)
                .endLine(lines.length)
                .signature(componentName)
                .parentFqn(filePath)
                .build());
        relations.add(new RelationTriple(filePath, componentName, CodeRelationType.CONTAINS));

        // Extract <script> block(s) and delegate to JS/TS parser
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            Matcher openMatcher = VUE_SCRIPT_OPEN.matcher(line);
            if (openMatcher.find()) {
                String langAttr = openMatcher.group(1);
                String scriptLang = (langAttr != null && langAttr.equalsIgnoreCase("ts"))
                        ? "typescript" : "javascript";

                int scriptStart = i + 1;   // first line after <script ...>
                int scriptEnd = scriptStart;
                for (int j = scriptStart; j < lines.length; j++) {
                    if (VUE_SCRIPT_CLOSE.matcher(lines[j]).find()) {
                        scriptEnd = j;
                        break;
                    }
                }

                if (scriptEnd > scriptStart) {
                    int blockLen = scriptEnd - scriptStart;
                    String[] scriptLines = new String[blockLen];
                    System.arraycopy(lines, scriptStart, scriptLines, 0, blockLen);
                    extractJsTs(scriptLines, filePath, projectId, scriptLang,
                            null, scriptStart, entities, relations);
                }
                i = scriptEnd + 1;
            } else {
                i++;
            }
        }
    }

    // =========================================================================
    // CSS / SCSS extraction
    // =========================================================================

    private void extractCss(String[] lines, String filePath, String projectId,
                            String language, List<CodeEntity> entities, List<RelationTriple> relations) {

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            }

            // @import
            Matcher m = CSS_IMPORT.matcher(line);
            if (m.find()) {
                String importedFile = m.group(1).trim();
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.IMPORT)
                        .name(importedFile)
                        .fullyQualifiedName(importedFile)
                        .filePath(filePath)
                        .language(language)
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature(trimmed)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, importedFile, CodeRelationType.IMPORTS));
                continue;
            }

            // SCSS @mixin
            if ("scss".equals(language)) {
                m = SCSS_MIXIN.matcher(line);
                if (m.find()) {
                    String mixinName = m.group(1);
                    int endLine = findBlockEnd(lines, i);
                    entities.add(CodeEntity.builder()
                            .projectId(projectId)
                            .entityType(CodeEntityType.FUNCTION)
                            .name(mixinName)
                            .fullyQualifiedName("mixin:" + mixinName)
                            .filePath(filePath)
                            .language(language)
                            .startLine(i + 1)
                            .endLine(endLine + 1)
                            .signature(trimmed)
                            .parentFqn(filePath)
                            .build());
                    relations.add(new RelationTriple(filePath, "mixin:" + mixinName, CodeRelationType.CONTAINS));
                    continue;
                }

                m = SCSS_INCLUDE.matcher(line);
                if (m.find()) {
                    String mixinName = m.group(1);
                    relations.add(new RelationTriple(filePath, "mixin:" + mixinName, CodeRelationType.DEPENDS_ON));
                    continue;
                }
            }

            // CSS custom property declaration
            m = CSS_CUSTOM_PROP.matcher(line);
            if (m.find()) {
                String varName = m.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.CONSTANT)
                        .name(varName)
                        .fullyQualifiedName(filePath + ":" + varName)
                        .filePath(filePath)
                        .language(language)
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature(trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed)
                        .parentFqn(filePath)
                        .build());
                continue;
            }

            // Selector (lines ending with '{')
            m = CSS_SELECTOR.matcher(line);
            if (m.find() && !trimmed.startsWith("@")) {
                String rawSelector = m.group(1).trim();
                if (!rawSelector.isEmpty()) {
                    // Emit one entity per comma-separated selector group
                    for (String selectorPart : rawSelector.split(",")) {
                        String selector = selectorPart.trim();
                        if (selector.isEmpty()) continue;
                        int endLine = findBlockEnd(lines, i);
                        entities.add(CodeEntity.builder()
                                .projectId(projectId)
                                .entityType(CodeEntityType.CLASS)  // "class" in the CSS sense
                                .name(selector)
                                .fullyQualifiedName(filePath + ":" + selector)
                                .filePath(filePath)
                                .language(language)
                                .startLine(i + 1)
                                .endLine(endLine + 1)
                                .signature(selector + " { ... }")
                                .parentFqn(filePath)
                                .build());
                        relations.add(new RelationTriple(filePath, filePath + ":" + selector,
                                CodeRelationType.CONTAINS));
                    }
                }
            }
        }
    }

    // =========================================================================
    // HTML extraction
    // =========================================================================

    private void extractHtml(String[] lines, String filePath, String projectId,
                             List<CodeEntity> entities, List<RelationTriple> relations) {

        Set<String> seenElements = new java.util.HashSet<>();
        int scriptBlockIndex = 0;

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];

            // Custom element usage
            Matcher ceMatcher = HTML_CUSTOM_ELEMENT.matcher(line);
            while (ceMatcher.find()) {
                String tagName = ceMatcher.group(1);
                if (seenElements.add(tagName)) {
                    entities.add(CodeEntity.builder()
                            .projectId(projectId)
                            .entityType(CodeEntityType.MODULE)
                            .name(tagName)
                            .fullyQualifiedName(filePath + ":<" + tagName + ">")
                            .filePath(filePath)
                            .language("html")
                            .startLine(i + 1)
                            .endLine(i + 1)
                            .signature("<" + tagName + ">")
                            .parentFqn(filePath)
                            .build());
                    relations.add(new RelationTriple(filePath,
                            filePath + ":<" + tagName + ">", CodeRelationType.DEPENDS_ON));
                }
            }

            // <script> block
            Matcher scriptOpen = HTML_SCRIPT_OPEN.matcher(line);
            if (scriptOpen.find()) {
                String langAttr = scriptOpen.group(1);
                String scriptLang = (langAttr != null && langAttr.equalsIgnoreCase("ts"))
                        ? "typescript" : "javascript";

                int scriptStart = i + 1;
                int scriptEnd = scriptStart;
                for (int j = scriptStart; j < lines.length; j++) {
                    if (HTML_SCRIPT_CLOSE.matcher(lines[j]).find()) {
                        scriptEnd = j;
                        break;
                    }
                }

                if (scriptEnd > scriptStart) {
                    int blockLen = scriptEnd - scriptStart;
                    String[] scriptLines = new String[blockLen];
                    System.arraycopy(lines, scriptStart, scriptLines, 0, blockLen);

                    // Create a synthetic MODULE entity for the script block
                    String blockName = "script#" + scriptBlockIndex++;
                    entities.add(CodeEntity.builder()
                            .projectId(projectId)
                            .entityType(CodeEntityType.MODULE)
                            .name(blockName)
                            .fullyQualifiedName(filePath + ":" + blockName)
                            .filePath(filePath)
                            .language(scriptLang)
                            .startLine(scriptStart + 1)
                            .endLine(scriptEnd)
                            .signature("<script>")
                            .parentFqn(filePath)
                            .build());
                    relations.add(new RelationTriple(filePath,
                            filePath + ":" + blockName, CodeRelationType.CONTAINS));

                    extractJsTs(scriptLines, filePath, projectId, scriptLang,
                            null, scriptStart, entities, relations);
                }
                i = scriptEnd + 1;
            } else {
                i++;
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Finds the line index of the closing brace for a block that opens at or
     * after {@code startLine}. Uses simple brace counting; does not account for
     * braces inside string literals or comments but is sufficient for shallow
     * structural extraction.
     *
     * @param lines     source line array
     * @param startLine index of the line where the block definition begins
     * @return index of the line containing the matching {@code }}, or a
     *         fallback at {@code startLine + 50} if no match is found
     */
    private int findBlockEnd(String[] lines, int startLine) {
        int braceCount = 0;
        boolean foundOpen = false;
        for (int i = startLine; i < lines.length; i++) {
            for (char c : lines[i].toCharArray()) {
                if (c == '{') {
                    braceCount++;
                    foundOpen = true;
                } else if (c == '}') {
                    braceCount--;
                    if (foundOpen && braceCount == 0) {
                        return i;
                    }
                }
            }
        }
        return Math.min(startLine + 50, lines.length - 1);
    }

    /**
     * Returns {@code true} for JS/TS reserved words that the method-detection
     * regex might otherwise match as method names.
     */
    private boolean isKeyword(String name) {
        return switch (name) {
            case "if", "else", "for", "while", "do", "switch", "try", "catch",
                    "finally", "return", "new", "delete", "typeof", "void",
                    "in", "of", "instanceof", "import", "export", "default",
                    "class", "extends", "super", "this", "static", "async",
                    "await", "yield", "let", "const", "var", "function",
                    "throw", "break", "continue", "debugger", "with" -> true;
            default -> false;
        };
    }
}
