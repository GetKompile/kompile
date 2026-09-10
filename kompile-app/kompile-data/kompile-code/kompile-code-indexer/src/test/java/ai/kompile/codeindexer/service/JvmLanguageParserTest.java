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
import ai.kompile.codeindexer.service.CodeEntityExtractor.RelationTriple;
import ai.kompile.codeindexer.service.LanguageParser.ExtractionOutput;
import ai.kompile.codeindexer.service.parsers.JvmLanguageParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JvmLanguageParserTest {

    private JvmLanguageParser parser;

    @BeforeEach
    void setUp() {
        parser = new JvmLanguageParser();
    }

    @Test
    void supportedLanguages() {
        Set<String> langs = parser.supportedLanguages();
        assertTrue(langs.contains("java"));
        assertTrue(langs.contains("kotlin"));
        assertTrue(langs.contains("scala"));
        assertTrue(langs.contains("groovy"));
    }

    @Test
    void parseJavaClass() {
        String java = """
                package com.example;

                import java.util.List;
                import java.util.Map;

                /**
                 * A sample service class.
                 */
                public class UserService {

                    private String name;

                    public UserService(String name) {
                        this.name = name;
                    }

                    public List<String> getUsers() {
                        return List.of("alice", "bob");
                    }

                    private void processUser(String userId) {
                        // implementation
                    }
                }
                """;
        String[] lines = java.split("\n");

        ExtractionOutput output = parser.parse(lines, "UserService.java", "default", "java");

        // Should find: package, imports, class, fields, constructor, methods
        List<CodeEntity> classes = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CLASS).toList();
        assertEquals(1, classes.size(), "Should find 1 class");
        assertEquals("UserService", classes.get(0).getName());

        List<CodeEntity> methods = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.METHOD).toList();
        assertTrue(methods.size() >= 2, "Should find at least 2 methods");

        List<CodeEntity> constructors = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CONSTRUCTOR).toList();
        assertTrue(constructors.size() >= 1, "Should find constructor");

        List<CodeEntity> fields = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.FIELD).toList();
        assertTrue(fields.size() >= 1, "Should find fields");

        List<CodeEntity> imports = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.IMPORT).toList();
        assertTrue(imports.size() >= 2, "Should find imports");

        List<CodeEntity> packages = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.PACKAGE).toList();
        assertTrue(packages.size() >= 1, "Should find package");
    }

    @Test
    void parseJavaInheritance() {
        String java = """
                package com.example;

                public class AdminService extends UserService implements Serializable {
                    public void adminAction() {}
                }
                """;
        String[] lines = java.split("\n");

        ExtractionOutput output = parser.parse(lines, "AdminService.java", "default", "java");

        List<RelationTriple> extends_ = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.EXTENDS).toList();
        assertTrue(extends_.size() >= 1, "Should have EXTENDS relation");

        List<RelationTriple> implements_ = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.IMPLEMENTS).toList();
        assertTrue(implements_.size() >= 1, "Should have IMPLEMENTS relation");
    }

    @Test
    void parseJavaAnnotations() {
        String java = """
                package com.example;

                @Service
                @Transactional
                public class DataService {
                    @Autowired
                    private Repository repo;

                    @Override
                    public void process() {}
                }
                """;
        String[] lines = java.split("\n");

        ExtractionOutput output = parser.parse(lines, "DataService.java", "default", "java");

        List<RelationTriple> annotated = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.ANNOTATED_BY).toList();
        assertTrue(annotated.size() >= 2, "Should have ANNOTATED_BY relations for class annotations");
    }

    @Test
    void parseJavaInterface() {
        String java = """
                package com.example;

                public interface Processor<T> {
                    T process(T input); // { comment is not a body
                    default void init() {}
                }
                """;
        String[] lines = java.split("\n");

        ExtractionOutput output = parser.parse(lines, "Processor.java", "default", "java");

        List<CodeEntity> interfaces = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.INTERFACE).toList();
        assertEquals(1, interfaces.size(), "Should find 1 interface");
        assertEquals("Processor", interfaces.get(0).getName());

        CodeEntity process = output.entities().stream()
                .filter(e -> e.getName().equals("process"))
                .findFirst().orElseThrow();
        CodeEntity init = output.entities().stream()
                .filter(e -> e.getName().equals("init"))
                .findFirst().orElseThrow();
        assertTrue(process.getEndLine() < init.getStartLine(),
                "Bodyless interface method must end before the following default method");
        assertTrue(output.relations().stream().noneMatch(r ->
                r.sourceFqn().equals("com.example.Processor.process")
                        && r.targetFqn().equals("init")
                        && r.relationType() == CodeRelationType.CALLS));
    }

    @Test
    void parseJavaEnum() {
        String java = """
                package com.example;

                public enum Status {
                    ACTIVE,
                    INACTIVE,
                    PENDING;

                    public boolean isActive() { return this == ACTIVE; }
                }
                """;
        String[] lines = java.split("\n");

        ExtractionOutput output = parser.parse(lines, "Status.java", "default", "java");

        List<CodeEntity> enums = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.ENUM).toList();
        assertEquals(1, enums.size(), "Should find 1 enum");
        assertEquals("Status", enums.get(0).getName());
    }

    @Test
    void parseJavaRecord() {
        String java = """
                package com.example;

                public record Point(int x, int y) {
                    public double distance() {
                        return Math.sqrt(x * x + y * y);
                    }
                }
                """;
        String[] lines = java.split("\n");

        ExtractionOutput output = parser.parse(lines, "Point.java", "default", "java");

        List<CodeEntity> records = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.RECORD).toList();
        assertEquals(1, records.size(), "Should find 1 record");
        assertEquals("Point", records.get(0).getName());
    }

    @Test
    void containsRelationsCorrect() {
        String java = """
                package com.example;

                public class Container {
                    public void methodA() {}
                    public void methodB() {}
                }
                """;
        String[] lines = java.split("\n");

        ExtractionOutput output = parser.parse(lines, "Container.java", "default", "java");

        // File CONTAINS class
        List<RelationTriple> fileContainsClass = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.CONTAINS
                        && r.sourceFqn().equals("Container.java"))
                .toList();
        assertTrue(fileContainsClass.size() >= 1, "File should CONTAIN class");

        // Class CONTAINS methods
        List<RelationTriple> classContainsMethods = output.relations().stream()
                .filter(r -> r.relationType() == CodeRelationType.CONTAINS
                        && r.sourceFqn().contains("Container")
                        && !r.sourceFqn().equals("Container.java"))
                .toList();
        assertTrue(classContainsMethods.size() >= 2, "Class should CONTAIN methods");
    }

    @Test
    void methodSignatureExtracted() {
        String java = """
                package com.example;

                public class Service {
                    public String process(int id, String name) { return ""; }
                }
                """;
        String[] lines = java.split("\n");

        ExtractionOutput output = parser.parse(lines, "Service.java", "default", "java");

        List<CodeEntity> methods = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.METHOD).toList();
        assertFalse(methods.isEmpty());
        String sig = methods.get(0).getSignature();
        assertNotNull(sig, "Method should have signature");
        assertTrue(sig.contains("process"), "Signature should contain method name");
    }

    @Test
    void multilineMethodsAndConstructorsAreDefinitionsNotBodyEntities() {
        String java = """
                package com.example;

                public class DeferredService {
                    private DeferredService(
                            String name,
                            int retries) {
                        int constructorLocal = retries;
                    }

                    private int createDeferredEdges(
                            java.util.List<String> deferred,
                            java.util.Map<String, String> ids,
                            Long factSheetId) {
                        int bodyLocal = deferred.size();
                        helperCall(
                                bodyLocal,
                                factSheetId);
                        return bodyLocal;
                    }

                    private void helperCall(int count, Long id) {}
                }
                """;

        ExtractionOutput output = parser.parse(
                java.split("\n"), "DeferredService.java", "default", "java");

        List<CodeEntity> methods = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.METHOD).toList();
        assertTrue(methods.stream().anyMatch(e -> e.getName().equals("createDeferredEdges")));
        assertTrue(methods.stream().anyMatch(e -> e.getName().equals("helperCall")));

        List<CodeEntity> constructors = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CONSTRUCTOR).toList();
        assertEquals(1, constructors.size());
        assertTrue(constructors.get(0).getSignature().contains("retries"));

        assertTrue(output.entities().stream().noneMatch(e ->
                e.getName().equals("bodyLocal") || e.getName().equals("constructorLocal")));
    }

    @Test
    void allmanDeclarationsAndTextBlocksDoNotHideFollowingMethods() {
        String java = String.join("\n",
                "package com.example;",
                "public class FormattingService {",
                "    private FormattingService(",
                "            String name)",
                "    {",
                "        int constructorLocal = name.length();",
                "    }",
                "    public java.util.Map<String, ? extends Number> allman(",
                "            String value)",
                "    throws IllegalStateException",
                "    {",
                "        String braces = \"\"\"",
                "                { text block braces are not code }",
                "                escaped delimiter: \\" + "\"\"\" { still text block }",
                "                \"\"\";",
                "        int bodyLocal = value.length();",
                "        return java.util.Map.of();",
                "    }",
                "    public void afterTextBlock() {}",
                "    public void commentedAllman() // header comment",
                "    {",
                "        int commentedLocal = 1;",
                "    }",
                "    public void afterCommentedAllman() {}",
                "}");

        ExtractionOutput output = parser.parse(
                java.split("\n"), "FormattingService.java", "default", "java");

        assertTrue(output.entities().stream().anyMatch(e -> e.getName().equals("FormattingService")
                && e.getEntityType() == CodeEntityType.CONSTRUCTOR));
        assertTrue(output.entities().stream().anyMatch(e -> e.getName().equals("allman")
                && e.getEntityType() == CodeEntityType.METHOD));
        assertTrue(output.entities().stream().anyMatch(e -> e.getName().equals("afterTextBlock")
                && e.getEntityType() == CodeEntityType.METHOD));
        assertTrue(output.entities().stream().anyMatch(e -> e.getName().equals("commentedAllman")
                && e.getEntityType() == CodeEntityType.METHOD));
        assertTrue(output.entities().stream().anyMatch(e -> e.getName().equals("afterCommentedAllman")
                && e.getEntityType() == CodeEntityType.METHOD));
        assertTrue(output.entities().stream().noneMatch(e ->
                e.getName().equals("bodyLocal") || e.getName().equals("constructorLocal")
                        || e.getName().equals("commentedLocal")));
    }

    @Test
    void nestedTypeScopeRestoresOuterClassForFollowingMethods() {
        String java = """
                package com.example;
                public class OuterService {
                    public record NestedValue(
                            @JsonNames({"value", "alias"}) String value)
                            implements
                            java.io.Serializable {
                        public String normalized() { return value.trim(); }
                        public int length() { return value.length(); }
                    }

                    public void afterNestedType() {}
                }
                """;

        ExtractionOutput output = parser.parse(
                java.split("\n"), "OuterService.java", "default", "java");

        CodeEntity nestedType = output.entities().stream()
                .filter(e -> e.getName().equals("NestedValue"))
                .findFirst().orElseThrow();
        assertEquals("com.example.OuterService.NestedValue",
                nestedType.getFullyQualifiedName());
        assertEquals("com.example.OuterService", nestedType.getParentFqn());

        CodeEntity nestedMethod = output.entities().stream()
                .filter(e -> e.getName().equals("normalized"))
                .findFirst().orElseThrow();
        assertEquals("com.example.OuterService.NestedValue.normalized",
                nestedMethod.getFullyQualifiedName());

        CodeEntity secondNestedMethod = output.entities().stream()
                .filter(e -> e.getName().equals("length"))
                .findFirst().orElseThrow();
        assertEquals("com.example.OuterService.NestedValue.length",
                secondNestedMethod.getFullyQualifiedName());

        CodeEntity outerMethod = output.entities().stream()
                .filter(e -> e.getName().equals("afterNestedType"))
                .findFirst().orElseThrow();
        assertEquals("com.example.OuterService.afterNestedType",
                outerMethod.getFullyQualifiedName());
        assertEquals("com.example.OuterService", outerMethod.getParentFqn());

        assertTrue(output.relations().stream().anyMatch(r ->
                r.sourceFqn().equals("com.example.OuterService")
                        && r.targetFqn().equals("com.example.OuterService.NestedValue")
                        && r.relationType() == CodeRelationType.CONTAINS));
    }

    @Test
    void groovyDefBodiesAndMultilineLiteralsDoNotLeakIntoMembers() {
        String groovy = """
                package com.example
                class GroovyService {
                    def render()
                    {
                        def triple = ''' } still text '''
                        def slashy = / } still text /
                        def dollar = $/ } still text /$
                        def matches = "value" ==~ /}/
                        def combined = /a/ + / } /
                    }
                    def afterRender() {}
                }
                """;

        ExtractionOutput output = parser.parse(
                groovy.split("\n"), "GroovyService.groovy", "default", "groovy");

        CodeEntity after = output.entities().stream()
                .filter(e -> e.getName().equals("afterRender"))
                .findFirst().orElseThrow();
        assertEquals(CodeEntityType.METHOD, after.getEntityType());
        assertEquals("com.example.GroovyService.afterRender", after.getFullyQualifiedName());
        assertTrue(output.entities().stream().noneMatch(e ->
                e.getName().equals("triple") || e.getName().equals("slashy")
                        || e.getName().equals("dollar")));
        assertTrue(output.relations().stream().noneMatch(r ->
                r.sourceFqn().equals("com.example.GroovyService.render")
                        && r.targetFqn().equals("afterRender")
                        && r.relationType() == CodeRelationType.CALLS));
    }

    @Test
    void javadocExtracted() {
        String java = """
                package com.example;

                /**
                 * Provides user management capabilities.
                 * @author admin
                 */
                public class UserManager {
                }
                """;
        String[] lines = java.split("\n");

        ExtractionOutput output = parser.parse(lines, "UserManager.java", "default", "java");

        List<CodeEntity> classes = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CLASS).toList();
        assertFalse(classes.isEmpty());
        String doc = classes.get(0).getDocComment();
        assertNotNull(doc, "Class should have doc comment");
        assertTrue(doc.contains("user management"), "Doc should contain description");
    }

    @Test
    void parseKotlinDataClass() {
        String kotlin = """
                package com.example

                import java.io.Serializable

                data class User(
                    val name: String,
                    val age: Int
                ) : Serializable {
                    fun greet(): String = "Hello, $name"
                }
                """;
        String[] lines = kotlin.split("\n");

        ExtractionOutput output = parser.parse(lines, "User.kt", "default", "kotlin");

        List<CodeEntity> classes = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.CLASS).toList();
        assertTrue(classes.size() >= 1, "Should find data class");
    }
}
