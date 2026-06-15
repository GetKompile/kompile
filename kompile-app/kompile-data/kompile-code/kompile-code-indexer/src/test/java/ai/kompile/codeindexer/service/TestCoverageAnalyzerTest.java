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

import ai.kompile.codeindexer.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestCoverageAnalyzerTest {

    @Mock private CodeEntityRepository entityRepository;
    @Mock private CodeRelationRepository relationRepository;

    private TestCoverageAnalyzer analyzer;

    private static final String PROJECT = "test-project";

    @BeforeEach
    void setUp() {
        analyzer = new TestCoverageAnalyzer(entityRepository, relationRepository);
    }

    // ── Helper builders ─────────────────────────────────────────────────

    private static CodeEntity entity(String name, String fqn, CodeEntityType type,
                                      String filePath, String language) {
        return CodeEntity.builder()
                .id(UUID.randomUUID())
                .projectId(PROJECT)
                .name(name)
                .fullyQualifiedName(fqn)
                .entityType(type)
                .filePath(filePath)
                .language(language)
                .indexedAt(Instant.now())
                .build();
    }

    private static CodeEntity entityWithSig(String name, String fqn, CodeEntityType type,
                                             String filePath, String language, String signature) {
        CodeEntity e = entity(name, fqn, type, filePath, language);
        e.setSignature(signature);
        return e;
    }

    private static CodeEntity entityWithParent(String name, String fqn, CodeEntityType type,
                                                String filePath, String language, String parentFqn) {
        CodeEntity e = entity(name, fqn, type, filePath, language);
        e.setParentFqn(parentFqn);
        return e;
    }

    private static CodeRelation relation(String sourceFqn, String targetName,
                                          String targetFqn, CodeRelationType type) {
        return CodeRelation.builder()
                .id(UUID.randomUUID())
                .projectId(PROJECT)
                .sourceFqn(sourceFqn)
                .targetName(targetName)
                .targetFqn(targetFqn)
                .relationType(type)
                .indexedAt(Instant.now())
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  detectTestFrameworks
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void detectTestFrameworks_junit5ViaAnnotation() {
        // A Java test method annotated with @Test
        CodeEntity testMethod = entity("testFoo", "com.example.FooTest.testFoo",
                CodeEntityType.METHOD, "src/test/java/FooTest.java", "java");
        CodeRelation annotRel = relation("com.example.FooTest.testFoo", "Test",
                null, CodeRelationType.ANNOTATED_BY);

        when(entityRepository.findDistinctLanguagesByProjectId(PROJECT))
                .thenReturn(List.of("java"));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.ANNOTATED_BY))
                .thenReturn(List.of(annotRel));
        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "com.example.FooTest.testFoo"))
                .thenReturn(Optional.of(testMethod));
        when(entityRepository.findByProjectId(PROJECT))
                .thenReturn(List.of(testMethod));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.EXTENDS))
                .thenReturn(List.of());

        Map<String, Object> result = analyzer.detectTestFrameworks(PROJECT);

        assertEquals(PROJECT, result.get("projectId"));
        @SuppressWarnings("unchecked")
        Map<String, Set<String>> frameworks = (Map<String, Set<String>>) result.get("frameworksByLanguage");
        assertNotNull(frameworks);
        assertTrue(frameworks.containsKey("java"));
        assertTrue(frameworks.get("java").contains("JUnit5"));
    }

    @Test
    void detectTestFrameworks_goByFileConvention() {
        CodeEntity goTestFile = entity("calculator_test.go", "calculator_test.go",
                CodeEntityType.FILE, "pkg/math/calculator_test.go", "go");

        when(entityRepository.findDistinctLanguagesByProjectId(PROJECT))
                .thenReturn(List.of("go"));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.ANNOTATED_BY))
                .thenReturn(List.of());
        when(entityRepository.findByProjectId(PROJECT))
                .thenReturn(List.of(goTestFile));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.EXTENDS))
                .thenReturn(List.of());

        Map<String, Object> result = analyzer.detectTestFrameworks(PROJECT);

        @SuppressWarnings("unchecked")
        Map<String, Set<String>> frameworks = (Map<String, Set<String>>) result.get("frameworksByLanguage");
        assertNotNull(frameworks);
        assertTrue(frameworks.containsKey("go"));
        assertTrue(frameworks.get("go").contains("go test"));
    }

    @Test
    void detectTestFrameworks_pytestByFixtureAnnotation() {
        CodeEntity pyFunc = entity("test_setup", "conftest.test_setup",
                CodeEntityType.FUNCTION, "tests/conftest.py", "python");
        CodeRelation annotRel = relation("conftest.test_setup", "fixture",
                null, CodeRelationType.ANNOTATED_BY);

        when(entityRepository.findDistinctLanguagesByProjectId(PROJECT))
                .thenReturn(List.of("python"));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.ANNOTATED_BY))
                .thenReturn(List.of(annotRel));
        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "conftest.test_setup"))
                .thenReturn(Optional.of(pyFunc));
        when(entityRepository.findByProjectId(PROJECT))
                .thenReturn(List.of(pyFunc));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.EXTENDS))
                .thenReturn(List.of());

        Map<String, Object> result = analyzer.detectTestFrameworks(PROJECT);

        @SuppressWarnings("unchecked")
        Map<String, Set<String>> frameworks = (Map<String, Set<String>>) result.get("frameworksByLanguage");
        assertNotNull(frameworks);
        assertTrue(frameworks.containsKey("python"));
        assertTrue(frameworks.get("python").contains("pytest"));
    }

    @Test
    void detectTestFrameworks_unittestViaBaseClass() {
        CodeEntity testClass = entity("MyTests", "test_module.MyTests",
                CodeEntityType.CLASS, "tests/test_module.py", "python");
        CodeRelation extendsRel = relation("test_module.MyTests", "TestCase",
                "unittest.TestCase", CodeRelationType.EXTENDS);

        when(entityRepository.findDistinctLanguagesByProjectId(PROJECT))
                .thenReturn(List.of("python"));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.ANNOTATED_BY))
                .thenReturn(List.of());
        when(entityRepository.findByProjectId(PROJECT))
                .thenReturn(List.of(testClass));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.EXTENDS))
                .thenReturn(List.of(extendsRel));
        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "test_module.MyTests"))
                .thenReturn(Optional.of(testClass));

        Map<String, Object> result = analyzer.detectTestFrameworks(PROJECT);

        @SuppressWarnings("unchecked")
        Map<String, Set<String>> frameworks = (Map<String, Set<String>>) result.get("frameworksByLanguage");
        assertNotNull(frameworks);
        assertTrue(frameworks.containsKey("python"));
        assertTrue(frameworks.get("python").contains("unittest"));
    }

    @Test
    void detectTestFrameworks_jestByFilePath() {
        CodeEntity tsSpec = entity("App.spec.ts", "App.spec.ts",
                CodeEntityType.FILE, "src/__tests__/App.spec.ts", "typescript");

        when(entityRepository.findDistinctLanguagesByProjectId(PROJECT))
                .thenReturn(List.of("typescript"));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.ANNOTATED_BY))
                .thenReturn(List.of());
        when(entityRepository.findByProjectId(PROJECT))
                .thenReturn(List.of(tsSpec));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.EXTENDS))
                .thenReturn(List.of());

        Map<String, Object> result = analyzer.detectTestFrameworks(PROJECT);

        @SuppressWarnings("unchecked")
        Map<String, Set<String>> frameworks = (Map<String, Set<String>>) result.get("frameworksByLanguage");
        assertNotNull(frameworks);
        assertTrue(frameworks.containsKey("typescript"));
        assertTrue(frameworks.get("typescript").stream()
                .anyMatch(f -> f.contains("Jest")));
    }

    @Test
    void detectTestFrameworks_csharpXunit() {
        CodeEntity testMethod = entity("CanAdd", "Tests.CalculatorTests.CanAdd",
                CodeEntityType.METHOD, "Tests/CalculatorTests.cs", "csharp");
        CodeRelation factAnnot = relation("Tests.CalculatorTests.CanAdd", "Fact",
                null, CodeRelationType.ANNOTATED_BY);

        when(entityRepository.findDistinctLanguagesByProjectId(PROJECT))
                .thenReturn(List.of("csharp"));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.ANNOTATED_BY))
                .thenReturn(List.of(factAnnot));
        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "Tests.CalculatorTests.CanAdd"))
                .thenReturn(Optional.of(testMethod));
        when(entityRepository.findByProjectId(PROJECT))
                .thenReturn(List.of(testMethod));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.EXTENDS))
                .thenReturn(List.of());

        Map<String, Object> result = analyzer.detectTestFrameworks(PROJECT);

        @SuppressWarnings("unchecked")
        Map<String, Set<String>> frameworks = (Map<String, Set<String>>) result.get("frameworksByLanguage");
        assertNotNull(frameworks);
        assertTrue(frameworks.containsKey("csharp"));
        assertTrue(frameworks.get("csharp").contains("xUnit"));
    }

    @Test
    void detectTestFrameworks_emptyProject() {
        when(entityRepository.findDistinctLanguagesByProjectId(PROJECT)).thenReturn(List.of());
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.ANNOTATED_BY))
                .thenReturn(List.of());
        when(entityRepository.findByProjectId(PROJECT)).thenReturn(List.of());
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.EXTENDS))
                .thenReturn(List.of());

        Map<String, Object> result = analyzer.detectTestFrameworks(PROJECT);

        @SuppressWarnings("unchecked")
        Map<String, Set<String>> frameworks = (Map<String, Set<String>>) result.get("frameworksByLanguage");
        assertTrue(frameworks.isEmpty());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  classifyEntities
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void classifyEntities_annotatedTestMethod() {
        CodeEntity testMethod = entity("testCreate", "com.example.FooTest.testCreate",
                CodeEntityType.METHOD, "src/test/java/FooTest.java", "java");
        CodeEntity prodMethod = entity("create", "com.example.Foo.create",
                CodeEntityType.METHOD, "src/main/java/Foo.java", "java");
        CodeRelation testAnnot = relation("com.example.FooTest.testCreate", "Test",
                null, CodeRelationType.ANNOTATED_BY);

        when(entityRepository.findByProjectId(PROJECT))
                .thenReturn(List.of(testMethod, prodMethod));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.ANNOTATED_BY))
                .thenReturn(List.of(testAnnot));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.EXTENDS))
                .thenReturn(List.of());

        Map<String, Object> result = analyzer.classifyEntities(PROJECT);

        assertEquals(1, result.get("testCount"));
        assertEquals(1, result.get("productionCount"));
    }

    @Test
    void classifyEntities_testFileHeuristic() {
        CodeEntity specFunc = entity("it_should_work", "app.spec.it_should_work",
                CodeEntityType.FUNCTION, "src/__tests__/app.spec.js", "javascript");
        CodeEntity prodFunc = entity("processData", "utils.processData",
                CodeEntityType.FUNCTION, "src/utils.js", "javascript");

        when(entityRepository.findByProjectId(PROJECT))
                .thenReturn(List.of(specFunc, prodFunc));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.ANNOTATED_BY))
                .thenReturn(List.of());
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.EXTENDS))
                .thenReturn(List.of());

        Map<String, Object> result = analyzer.classifyEntities(PROJECT);

        assertEquals(1, result.get("testCount"));
        assertEquals(1, result.get("productionCount"));
    }

    @Test
    void classifyEntities_pythonTestPrefixConvention() {
        CodeEntity pyTest = entity("test_add", "math_test.test_add",
                CodeEntityType.FUNCTION, "src/math.py", "python");
        CodeEntity pyProd = entity("add", "math.add",
                CodeEntityType.FUNCTION, "src/math.py", "python");

        when(entityRepository.findByProjectId(PROJECT))
                .thenReturn(List.of(pyTest, pyProd));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.ANNOTATED_BY))
                .thenReturn(List.of());
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.EXTENDS))
                .thenReturn(List.of());

        Map<String, Object> result = analyzer.classifyEntities(PROJECT);

        assertEquals(1, result.get("testCount"));
        assertEquals(1, result.get("productionCount"));
    }

    @Test
    void classifyEntities_goTestPrefix() {
        CodeEntity goTest = entity("TestCalculate", "calc.TestCalculate",
                CodeEntityType.FUNCTION, "pkg/calc/calc.go", "go");
        CodeEntity goProd = entity("Calculate", "calc.Calculate",
                CodeEntityType.FUNCTION, "pkg/calc/calc.go", "go");

        when(entityRepository.findByProjectId(PROJECT))
                .thenReturn(List.of(goTest, goProd));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.ANNOTATED_BY))
                .thenReturn(List.of());
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.EXTENDS))
                .thenReturn(List.of());

        Map<String, Object> result = analyzer.classifyEntities(PROJECT);

        assertEquals(1, result.get("testCount"));
        assertEquals(1, result.get("productionCount"));
    }

    @Test
    void classifyEntities_parentInTestClass() {
        // A method whose parent class is annotated with @Test becomes test
        CodeEntity testClass = entity("FooTest", "com.example.FooTest",
                CodeEntityType.CLASS, "src/test/java/FooTest.java", "java");
        CodeEntity helperMethod = entityWithParent("helper", "com.example.FooTest.helper",
                CodeEntityType.METHOD, "src/test/java/FooTest.java", "java",
                "com.example.FooTest");
        CodeRelation classAnnot = relation("com.example.FooTest", "Test",
                null, CodeRelationType.ANNOTATED_BY);

        when(entityRepository.findByProjectId(PROJECT))
                .thenReturn(List.of(testClass, helperMethod));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.ANNOTATED_BY))
                .thenReturn(List.of(classAnnot));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.EXTENDS))
                .thenReturn(List.of());

        Map<String, Object> result = analyzer.classifyEntities(PROJECT);

        // Both are classified as test (class by annotation, method by parent)
        assertEquals(2, result.get("testCount"));
        assertEquals(0, result.get("productionCount"));
    }

    @Test
    void classifyEntities_signatureContainsTestAnnotation() {
        CodeEntity method = entityWithSig("myTest", "com.example.Foo.myTest",
                CodeEntityType.METHOD, "src/main/java/Foo.java", "java",
                "@Test public void myTest()");

        when(entityRepository.findByProjectId(PROJECT))
                .thenReturn(List.of(method));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.ANNOTATED_BY))
                .thenReturn(List.of());
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.EXTENDS))
                .thenReturn(List.of());

        Map<String, Object> result = analyzer.classifyEntities(PROJECT);

        assertEquals(1, result.get("testCount"));
        assertEquals(0, result.get("productionCount"));
    }

    @Test
    void classifyEntities_skipsStructuralEntities() {
        CodeEntity file = entity("Foo.java", "Foo.java",
                CodeEntityType.FILE, "src/main/java/Foo.java", "java");
        CodeEntity imp = entity("java.util.List", "Foo.java::java.util.List",
                CodeEntityType.IMPORT, "src/main/java/Foo.java", "java");
        CodeEntity pkg = entity("com.example", "com.example",
                CodeEntityType.PACKAGE, "src/main/java/com/example", "java");

        when(entityRepository.findByProjectId(PROJECT))
                .thenReturn(List.of(file, imp, pkg));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.ANNOTATED_BY))
                .thenReturn(List.of());
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.EXTENDS))
                .thenReturn(List.of());

        Map<String, Object> result = analyzer.classifyEntities(PROJECT);

        // FILE, IMPORT, PACKAGE are skipped — no test or production entities
        assertEquals(0, result.get("testCount"));
        assertEquals(0, result.get("productionCount"));
    }

    @Test
    void classifyEntities_testToProductionRatio() {
        CodeEntity t1 = entity("test1", "t.test1", CodeEntityType.METHOD,
                "src/test/java/T.java", "java");
        CodeEntity t2 = entity("test2", "t.test2", CodeEntityType.METHOD,
                "src/test/java/T.java", "java");
        CodeEntity p1 = entity("prod1", "p.prod1", CodeEntityType.METHOD,
                "src/main/java/P.java", "java");
        CodeEntity p2 = entity("prod2", "p.prod2", CodeEntityType.METHOD,
                "src/main/java/P.java", "java");
        CodeEntity p3 = entity("prod3", "p.prod3", CodeEntityType.METHOD,
                "src/main/java/P.java", "java");
        CodeEntity p4 = entity("prod4", "p.prod4", CodeEntityType.METHOD,
                "src/main/java/P.java", "java");

        when(entityRepository.findByProjectId(PROJECT))
                .thenReturn(List.of(t1, t2, p1, p2, p3, p4));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.ANNOTATED_BY))
                .thenReturn(List.of());
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.EXTENDS))
                .thenReturn(List.of());

        Map<String, Object> result = analyzer.classifyEntities(PROJECT);

        assertEquals(2, result.get("testCount"));
        assertEquals(4, result.get("productionCount"));
        assertEquals(0.5, result.get("testToProductionRatio"));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  getTestsForSymbol
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void getTestsForSymbol_directTestCaller() {
        CodeEntity prodMethod = entity("processOrder", "com.example.OrderService.processOrder",
                CodeEntityType.METHOD, "src/main/java/OrderService.java", "java");
        CodeEntity testMethod = entity("testProcessOrder", "com.example.OrderServiceTest.testProcessOrder",
                CodeEntityType.METHOD, "src/test/java/OrderServiceTest.java", "java");

        CodeRelation callRel = relation("com.example.OrderServiceTest.testProcessOrder",
                "processOrder", "com.example.OrderService.processOrder", CodeRelationType.CALLS);
        CodeRelation testAnnot = relation("com.example.OrderServiceTest.testProcessOrder",
                "Test", null, CodeRelationType.ANNOTATED_BY);

        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "com.example.OrderService.processOrder"))
                .thenReturn(Optional.of(prodMethod));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.ANNOTATED_BY))
                .thenReturn(List.of(testAnnot));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.EXTENDS))
                .thenReturn(List.of());
        when(relationRepository.findCallsToName(PROJECT, "processOrder"))
                .thenReturn(List.of(callRel));
        when(relationRepository.findByProjectIdAndTargetFqnAndRelationType(
                PROJECT, "com.example.OrderService.processOrder", CodeRelationType.CALLS))
                .thenReturn(List.of());
        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "com.example.OrderServiceTest.testProcessOrder"))
                .thenReturn(Optional.of(testMethod));
        // Second-hop callers
        when(relationRepository.findCallsToName(PROJECT, "testProcessOrder"))
                .thenReturn(List.of());

        Map<String, Object> result = analyzer.getTestsForSymbol(PROJECT, "com.example.OrderService.processOrder");

        assertTrue((Boolean) result.get("tested"));
        assertEquals(1, result.get("directTestCount"));
        assertEquals(0, result.get("indirectTestCount"));
    }

    @Test
    void getTestsForSymbol_indirectTest() {
        CodeEntity prodMethod = entity("save", "repo.UserRepo.save",
                CodeEntityType.METHOD, "src/main/java/UserRepo.java", "java");
        CodeEntity serviceMethod = entity("createUser", "svc.UserService.createUser",
                CodeEntityType.METHOD, "src/main/java/UserService.java", "java");
        CodeEntity testMethod = entity("testCreate", "test.UserServiceTest.testCreate",
                CodeEntityType.METHOD, "src/test/java/UserServiceTest.java", "java");

        // testCreate -> createUser -> save
        CodeRelation callToSave = relation("svc.UserService.createUser",
                "save", "repo.UserRepo.save", CodeRelationType.CALLS);
        CodeRelation callToCreateUser = relation("test.UserServiceTest.testCreate",
                "createUser", "svc.UserService.createUser", CodeRelationType.CALLS);
        CodeRelation testAnnot = relation("test.UserServiceTest.testCreate",
                "Test", null, CodeRelationType.ANNOTATED_BY);

        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "repo.UserRepo.save"))
                .thenReturn(Optional.of(prodMethod));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.ANNOTATED_BY))
                .thenReturn(List.of(testAnnot));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.EXTENDS))
                .thenReturn(List.of());
        when(relationRepository.findCallsToName(PROJECT, "save"))
                .thenReturn(List.of(callToSave));
        when(relationRepository.findByProjectIdAndTargetFqnAndRelationType(
                PROJECT, "repo.UserRepo.save", CodeRelationType.CALLS))
                .thenReturn(List.of());
        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "svc.UserService.createUser"))
                .thenReturn(Optional.of(serviceMethod));
        // Second hop: who calls createUser?
        when(relationRepository.findCallsToName(PROJECT, "createUser"))
                .thenReturn(List.of(callToCreateUser));
        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "test.UserServiceTest.testCreate"))
                .thenReturn(Optional.of(testMethod));

        Map<String, Object> result = analyzer.getTestsForSymbol(PROJECT, "repo.UserRepo.save");

        assertTrue((Boolean) result.get("tested"));
        assertEquals(0, result.get("directTestCount"));
        assertEquals(1, result.get("indirectTestCount"));
    }

    @Test
    void getTestsForSymbol_untested() {
        CodeEntity prodMethod = entity("doStuff", "com.Foo.doStuff",
                CodeEntityType.METHOD, "src/main/java/Foo.java", "java");

        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "com.Foo.doStuff"))
                .thenReturn(Optional.of(prodMethod));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.ANNOTATED_BY))
                .thenReturn(List.of());
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.EXTENDS))
                .thenReturn(List.of());
        when(relationRepository.findCallsToName(PROJECT, "doStuff"))
                .thenReturn(List.of());
        when(relationRepository.findByProjectIdAndTargetFqnAndRelationType(
                PROJECT, "com.Foo.doStuff", CodeRelationType.CALLS))
                .thenReturn(List.of());

        Map<String, Object> result = analyzer.getTestsForSymbol(PROJECT, "com.Foo.doStuff");

        assertFalse((Boolean) result.get("tested"));
        assertEquals(0, result.get("directTestCount"));
        assertEquals(0, result.get("indirectTestCount"));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  getTestCoverageReport
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void getTestCoverageReport_calculatesPercentCorrectly() {
        // 2 production methods, 1 tested by a test method calling it
        CodeEntity prodA = entity("methodA", "svc.Svc.methodA",
                CodeEntityType.METHOD, "src/main/java/Svc.java", "java");
        CodeEntity prodB = entity("methodB", "svc.Svc.methodB",
                CodeEntityType.METHOD, "src/main/java/Svc.java", "java");
        CodeEntity testM = entity("testA", "test.SvcTest.testA",
                CodeEntityType.METHOD, "src/test/java/SvcTest.java", "java");

        CodeRelation testAnnot = relation("test.SvcTest.testA", "Test",
                null, CodeRelationType.ANNOTATED_BY);
        CodeRelation testCallsA = relation("test.SvcTest.testA", "methodA",
                "svc.Svc.methodA", CodeRelationType.CALLS);

        when(entityRepository.findByProjectId(PROJECT))
                .thenReturn(List.of(prodA, prodB, testM));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.ANNOTATED_BY))
                .thenReturn(List.of(testAnnot));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.EXTENDS))
                .thenReturn(List.of());
        when(relationRepository.findByProjectIdAndSourceFqnAndRelationType(
                PROJECT, "test.SvcTest.testA", CodeRelationType.CALLS))
                .thenReturn(List.of(testCallsA));
        // For framework detection (called internally)
        when(entityRepository.findDistinctLanguagesByProjectId(PROJECT))
                .thenReturn(List.of("java"));
        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "test.SvcTest.testA"))
                .thenReturn(Optional.of(testM));

        Map<String, Object> result = analyzer.getTestCoverageReport(PROJECT);

        assertEquals(1, result.get("testCount"));
        assertEquals(2, result.get("productionMethodCount"));
        assertEquals(1, result.get("testedMethodCount"));
        assertEquals(1, result.get("untestedMethodCount"));
        assertEquals(50.0, result.get("coveragePercent"));
    }

    @Test
    void getTestCoverageReport_zeroCoverageWhenNoTests() {
        CodeEntity prod = entity("run", "app.Main.run",
                CodeEntityType.METHOD, "src/main/java/Main.java", "java");

        when(entityRepository.findByProjectId(PROJECT))
                .thenReturn(List.of(prod));
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.ANNOTATED_BY))
                .thenReturn(List.of());
        when(relationRepository.findByProjectIdAndRelationType(PROJECT, CodeRelationType.EXTENDS))
                .thenReturn(List.of());
        when(entityRepository.findDistinctLanguagesByProjectId(PROJECT))
                .thenReturn(List.of("java"));

        Map<String, Object> result = analyzer.getTestCoverageReport(PROJECT);

        assertEquals(0, result.get("testCount"));
        assertEquals(1, result.get("productionMethodCount"));
        assertEquals(0, result.get("testedMethodCount"));
        assertEquals(1, result.get("untestedMethodCount"));
        assertEquals(0.0, result.get("coveragePercent"));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  traceCodePaths
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void traceCodePaths_singleDepth() {
        CodeEntity entryPoint = entity("main", "app.Main.main",
                CodeEntityType.METHOD, "src/main/java/Main.java", "java");
        CodeEntity called1 = entity("init", "app.App.init",
                CodeEntityType.METHOD, "src/main/java/App.java", "java");
        CodeEntity called2 = entity("run", "app.App.run",
                CodeEntityType.METHOD, "src/main/java/App.java", "java");

        CodeRelation call1 = relation("app.Main.main", "init", "app.App.init", CodeRelationType.CALLS);
        CodeRelation call2 = relation("app.Main.main", "run", "app.App.run", CodeRelationType.CALLS);

        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "app.Main.main"))
                .thenReturn(Optional.of(entryPoint));
        when(relationRepository.findByProjectIdAndSourceFqnAndRelationType(
                PROJECT, "app.Main.main", CodeRelationType.CALLS))
                .thenReturn(List.of(call1, call2));
        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "app.App.init"))
                .thenReturn(Optional.of(called1));
        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "app.App.run"))
                .thenReturn(Optional.of(called2));
        // Depth 2 - no further calls
        when(relationRepository.findByProjectIdAndSourceFqnAndRelationType(
                PROJECT, "app.App.init", CodeRelationType.CALLS))
                .thenReturn(List.of());
        when(relationRepository.findByProjectIdAndSourceFqnAndRelationType(
                PROJECT, "app.App.run", CodeRelationType.CALLS))
                .thenReturn(List.of());

        Map<String, Object> result = analyzer.traceCodePaths(PROJECT, "app.Main.main", 3);

        assertEquals(2, result.get("totalReachable"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> layers = (List<Map<String, Object>>) result.get("layers");
        assertEquals(1, layers.size());
        assertEquals(1, layers.get(0).get("depth"));
        assertEquals(2, layers.get(0).get("count"));
    }

    @Test
    void traceCodePaths_multipleDepths() {
        CodeEntity a = entity("a", "pkg.A.a", CodeEntityType.METHOD,
                "src/A.java", "java");
        CodeEntity b = entity("b", "pkg.B.b", CodeEntityType.METHOD,
                "src/B.java", "java");
        CodeEntity c = entity("c", "pkg.C.c", CodeEntityType.METHOD,
                "src/C.java", "java");

        CodeRelation aCallsB = relation("pkg.A.a", "b", "pkg.B.b", CodeRelationType.CALLS);
        CodeRelation bCallsC = relation("pkg.B.b", "c", "pkg.C.c", CodeRelationType.CALLS);

        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "pkg.A.a"))
                .thenReturn(Optional.of(a));
        when(relationRepository.findByProjectIdAndSourceFqnAndRelationType(
                PROJECT, "pkg.A.a", CodeRelationType.CALLS))
                .thenReturn(List.of(aCallsB));
        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "pkg.B.b"))
                .thenReturn(Optional.of(b));
        when(relationRepository.findByProjectIdAndSourceFqnAndRelationType(
                PROJECT, "pkg.B.b", CodeRelationType.CALLS))
                .thenReturn(List.of(bCallsC));
        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "pkg.C.c"))
                .thenReturn(Optional.of(c));
        when(relationRepository.findByProjectIdAndSourceFqnAndRelationType(
                PROJECT, "pkg.C.c", CodeRelationType.CALLS))
                .thenReturn(List.of());

        Map<String, Object> result = analyzer.traceCodePaths(PROJECT, "pkg.A.a", 5);

        assertEquals(2, result.get("totalReachable"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> layers = (List<Map<String, Object>>) result.get("layers");
        assertEquals(2, layers.size());
        assertEquals(1, layers.get(0).get("depth"));
        assertEquals(1, ((Number) layers.get(0).get("count")).intValue());
        assertEquals(2, layers.get(1).get("depth"));
        assertEquals(1, ((Number) layers.get(1).get("count")).intValue());
    }

    @Test
    void traceCodePaths_respectsMaxDepth() {
        CodeEntity a = entity("a", "pkg.A.a", CodeEntityType.METHOD,
                "src/A.java", "java");
        CodeEntity b = entity("b", "pkg.B.b", CodeEntityType.METHOD,
                "src/B.java", "java");
        CodeEntity c = entity("c", "pkg.C.c", CodeEntityType.METHOD,
                "src/C.java", "java");

        CodeRelation aCallsB = relation("pkg.A.a", "b", "pkg.B.b", CodeRelationType.CALLS);
        CodeRelation bCallsC = relation("pkg.B.b", "c", "pkg.C.c", CodeRelationType.CALLS);

        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "pkg.A.a"))
                .thenReturn(Optional.of(a));
        when(relationRepository.findByProjectIdAndSourceFqnAndRelationType(
                PROJECT, "pkg.A.a", CodeRelationType.CALLS))
                .thenReturn(List.of(aCallsB));
        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "pkg.B.b"))
                .thenReturn(Optional.of(b));

        // maxDepth=1 should stop after first layer
        Map<String, Object> result = analyzer.traceCodePaths(PROJECT, "pkg.A.a", 1);

        assertEquals(1, result.get("totalReachable")); // only B, not C
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> layers = (List<Map<String, Object>>) result.get("layers");
        assertEquals(1, layers.size());
    }

    @Test
    void traceCodePaths_avoidsCycles() {
        CodeEntity a = entity("a", "pkg.A.a", CodeEntityType.METHOD,
                "src/A.java", "java");
        CodeEntity b = entity("b", "pkg.B.b", CodeEntityType.METHOD,
                "src/B.java", "java");

        // a -> b -> a (cycle)
        CodeRelation aCallsB = relation("pkg.A.a", "b", "pkg.B.b", CodeRelationType.CALLS);
        CodeRelation bCallsA = relation("pkg.B.b", "a", "pkg.A.a", CodeRelationType.CALLS);

        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "pkg.A.a"))
                .thenReturn(Optional.of(a));
        when(relationRepository.findByProjectIdAndSourceFqnAndRelationType(
                PROJECT, "pkg.A.a", CodeRelationType.CALLS))
                .thenReturn(List.of(aCallsB));
        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "pkg.B.b"))
                .thenReturn(Optional.of(b));
        when(relationRepository.findByProjectIdAndSourceFqnAndRelationType(
                PROJECT, "pkg.B.b", CodeRelationType.CALLS))
                .thenReturn(List.of(bCallsA));

        Map<String, Object> result = analyzer.traceCodePaths(PROJECT, "pkg.A.a", 10);

        // Should not loop — only B is reachable (A is already visited as entry point)
        assertEquals(1, result.get("totalReachable"));
    }

    @Test
    void traceCodePaths_noCallsFromEntryPoint() {
        CodeEntity a = entity("a", "pkg.A.a", CodeEntityType.METHOD,
                "src/A.java", "java");

        when(entityRepository.findByProjectIdAndFullyQualifiedName(PROJECT, "pkg.A.a"))
                .thenReturn(Optional.of(a));
        when(relationRepository.findByProjectIdAndSourceFqnAndRelationType(
                PROJECT, "pkg.A.a", CodeRelationType.CALLS))
                .thenReturn(List.of());

        Map<String, Object> result = analyzer.traceCodePaths(PROJECT, "pkg.A.a", 5);

        assertEquals(0, result.get("totalReachable"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> layers = (List<Map<String, Object>>) result.get("layers");
        assertTrue(layers.isEmpty());
    }
}
