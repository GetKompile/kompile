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
                    T process(T input);
                    default void init() {}
                }
                """;
        String[] lines = java.split("\n");

        ExtractionOutput output = parser.parse(lines, "Processor.java", "default", "java");

        List<CodeEntity> interfaces = output.entities().stream()
                .filter(e -> e.getEntityType() == CodeEntityType.INTERFACE).toList();
        assertEquals(1, interfaces.size(), "Should find 1 interface");
        assertEquals("Processor", interfaces.get(0).getName());
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
