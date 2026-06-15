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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-based language parser for dynamic/scripting languages: Python, Ruby, PHP, Perl, and Lua.
 *
 * <ul>
 *   <li><b>Python</b>: imports, classes with bases, top-level/async defs, methods, decorators,
 *       module-level ALL_CAPS constants. Uses indentation-based block end detection.</li>
 *   <li><b>Ruby</b>: require/require_relative, module/class with inheritance, def methods
 *       (instance and self.class methods), attr_accessor/reader/writer, UPPER_CASE constants.</li>
 *   <li><b>PHP</b>: namespace/use, class/interface/trait/enum, function declarations,
 *       methods with access modifiers, const/define constants.</li>
 *   <li><b>Perl</b>: package/use declarations, sub declarations.</li>
 *   <li><b>Lua</b>: require, function/local function, colon-style and dot-style method declarations.</li>
 * </ul>
 */
@Component
public class ScriptLanguageParser implements LanguageParser {

    // -------------------------------------------------------------------------
    // Python patterns
    // -------------------------------------------------------------------------
    private static final Pattern PY_IMPORT = Pattern.compile(
            "^\\s*(?:from\\s+([\\w.]+)\\s+)?import\\s+(.+)");
    private static final Pattern PY_CLASS = Pattern.compile(
            "^(\\s*)class\\s+(\\w+)(?:\\(([^)]+)\\))?\\s*:");
    private static final Pattern PY_DEF = Pattern.compile(
            "^(\\s*)(?:async\\s+)?def\\s+(\\w+)\\s*\\(([^)]*)\\)");
    private static final Pattern PY_DECORATOR = Pattern.compile(
            "^\\s*@(\\w+(?:\\.\\w+)?)(?:\\([^)]*\\))?");
    /** Module-level constant: ALL_CAPS with optional underscores, assigned with = */
    private static final Pattern PY_CONSTANT = Pattern.compile(
            "^([A-Z][A-Z0-9_]*)\\s*=\\s*(.+)");

    // -------------------------------------------------------------------------
    // Ruby patterns
    // -------------------------------------------------------------------------
    private static final Pattern RB_REQUIRE = Pattern.compile(
            "^\\s*require(?:_relative)?\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern RB_MODULE = Pattern.compile(
            "^\\s*module\\s+(\\w+(?:::\\w+)*)");
    private static final Pattern RB_CLASS = Pattern.compile(
            "^\\s*class\\s+(\\w+(?:::\\w+)*)(?:\\s*<\\s*([\\w:]+))?");
    private static final Pattern RB_DEF = Pattern.compile(
            "^\\s*def\\s+(self\\.)??(\\w+[!?]?)");
    private static final Pattern RB_ATTR = Pattern.compile(
            "^\\s*(attr_accessor|attr_reader|attr_writer)\\s+(.+)");
    /** Ruby constant: starts with uppercase letter */
    private static final Pattern RB_CONSTANT = Pattern.compile(
            "^\\s*([A-Z][A-Z0-9_]*)\\s*=\\s*(.+)");

    // -------------------------------------------------------------------------
    // PHP patterns
    // -------------------------------------------------------------------------
    private static final Pattern PHP_NAMESPACE = Pattern.compile(
            "^\\s*namespace\\s+([\\w\\\\]+)\\s*;");
    private static final Pattern PHP_USE = Pattern.compile(
            "^\\s*use\\s+([\\w\\\\]+(?:\\s+as\\s+\\w+)?)\\s*;");
    private static final Pattern PHP_CLASS = Pattern.compile(
            "^\\s*(?:(abstract|final|readonly)\\s+)?(?:(class|interface|trait|enum))\\s+(\\w+)" +
            "(?:\\s+extends\\s+(\\w+))?(?:\\s+implements\\s+([\\w,\\s]+))?");
    private static final Pattern PHP_FUNCTION = Pattern.compile(
            "^\\s*function\\s+(\\w+)\\s*\\(([^)]*)\\)");
    private static final Pattern PHP_METHOD = Pattern.compile(
            "^\\s*(public|protected|private)(?:\\s+static)?(?:\\s+abstract)?(?:\\s+readonly)?" +
            "\\s+function\\s+(\\w+)\\s*\\(([^)]*)\\)");
    private static final Pattern PHP_CONST = Pattern.compile(
            "^\\s*(?:(?:public|protected|private)\\s+)?const\\s+(\\w+)\\s*=");
    private static final Pattern PHP_DEFINE = Pattern.compile(
            "^\\s*define\\s*\\(\\s*['\"]([^'\"]+)['\"]");

    // -------------------------------------------------------------------------
    // Perl patterns
    // -------------------------------------------------------------------------
    private static final Pattern PERL_PACKAGE = Pattern.compile(
            "^\\s*package\\s+([\\w:]+)\\s*;");
    private static final Pattern PERL_USE = Pattern.compile(
            "^\\s*use\\s+([\\w:]+)");
    private static final Pattern PERL_SUB = Pattern.compile(
            "^\\s*sub\\s+(\\w+)");

    // -------------------------------------------------------------------------
    // Lua patterns
    // -------------------------------------------------------------------------
    private static final Pattern LUA_REQUIRE = Pattern.compile(
            "^\\s*(?:local\\s+\\w+\\s*=\\s*)?require\\s*\\(?\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern LUA_FUNCTION = Pattern.compile(
            "^\\s*(local\\s+)?function\\s+(\\w+)\\s*\\(([^)]*)\\)");
    /** Method with colon syntax: function obj:method() */
    private static final Pattern LUA_METHOD_COLON = Pattern.compile(
            "^\\s*function\\s+(\\w+):(\\w+)\\s*\\(([^)]*)\\)");
    /** Method/function with dot syntax: function obj.method() */
    private static final Pattern LUA_METHOD_DOT = Pattern.compile(
            "^\\s*function\\s+(\\w+)\\.(\\w+)\\s*\\(([^)]*)\\)");

    // -------------------------------------------------------------------------
    // LanguageParser contract
    // -------------------------------------------------------------------------

    @Override
    public Set<String> supportedLanguages() {
        return Set.of("python", "ruby", "php", "perl", "lua");
    }

    @Override
    public ExtractionOutput parse(String[] lines, String filePath, String projectId, String language) {
        List<CodeEntity> entities = new ArrayList<>();
        List<RelationTriple> relations = new ArrayList<>();

        // FILE entity is created by CodeEntityExtractor — not duplicated here.

        switch (language) {
            case "python" -> parsePython(lines, filePath, projectId, entities, relations);
            case "ruby"   -> parseRuby(lines, filePath, projectId, entities, relations);
            case "php"    -> parsePhp(lines, filePath, projectId, entities, relations);
            case "perl"   -> parsePerl(lines, filePath, projectId, entities, relations);
            case "lua"    -> parseLua(lines, filePath, projectId, entities, relations);
            default       -> { /* unreachable given supportedLanguages() check */ }
        }

        return new ExtractionOutput(entities, relations);
    }

    // =========================================================================
    // Python
    // =========================================================================

    private void parsePython(String[] lines, String filePath, String projectId,
                             List<CodeEntity> entities, List<RelationTriple> relations) {
        String currentClass = null;
        int currentClassIndent = -1;
        String pendingDecorator = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            // Decorator — capture for the next def/class
            Matcher decM = PY_DECORATOR.matcher(line);
            if (decM.find()) {
                pendingDecorator = decM.group(1);
                continue;
            }

            // Import / from … import
            Matcher impM = PY_IMPORT.matcher(line);
            if (impM.find()) {
                String fromPkg = impM.group(1);
                String names = impM.group(2).trim();
                String fqn = (fromPkg != null ? fromPkg + "." : "") + names;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.IMPORT)
                        .name(names)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("python")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .build());
                relations.add(new RelationTriple(filePath, fqn, CodeRelationType.IMPORTS));
                pendingDecorator = null;
                continue;
            }

            // Class definition
            Matcher clsM = PY_CLASS.matcher(line);
            if (clsM.find()) {
                String indent = clsM.group(1);
                String className = clsM.group(2);
                String bases = clsM.group(3);
                int classIndent = indent.length();
                int endLine = findPythonBlockEnd(lines, i);

                currentClass = className;
                currentClassIndent = classIndent;

                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.CLASS)
                        .name(className)
                        .fullyQualifiedName(className)
                        .filePath(filePath)
                        .language("python")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(trimmed)
                        .parentFqn(filePath)
                        .build());
                // Peek ahead for a triple-quoted docstring on the next non-blank line
                String pyClassDoc = extractPythonDocstring(lines, i + 1);
                if (pyClassDoc != null) {
                    entities.get(entities.size() - 1).setDocComment(pyClassDoc);
                }
                relations.add(new RelationTriple(filePath, className, CodeRelationType.CONTAINS));
                if (bases != null) {
                    for (String base : bases.split(",")) {
                        String b = base.trim();
                        if (!b.isEmpty()) {
                            relations.add(new RelationTriple(className, b, CodeRelationType.EXTENDS));
                        }
                    }
                }
                pendingDecorator = null;
                continue;
            }

            // def / async def
            Matcher defM = PY_DEF.matcher(line);
            if (defM.find()) {
                String indentStr = defM.group(1);
                String funcName = defM.group(2);
                String params = defM.group(3);
                int defIndent = indentStr.length();
                int endLine = findPythonBlockEnd(lines, i);

                // Determine if method (inside a class) or top-level function
                boolean isMethod = currentClass != null && defIndent > currentClassIndent;
                CodeEntityType type = isMethod ? CodeEntityType.METHOD : CodeEntityType.FUNCTION;
                String parentFqn = isMethod ? currentClass : filePath;
                String fqn = isMethod ? currentClass + "." + funcName : funcName;

                String sig = trimmed;
                if (pendingDecorator != null) {
                    sig = "@" + pendingDecorator + " " + trimmed;
                }

                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(type)
                        .name(funcName)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("python")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(sig)
                        .parentFqn(parentFqn)
                        .build());
                // Peek ahead for a triple-quoted docstring on the next non-blank line
                String pyDefDoc = extractPythonDocstring(lines, i + 1);
                if (pyDefDoc != null) {
                    entities.get(entities.size() - 1).setDocComment(pyDefDoc);
                }
                relations.add(new RelationTriple(parentFqn, fqn, CodeRelationType.CONTAINS));
                pendingDecorator = null;
                continue;
            }

            // Module-level ALL_CAPS constant (indent == 0)
            int lineIndent = getIndent(line);
            if (lineIndent == 0) {
                Matcher cstM = PY_CONSTANT.matcher(line);
                if (cstM.find()) {
                    String constName = cstM.group(1);
                    entities.add(CodeEntity.builder()
                            .projectId(projectId)
                            .entityType(CodeEntityType.CONSTANT)
                            .name(constName)
                            .fullyQualifiedName(constName)
                            .filePath(filePath)
                            .language("python")
                            .startLine(i + 1)
                            .endLine(i + 1)
                            .signature(trimmed)
                            .parentFqn(filePath)
                            .build());
                    relations.add(new RelationTriple(filePath, constName, CodeRelationType.CONTAINS));
                }
            }

            pendingDecorator = null;
        }
    }

    // =========================================================================
    // Ruby
    // =========================================================================

    private void parseRuby(String[] lines, String filePath, String projectId,
                           List<CodeEntity> entities, List<RelationTriple> relations) {
        String currentContainer = null;   // current class or module FQN
        String currentContainerType = null; // "class" or "module"
        StringBuilder rbDocBuffer = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Accumulate consecutive # comment lines as RDoc — reset on blank lines
            if (trimmed.isEmpty()) {
                rbDocBuffer.setLength(0);
                continue;
            }
            if (trimmed.startsWith("#")) {
                // Strip leading "# " or "#" and append
                String commentText = trimmed.length() > 1
                        ? trimmed.substring(trimmed.charAt(1) == ' ' ? 2 : 1)
                        : "";
                if (rbDocBuffer.length() > 0) rbDocBuffer.append('\n');
                rbDocBuffer.append(commentText);
                continue;
            }

            // Capture and reset the accumulated doc comment for the next entity
            String rbDoc = rbDocBuffer.length() > 0 ? rbDocBuffer.toString() : null;
            rbDocBuffer.setLength(0);

            // require / require_relative
            Matcher reqM = RB_REQUIRE.matcher(line);
            if (reqM.find()) {
                String path = reqM.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.IMPORT)
                        .name(path)
                        .fullyQualifiedName(path)
                        .filePath(filePath)
                        .language("ruby")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .build());
                relations.add(new RelationTriple(filePath, path, CodeRelationType.IMPORTS));
                continue;
            }

            // module
            Matcher modM = RB_MODULE.matcher(line);
            if (modM.find()) {
                String modName = modM.group(1);
                int endLine = findRubyBlockEnd(lines, i);
                currentContainer = modName;
                currentContainerType = "module";
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.MODULE)
                        .name(modName)
                        .fullyQualifiedName(modName)
                        .filePath(filePath)
                        .language("ruby")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(trimmed)
                        .parentFqn(filePath)
                        .docComment(rbDoc)
                        .build());
                relations.add(new RelationTriple(filePath, modName, CodeRelationType.CONTAINS));
                continue;
            }

            // class
            Matcher clsM = RB_CLASS.matcher(line);
            if (clsM.find()) {
                String className = clsM.group(1);
                String superclass = clsM.group(2);
                int endLine = findRubyBlockEnd(lines, i);
                currentContainer = className;
                currentContainerType = "class";
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.CLASS)
                        .name(className)
                        .fullyQualifiedName(className)
                        .filePath(filePath)
                        .language("ruby")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(trimmed)
                        .parentFqn(filePath)
                        .docComment(rbDoc)
                        .build());
                relations.add(new RelationTriple(filePath, className, CodeRelationType.CONTAINS));
                if (superclass != null) {
                    relations.add(new RelationTriple(className, superclass, CodeRelationType.EXTENDS));
                }
                continue;
            }

            // def (instance or class method)
            Matcher defM = RB_DEF.matcher(line);
            if (defM.find()) {
                boolean isClassMethod = defM.group(1) != null; // "self."
                String methodName = defM.group(2);
                String parentFqn = currentContainer != null ? currentContainer : filePath;
                String fqn = parentFqn + (isClassMethod ? "." : "#") + methodName;
                int endLine = findRubyBlockEnd(lines, i);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.METHOD)
                        .name(methodName)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("ruby")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(trimmed)
                        .parentFqn(parentFqn)
                        .isStatic(isClassMethod)
                        .docComment(rbDoc)
                        .build());
                relations.add(new RelationTriple(parentFqn, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            // attr_accessor / attr_reader / attr_writer
            Matcher attrM = RB_ATTR.matcher(line);
            if (attrM.find()) {
                String attrKind = attrM.group(1);
                String attrList = attrM.group(2);
                String parentFqn = currentContainer != null ? currentContainer : filePath;
                // Split on commas; symbols look like :name
                for (String sym : attrList.split(",")) {
                    String attrName = sym.trim().replaceAll("^:", "");
                    if (attrName.isEmpty()) continue;
                    String fqn = parentFqn + "#" + attrName;
                    entities.add(CodeEntity.builder()
                            .projectId(projectId)
                            .entityType(CodeEntityType.FIELD)
                            .name(attrName)
                            .fullyQualifiedName(fqn)
                            .filePath(filePath)
                            .language("ruby")
                            .startLine(i + 1)
                            .endLine(i + 1)
                            .signature(attrKind + " :" + attrName)
                            .parentFqn(parentFqn)
                            .build());
                    relations.add(new RelationTriple(parentFqn, fqn, CodeRelationType.CONTAINS));
                }
                continue;
            }

            // UPPER_CASE constant
            Matcher cstM = RB_CONSTANT.matcher(line);
            if (cstM.find()) {
                String constName = cstM.group(1);
                // Exclude class/module keywords that happen to match (they start with upper)
                if (!trimmed.startsWith("class ") && !trimmed.startsWith("module ")) {
                    String parentFqn = currentContainer != null ? currentContainer : filePath;
                    String fqn = parentFqn + "::" + constName;
                    entities.add(CodeEntity.builder()
                            .projectId(projectId)
                            .entityType(CodeEntityType.CONSTANT)
                            .name(constName)
                            .fullyQualifiedName(fqn)
                            .filePath(filePath)
                            .language("ruby")
                            .startLine(i + 1)
                            .endLine(i + 1)
                            .signature(trimmed)
                            .parentFqn(parentFqn)
                            .build());
                    relations.add(new RelationTriple(parentFqn, fqn, CodeRelationType.CONTAINS));
                }
            }
        }
    }

    // =========================================================================
    // PHP
    // =========================================================================

    private void parsePhp(String[] lines, String filePath, String projectId,
                          List<CodeEntity> entities, List<RelationTriple> relations) {
        String namespace = null;
        String currentClass = null;
        StringBuilder phpDocBuffer = new StringBuilder();
        boolean inPhpDocBlock = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#")) {
                if (!inPhpDocBlock) phpDocBuffer.setLength(0);
                continue;
            }

            // PHPDoc block accumulation: /** ... */
            if (!inPhpDocBlock && trimmed.startsWith("/**")) {
                phpDocBuffer.setLength(0);
                inPhpDocBlock = true;
                // The opening /** line may also contain content
                String afterOpen = trimmed.substring(3);
                if (afterOpen.contains("*/")) {
                    // Single-line /** ... */
                    String content = afterOpen.substring(0, afterOpen.indexOf("*/")).trim();
                    content = content.startsWith("*") ? content.substring(1).trim() : content;
                    phpDocBuffer.append(content);
                    inPhpDocBlock = false;
                } else if (!afterOpen.trim().isEmpty()) {
                    phpDocBuffer.append(afterOpen.trim());
                }
                continue;
            }
            if (inPhpDocBlock) {
                if (trimmed.contains("*/")) {
                    // End of block — capture text before */
                    String before = trimmed.substring(0, trimmed.indexOf("*/")).trim();
                    before = before.startsWith("*") ? before.substring(1).trim() : before;
                    if (!before.isEmpty()) {
                        if (phpDocBuffer.length() > 0) phpDocBuffer.append('\n');
                        phpDocBuffer.append(before);
                    }
                    inPhpDocBlock = false;
                } else {
                    // Middle line: strip leading * or whitespace
                    String content = trimmed.startsWith("*") ? trimmed.substring(1).trim() : trimmed;
                    if (phpDocBuffer.length() > 0) phpDocBuffer.append('\n');
                    phpDocBuffer.append(content);
                }
                continue;
            }

            // Capture and reset the accumulated PHPDoc for the next entity
            String phpDocRaw = phpDocBuffer.toString().trim();
            String phpDoc = phpDocRaw.isEmpty() ? null : phpDocRaw;
            phpDocBuffer.setLength(0);

            // Skip regular block comments (not PHPDoc)
            if (trimmed.startsWith("/*")) {
                continue;
            }

            // namespace
            Matcher nsM = PHP_NAMESPACE.matcher(line);
            if (nsM.find()) {
                namespace = nsM.group(1).replace("\\", ".");
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.PACKAGE)
                        .name(namespace)
                        .fullyQualifiedName(namespace)
                        .filePath(filePath)
                        .language("php")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .build());
                relations.add(new RelationTriple(filePath, namespace, CodeRelationType.CONTAINS));
                continue;
            }

            // use
            Matcher useM = PHP_USE.matcher(line);
            if (useM.find()) {
                String imported = useM.group(1).trim();
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.IMPORT)
                        .name(imported)
                        .fullyQualifiedName(imported.replace("\\", "."))
                        .filePath(filePath)
                        .language("php")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .build());
                relations.add(new RelationTriple(filePath, imported, CodeRelationType.IMPORTS));
                continue;
            }

            // class / interface / trait / enum
            Matcher clsM = PHP_CLASS.matcher(line);
            if (clsM.find()) {
                String modifier = clsM.group(1);  // abstract / final / readonly (nullable)
                String keyword = clsM.group(2);   // class / interface / trait / enum
                String className = clsM.group(3);
                String superclass = clsM.group(4);
                String ifaces = clsM.group(5);

                String fqn = (namespace != null ? namespace + "." : "") + className;
                currentClass = fqn;

                CodeEntityType type = switch (keyword) {
                    case "interface" -> CodeEntityType.INTERFACE;
                    case "enum"      -> CodeEntityType.ENUM;
                    case "trait"     -> CodeEntityType.CLASS; // closest analogue
                    default          -> CodeEntityType.CLASS;
                };

                int endLine = findBlockEnd(lines, i);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(type)
                        .name(className)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("php")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(trimmed)
                        .packageName(namespace)
                        .parentFqn(filePath)
                        .isAbstract("abstract".equals(modifier))
                        .docComment(phpDoc)
                        .build());
                relations.add(new RelationTriple(filePath, fqn, CodeRelationType.CONTAINS));
                if (superclass != null) {
                    relations.add(new RelationTriple(fqn, superclass, CodeRelationType.EXTENDS));
                }
                if (ifaces != null) {
                    for (String iface : ifaces.split(",")) {
                        String ifaceName = iface.trim();
                        if (!ifaceName.isEmpty()) {
                            relations.add(new RelationTriple(fqn, ifaceName, CodeRelationType.IMPLEMENTS));
                        }
                    }
                }
                continue;
            }

            // method with access modifier (must be checked before plain function)
            Matcher methodM = PHP_METHOD.matcher(line);
            if (methodM.find() && currentClass != null) {
                String visibility = methodM.group(1);
                String methodName = methodM.group(2);
                String params = methodM.group(3);
                String fqn = currentClass + "::" + methodName;
                int endLine = findBlockEnd(lines, i);
                boolean isStatic = trimmed.contains(" static ");
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.METHOD)
                        .name(methodName)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("php")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(trimmed)
                        .parentFqn(currentClass)
                        .visibility(visibility)
                        .isStatic(isStatic)
                        .docComment(phpDoc)
                        .build());
                relations.add(new RelationTriple(currentClass, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            // standalone function declaration
            Matcher funcM = PHP_FUNCTION.matcher(line);
            if (funcM.find()) {
                String funcName = funcM.group(1);
                String params = funcM.group(2);
                String parentFqn = currentClass != null ? currentClass : filePath;
                String fqn = (currentClass != null ? currentClass + "::" : "") + funcName;
                CodeEntityType type = currentClass != null ? CodeEntityType.METHOD : CodeEntityType.FUNCTION;
                int endLine = findBlockEnd(lines, i);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(type)
                        .name(funcName)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("php")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature("function " + funcName + "(" + params + ")")
                        .parentFqn(parentFqn)
                        .docComment(phpDoc)
                        .build());
                relations.add(new RelationTriple(parentFqn, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            // const NAME = ...
            Matcher constM = PHP_CONST.matcher(line);
            if (constM.find()) {
                String constName = constM.group(1);
                String parentFqn = currentClass != null ? currentClass : filePath;
                String fqn = parentFqn + "::" + constName;
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.CONSTANT)
                        .name(constName)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("php")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature(trimmed)
                        .parentFqn(parentFqn)
                        .build());
                relations.add(new RelationTriple(parentFqn, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            // define('NAME', ...)
            Matcher defineM = PHP_DEFINE.matcher(line);
            if (defineM.find()) {
                String constName = defineM.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.CONSTANT)
                        .name(constName)
                        .fullyQualifiedName(constName)
                        .filePath(filePath)
                        .language("php")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .signature(trimmed)
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, constName, CodeRelationType.CONTAINS));
            }
        }
    }

    // =========================================================================
    // Perl
    // =========================================================================

    private void parsePerl(String[] lines, String filePath, String projectId,
                           List<CodeEntity> entities, List<RelationTriple> relations) {
        String currentPackage = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            // package
            Matcher pkgM = PERL_PACKAGE.matcher(line);
            if (pkgM.find()) {
                currentPackage = pkgM.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.PACKAGE)
                        .name(currentPackage)
                        .fullyQualifiedName(currentPackage)
                        .filePath(filePath)
                        .language("perl")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .build());
                relations.add(new RelationTriple(filePath, currentPackage, CodeRelationType.CONTAINS));
                continue;
            }

            // use
            Matcher useM = PERL_USE.matcher(line);
            if (useM.find()) {
                String module = useM.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.IMPORT)
                        .name(module)
                        .fullyQualifiedName(module)
                        .filePath(filePath)
                        .language("perl")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .build());
                relations.add(new RelationTriple(filePath, module, CodeRelationType.IMPORTS));
                continue;
            }

            // sub
            Matcher subM = PERL_SUB.matcher(line);
            if (subM.find()) {
                String subName = subM.group(1);
                String parentFqn = currentPackage != null ? currentPackage : filePath;
                String fqn = parentFqn + "::" + subName;
                int endLine = findBlockEnd(lines, i);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.FUNCTION)
                        .name(subName)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("perl")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature(trimmed)
                        .parentFqn(parentFqn)
                        .build());
                relations.add(new RelationTriple(parentFqn, fqn, CodeRelationType.CONTAINS));
            }
        }
    }

    // =========================================================================
    // Lua
    // =========================================================================

    private void parseLua(String[] lines, String filePath, String projectId,
                          List<CodeEntity> entities, List<RelationTriple> relations) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }

            // require
            Matcher reqM = LUA_REQUIRE.matcher(line);
            if (reqM.find()) {
                String module = reqM.group(1);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.IMPORT)
                        .name(module)
                        .fullyQualifiedName(module)
                        .filePath(filePath)
                        .language("lua")
                        .startLine(i + 1)
                        .endLine(i + 1)
                        .build());
                relations.add(new RelationTriple(filePath, module, CodeRelationType.IMPORTS));
                continue;
            }

            // function obj:method() — colon syntax (check before plain function)
            Matcher colonM = LUA_METHOD_COLON.matcher(line);
            if (colonM.find()) {
                String obj = colonM.group(1);
                String methodName = colonM.group(2);
                String params = colonM.group(3);
                String fqn = obj + ":" + methodName;
                int endLine = findLuaBlockEnd(lines, i);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.METHOD)
                        .name(methodName)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("lua")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature("function " + fqn + "(" + params + ")")
                        .parentFqn(obj)
                        .build());
                relations.add(new RelationTriple(obj, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            // function obj.method() — dot syntax
            Matcher dotM = LUA_METHOD_DOT.matcher(line);
            if (dotM.find()) {
                String obj = dotM.group(1);
                String methodName = dotM.group(2);
                String params = dotM.group(3);
                String fqn = obj + "." + methodName;
                int endLine = findLuaBlockEnd(lines, i);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.METHOD)
                        .name(methodName)
                        .fullyQualifiedName(fqn)
                        .filePath(filePath)
                        .language("lua")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature("function " + fqn + "(" + params + ")")
                        .parentFqn(obj)
                        .build());
                relations.add(new RelationTriple(obj, fqn, CodeRelationType.CONTAINS));
                continue;
            }

            // function name() / local function name()
            Matcher funcM = LUA_FUNCTION.matcher(line);
            if (funcM.find()) {
                boolean isLocal = funcM.group(1) != null;
                String funcName = funcM.group(2);
                String params = funcM.group(3);
                int endLine = findLuaBlockEnd(lines, i);
                entities.add(CodeEntity.builder()
                        .projectId(projectId)
                        .entityType(CodeEntityType.FUNCTION)
                        .name(funcName)
                        .fullyQualifiedName(funcName)
                        .filePath(filePath)
                        .language("lua")
                        .startLine(i + 1)
                        .endLine(endLine + 1)
                        .signature((isLocal ? "local " : "") + "function " + funcName + "(" + params + ")")
                        .parentFqn(filePath)
                        .build());
                relations.add(new RelationTriple(filePath, funcName, CodeRelationType.CONTAINS));
            }
        }
    }

    // =========================================================================
    // Python docstring helper
    // =========================================================================

    /**
     * Starting at {@code startIdx}, scan forward past blank lines and return the
     * content of a triple-quoted docstring (""" or ''') if one is found on the
     * first non-blank line. Returns {@code null} if no docstring is present.
     *
     * @param lines    source lines array
     * @param startIdx index of the line immediately after the class/def declaration
     */
    private String extractPythonDocstring(String[] lines, int startIdx) {
        // Find first non-blank line after the declaration
        int idx = startIdx;
        while (idx < lines.length && lines[idx].trim().isEmpty()) {
            idx++;
        }
        if (idx >= lines.length) return null;

        String first = lines[idx].trim();
        String quote = null;
        if (first.startsWith("\"\"\"")) {
            quote = "\"\"\"";
        } else if (first.startsWith("'''")) {
            quote = "'''";
        } else {
            return null;
        }

        // Strip the opening triple-quote
        String content = first.substring(3);
        // Check if the docstring closes on the same line
        int closeIdx = content.indexOf(quote);
        if (closeIdx >= 0) {
            return content.substring(0, closeIdx).trim();
        }

        // Multi-line docstring — accumulate until closing triple-quote
        StringBuilder sb = new StringBuilder(content);
        for (int j = idx + 1; j < lines.length; j++) {
            String l = lines[j];
            int ci = l.indexOf(quote);
            if (ci >= 0) {
                sb.append('\n').append(l, 0, ci);
                break;
            } else {
                sb.append('\n').append(l);
            }
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    // =========================================================================
    // Block-end detection helpers
    // =========================================================================

    /**
     * Find end of a brace-delimited block ({...}) starting at or after startLine.
     * Falls back to startLine + 50 if no matching brace is found.
     */
    private int findBlockEnd(String[] lines, int startLine) {
        int braceCount = 0;
        boolean foundOpen = false;
        for (int i = startLine; i < lines.length; i++) {
            for (char c : lines[i].toCharArray()) {
                if (c == '{') { braceCount++; foundOpen = true; }
                if (c == '}') { braceCount--; }
                if (foundOpen && braceCount == 0) return i;
            }
        }
        return Math.min(startLine + 50, lines.length - 1);
    }

    /**
     * Find end of a Python indentation block starting at startLine.
     * The block ends when a non-blank line with indent <= the start line's indent is encountered.
     */
    private int findPythonBlockEnd(String[] lines, int startLine) {
        int baseIndent = getIndent(lines[startLine]);
        for (int i = startLine + 1; i < lines.length; i++) {
            String l = lines[i];
            if (l.trim().isEmpty()) continue;
            if (getIndent(l) <= baseIndent) return i - 1;
        }
        return lines.length - 1;
    }

    /**
     * Find end of a Ruby keyword-delimited block.
     * Counts do/def/class/module/if/unless/while/for/begin as openers and 'end' as closer.
     */
    private int findRubyBlockEnd(String[] lines, int startLine) {
        int depth = 0;
        for (int i = startLine; i < lines.length; i++) {
            String t = lines[i].trim();
            // Count block-opening keywords on this line
            depth += countRubyOpeners(t);
            if (t.equals("end") || t.startsWith("end ") || t.startsWith("end#")) {
                depth--;
                if (depth <= 0) return i;
            } else if (t.endsWith(" end") || t.contains("; end")) {
                // inline end (e.g. "def foo; bar; end")
                depth--;
                if (depth <= 0) return i;
            }
        }
        return Math.min(startLine + 100, lines.length - 1);
    }

    /** Count block-opening Ruby keywords on a single trimmed line. */
    private int countRubyOpeners(String trimmed) {
        int count = 0;
        if (trimmed.startsWith("def ") || trimmed.equals("def") ||
            trimmed.startsWith("class ") || trimmed.startsWith("module ") ||
            trimmed.startsWith("do ") || trimmed.equals("do") || trimmed.endsWith(" do") ||
            trimmed.startsWith("if ") || trimmed.startsWith("unless ") ||
            trimmed.startsWith("while ") || trimmed.startsWith("for ") ||
            trimmed.startsWith("begin") || trimmed.startsWith("case ")) {
            count++;
        }
        return count;
    }

    /**
     * Find end of a Lua block using keyword matching.
     * Counts function/do/if/while/for/repeat as openers and 'end' as closer.
     */
    private int findLuaBlockEnd(String[] lines, int startLine) {
        int depth = 0;
        for (int i = startLine; i < lines.length; i++) {
            String t = lines[i].trim();
            depth += countLuaOpeners(t);
            if (t.equals("end") || t.startsWith("end ") || t.endsWith(" end") || t.contains("; end")) {
                depth--;
                if (depth <= 0) return i;
            }
        }
        return Math.min(startLine + 100, lines.length - 1);
    }

    /** Count block-opening Lua keywords on a single trimmed line. */
    private int countLuaOpeners(String trimmed) {
        int count = 0;
        if (trimmed.startsWith("function ") || trimmed.startsWith("local function ") ||
            trimmed.startsWith("do ") || trimmed.equals("do") ||
            trimmed.startsWith("if ") || trimmed.startsWith("while ") ||
            trimmed.startsWith("for ") || trimmed.startsWith("repeat")) {
            count++;
        }
        return count;
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    /** Count leading whitespace characters, treating one tab as 4 spaces. */
    private int getIndent(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') count++;
            else if (c == '\t') count += 4;
            else break;
        }
        return count;
    }

    /** Extract the filename portion from a file path string. */
    private String fileNameFrom(String filePath) {
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return slash >= 0 ? filePath.substring(slash + 1) : filePath;
    }
}
