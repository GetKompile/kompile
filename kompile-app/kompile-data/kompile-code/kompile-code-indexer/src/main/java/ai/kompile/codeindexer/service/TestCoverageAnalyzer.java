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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyses indexed codebases for test coverage and code paths across
 * multiple programming languages.  Detects test frameworks, classifies
 * entities as test or production code, maps tests to the production
 * symbols they exercise, and traces execution paths through the call graph.
 */
@Service
public class TestCoverageAnalyzer {

    private final CodeEntityRepository entityRepository;
    private final CodeRelationRepository relationRepository;

    /** Per-language test annotation names (target of ANNOTATED_BY relations). */
    private static final Map<String, Set<String>> TEST_ANNOTATIONS;
    static {
        Map<String, Set<String>> m = new HashMap<>();
        // Java / Kotlin / Scala / Groovy (JVM)
        m.put("java",   Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory",
                "TestTemplate", "Disabled", "BeforeEach", "AfterEach", "BeforeAll", "AfterAll",
                "ExtendWith", "Nested"));
        m.put("kotlin", Set.of("Test", "ParameterizedTest", "RepeatedTest", "BeforeEach",
                "AfterEach", "BeforeAll", "AfterAll", "Nested"));
        m.put("scala",  Set.of("Test"));
        m.put("groovy", Set.of("Test"));
        // C#
        m.put("csharp", Set.of("Test", "TestMethod", "Fact", "Theory", "TestCase",
                "SetUp", "TearDown", "OneTimeSetUp", "OneTimeTearDown"));
        // Rust (#[test] captured as ANNOTATED_BY → "test")
        m.put("rust",   Set.of("test", "cfg"));
        // Python — decorators captured as ANNOTATED_BY
        m.put("python", Set.of("pytest.fixture", "fixture", "parametrize", "mark"));
        m.put("typescript", Set.of());
        m.put("javascript", Set.of());
        m.put("go",     Set.of());
        TEST_ANNOTATIONS = Collections.unmodifiableMap(m);
    }

    /** Known test-framework base classes (target of EXTENDS relations). */
    private static final Set<String> TEST_BASE_CLASSES = Set.of(
            "TestCase", "unittest.TestCase", "junit.framework.TestCase",
            "AbstractTestCase", "SpringBootTest", "XCTestCase",
            "Specification", "FunSpec", "WordSpec", "FlatSpec", "AnyFunSuite"
    );

    /** File path patterns that indicate test files. */
    private static final List<String> TEST_PATH_PATTERNS = List.of(
            "/test/", "/tests/", "/spec/", "/specs/",
            "/__tests__/", "/__test__/", "/testing/"
    );
    private static final List<String> TEST_FILE_PATTERNS = List.of(
            "_test.", ".test.", "test_", "_spec.", ".spec.", "spec_",
            "Test.java", "Test.kt", "Tests.java", "Tests.kt",
            "Spec.scala", "Suite.scala"
    );

    @Autowired
    public TestCoverageAnalyzer(CodeEntityRepository entityRepository,
                                 CodeRelationRepository relationRepository) {
        this.entityRepository = entityRepository;
        this.relationRepository = relationRepository;
    }

    // ── Test framework detection ───────────────────────────────────────────

    /**
     * Detect which test frameworks are present in the indexed project by
     * scanning ANNOTATED_BY relations, file paths, and naming conventions.
     */
    public Map<String, Object> detectTestFrameworks(String projectId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", projectId);

        List<String> languages = entityRepository.findDistinctLanguagesByProjectId(projectId);
        result.put("languages", languages);

        // Scan all ANNOTATED_BY relations to find test annotations
        List<CodeRelation> annotations = relationRepository
                .findByProjectIdAndRelationType(projectId, CodeRelationType.ANNOTATED_BY);

        Map<String, Set<String>> frameworksByLanguage = new LinkedHashMap<>();
        Map<String, Integer> annotationCounts = new LinkedHashMap<>();

        for (CodeRelation rel : annotations) {
            String annotName = rel.getTargetName();
            if (annotName == null) continue;
            annotationCounts.merge(annotName, 1, Integer::sum);

            // Determine the language of the annotated entity
            entityRepository.findByProjectIdAndFullyQualifiedName(projectId, rel.getSourceFqn())
                    .ifPresent(entity -> {
                        String lang = entity.getLanguage() != null
                                ? entity.getLanguage().toLowerCase() : "";
                        Set<String> knownAnnots = TEST_ANNOTATIONS.getOrDefault(lang, Set.of());
                        if (knownAnnots.contains(annotName)) {
                            frameworksByLanguage
                                    .computeIfAbsent(lang, k -> new LinkedHashSet<>())
                                    .add(resolveFramework(lang, annotName));
                        }
                    });
        }

        // Detect by naming conventions and file paths
        List<CodeEntity> allEntities = entityRepository.findByProjectId(projectId);
        Map<String, Set<String>> conventionFrameworks = new LinkedHashMap<>();

        for (CodeEntity entity : allEntities) {
            String lang = entity.getLanguage() != null
                    ? entity.getLanguage().toLowerCase() : "";
            if (isTestFile(entity)) {
                String framework = detectFrameworkByConvention(entity, lang);
                if (framework != null) {
                    conventionFrameworks
                            .computeIfAbsent(lang, k -> new LinkedHashSet<>())
                            .add(framework);
                }
            }
        }

        // Merge annotation-detected and convention-detected frameworks
        for (Map.Entry<String, Set<String>> e : conventionFrameworks.entrySet()) {
            frameworksByLanguage.merge(e.getKey(), e.getValue(), (a, b) -> {
                Set<String> merged = new LinkedHashSet<>(a);
                merged.addAll(b);
                return merged;
            });
        }

        // Detect by EXTENDS relations to known test base classes
        List<CodeRelation> extendsRels = relationRepository
                .findByProjectIdAndRelationType(projectId, CodeRelationType.EXTENDS);
        for (CodeRelation rel : extendsRels) {
            String targetName = rel.getTargetName();
            if (targetName != null && TEST_BASE_CLASSES.contains(targetName)) {
                entityRepository.findByProjectIdAndFullyQualifiedName(projectId, rel.getSourceFqn())
                        .ifPresent(entity -> {
                            String lang = entity.getLanguage() != null
                                    ? entity.getLanguage().toLowerCase() : "";
                            frameworksByLanguage
                                    .computeIfAbsent(lang, k -> new LinkedHashSet<>())
                                    .add(resolveFrameworkFromBaseClass(targetName));
                        });
            }
        }

        result.put("frameworksByLanguage", frameworksByLanguage);
        result.put("testAnnotationCounts", annotationCounts);
        return result;
    }

    // ── Entity classification ──────────────────────────────────────────────

    /**
     * Classify all entities in a project as test or production code.
     */
    public Map<String, Object> classifyEntities(String projectId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", projectId);

        List<CodeEntity> allEntities = entityRepository.findByProjectId(projectId);

        // Build set of FQNs that have test annotations
        Set<String> annotatedTestFqns = new HashSet<>();
        List<CodeRelation> annotations = relationRepository
                .findByProjectIdAndRelationType(projectId, CodeRelationType.ANNOTATED_BY);
        for (CodeRelation rel : annotations) {
            String annotName = rel.getTargetName();
            if (annotName != null && isTestAnnotation(annotName)) {
                annotatedTestFqns.add(rel.getSourceFqn());
            }
        }

        // Build set of FQNs that extend test base classes
        Set<String> testClassFqns = new HashSet<>();
        List<CodeRelation> extendsRels = relationRepository
                .findByProjectIdAndRelationType(projectId, CodeRelationType.EXTENDS);
        for (CodeRelation rel : extendsRels) {
            if (rel.getTargetName() != null && TEST_BASE_CLASSES.contains(rel.getTargetName())) {
                testClassFqns.add(rel.getSourceFqn());
            }
        }

        List<Map<String, Object>> testEntities = new ArrayList<>();
        List<Map<String, Object>> productionEntities = new ArrayList<>();
        Map<String, Integer> testByLanguage = new LinkedHashMap<>();
        Map<String, Integer> prodByLanguage = new LinkedHashMap<>();

        for (CodeEntity entity : allEntities) {
            if (entity.getEntityType() == CodeEntityType.FILE
                    || entity.getEntityType() == CodeEntityType.IMPORT
                    || entity.getEntityType() == CodeEntityType.PACKAGE) {
                continue; // skip structural entities
            }

            boolean isTest = isTestEntity(entity, annotatedTestFqns, testClassFqns);
            Map<String, Object> entry = entitySummary(entity);
            entry.put("classification", isTest ? "test" : "production");

            String lang = entity.getLanguage() != null ? entity.getLanguage().toLowerCase() : "unknown";
            if (isTest) {
                testEntities.add(entry);
                testByLanguage.merge(lang, 1, Integer::sum);
            } else {
                productionEntities.add(entry);
                prodByLanguage.merge(lang, 1, Integer::sum);
            }
        }

        result.put("testCount", testEntities.size());
        result.put("productionCount", productionEntities.size());
        result.put("testByLanguage", testByLanguage);
        result.put("productionByLanguage", prodByLanguage);
        result.put("testEntities", testEntities);
        result.put("productionEntities", productionEntities);

        double ratio = productionEntities.isEmpty() ? 0
                : (double) testEntities.size() / productionEntities.size();
        result.put("testToProductionRatio", Math.round(ratio * 100.0) / 100.0);

        return result;
    }

    // ── Tests-for-symbol mapping ───────────────────────────────────────────

    /**
     * Find tests that exercise a given production symbol by tracing reverse
     * CALLS relations back to test entities.
     */
    public Map<String, Object> getTestsForSymbol(String projectId, String fqn) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", fqn);

        Optional<CodeEntity> entityOpt = entityRepository
                .findByProjectIdAndFullyQualifiedName(projectId, fqn);
        result.put("entity", entityOpt.map(this::entitySummary).orElse(null));

        // Build test classification sets
        Set<String> annotatedTestFqns = buildAnnotatedTestFqnSet(projectId);
        Set<String> testClassFqns = buildTestClassFqnSet(projectId);

        // Find callers of this symbol (by simple name)
        String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
        List<CodeRelation> callers = relationRepository.findCallsToName(projectId, simpleName);

        // Also check reverse CALLS by target FQN
        List<CodeRelation> reverseCallsByFqn = relationRepository
                .findByProjectIdAndTargetFqnAndRelationType(projectId, fqn, CodeRelationType.CALLS);
        Set<String> callerFqns = new LinkedHashSet<>();
        for (CodeRelation r : callers) callerFqns.add(r.getSourceFqn());
        for (CodeRelation r : reverseCallsByFqn) callerFqns.add(r.getSourceFqn());

        // Filter to test entities
        List<Map<String, Object>> directTests = new ArrayList<>();
        List<Map<String, Object>> indirectTests = new ArrayList<>();

        for (String callerFqn : callerFqns) {
            entityRepository.findByProjectIdAndFullyQualifiedName(projectId, callerFqn)
                    .ifPresent(caller -> {
                        if (isTestEntity(caller, annotatedTestFqns, testClassFqns)) {
                            directTests.add(entitySummary(caller));
                        }
                    });
        }

        // Indirect: trace one more hop — who calls the callers, and are they tests?
        Set<String> visited = new HashSet<>(callerFqns);
        for (String callerFqn : callerFqns) {
            String callerSimple = callerFqn.contains(".")
                    ? callerFqn.substring(callerFqn.lastIndexOf('.') + 1) : callerFqn;
            List<CodeRelation> level2 = relationRepository.findCallsToName(projectId, callerSimple);
            for (CodeRelation r : level2) {
                if (visited.contains(r.getSourceFqn())) continue;
                visited.add(r.getSourceFqn());
                entityRepository.findByProjectIdAndFullyQualifiedName(projectId, r.getSourceFqn())
                        .ifPresent(caller -> {
                            if (isTestEntity(caller, annotatedTestFqns, testClassFqns)) {
                                indirectTests.add(entitySummary(caller));
                            }
                        });
            }
        }

        result.put("directTests", directTests);
        result.put("directTestCount", directTests.size());
        result.put("indirectTests", indirectTests);
        result.put("indirectTestCount", indirectTests.size());
        result.put("totalTestCount", directTests.size() + indirectTests.size());
        result.put("tested", !directTests.isEmpty() || !indirectTests.isEmpty());
        return result;
    }

    // ── Coverage report ────────────────────────────────────────────────────

    /**
     * Overall test coverage summary: test/production ratio, untested symbols,
     * framework breakdown.
     */
    public Map<String, Object> getTestCoverageReport(String projectId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", projectId);

        // Classification
        Set<String> annotatedTestFqns = buildAnnotatedTestFqnSet(projectId);
        Set<String> testClassFqns = buildTestClassFqnSet(projectId);

        List<CodeEntity> allEntities = entityRepository.findByProjectId(projectId);
        List<CodeEntity> productionMethods = new ArrayList<>();
        int testCount = 0;

        for (CodeEntity entity : allEntities) {
            if (entity.getEntityType() == CodeEntityType.FILE
                    || entity.getEntityType() == CodeEntityType.IMPORT
                    || entity.getEntityType() == CodeEntityType.PACKAGE) {
                continue;
            }
            if (isTestEntity(entity, annotatedTestFqns, testClassFqns)) {
                testCount++;
            } else if (entity.getEntityType() == CodeEntityType.METHOD
                    || entity.getEntityType() == CodeEntityType.FUNCTION
                    || entity.getEntityType() == CodeEntityType.CONSTRUCTOR) {
                productionMethods.add(entity);
            }
        }

        // Determine which production methods are called from tests
        Set<String> calledNames = new HashSet<>();
        for (String testFqn : annotatedTestFqns) {
            List<CodeRelation> calls = relationRepository
                    .findByProjectIdAndSourceFqnAndRelationType(projectId, testFqn, CodeRelationType.CALLS);
            for (CodeRelation r : calls) {
                calledNames.add(r.getTargetName());
                if (r.getTargetFqn() != null) calledNames.add(r.getTargetFqn());
            }
        }
        // Also check methods in test files
        for (CodeEntity entity : allEntities) {
            if (isTestFile(entity)
                    && (entity.getEntityType() == CodeEntityType.METHOD
                        || entity.getEntityType() == CodeEntityType.FUNCTION)) {
                List<CodeRelation> calls = relationRepository
                        .findByProjectIdAndSourceFqnAndRelationType(
                                projectId, entity.getFullyQualifiedName(), CodeRelationType.CALLS);
                for (CodeRelation r : calls) {
                    calledNames.add(r.getTargetName());
                    if (r.getTargetFqn() != null) calledNames.add(r.getTargetFqn());
                }
            }
        }

        List<Map<String, Object>> testedMethods = new ArrayList<>();
        List<Map<String, Object>> untestedMethods = new ArrayList<>();

        for (CodeEntity method : productionMethods) {
            boolean isCalled = calledNames.contains(method.getName())
                    || calledNames.contains(method.getFullyQualifiedName());
            Map<String, Object> entry = entitySummary(method);
            if (isCalled) {
                testedMethods.add(entry);
            } else {
                untestedMethods.add(entry);
            }
        }

        int totalProdMethods = productionMethods.size();
        double coveragePercent = totalProdMethods == 0 ? 0
                : Math.round((double) testedMethods.size() / totalProdMethods * 10000.0) / 100.0;

        result.put("testCount", testCount);
        result.put("productionMethodCount", totalProdMethods);
        result.put("testedMethodCount", testedMethods.size());
        result.put("untestedMethodCount", untestedMethods.size());
        result.put("coveragePercent", coveragePercent);

        // Frameworks
        Map<String, Object> frameworks = detectTestFrameworks(projectId);
        result.put("frameworksByLanguage", frameworks.get("frameworksByLanguage"));

        // Top untested (limit to 50)
        result.put("untestedMethods", untestedMethods.stream().limit(50).collect(Collectors.toList()));
        result.put("testedMethods", testedMethods.stream().limit(50).collect(Collectors.toList()));

        return result;
    }

    // ── Code path tracing ──────────────────────────────────────────────────

    /**
     * Trace execution paths from an entry point through CALLS relations,
     * collecting all reachable symbols up to {@code maxDepth}.
     */
    public Map<String, Object> traceCodePaths(String projectId, String fromFqn, int maxDepth) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entryPoint", fromFqn);
        result.put("maxDepth", maxDepth);

        Optional<CodeEntity> entityOpt = entityRepository
                .findByProjectIdAndFullyQualifiedName(projectId, fromFqn);
        result.put("entity", entityOpt.map(this::entitySummary).orElse(null));

        // BFS over outgoing CALLS relations only
        Set<String> visited = new LinkedHashSet<>();
        visited.add(fromFqn);
        List<Map<String, Object>> layers = new ArrayList<>();
        Set<String> frontier = new LinkedHashSet<>();
        frontier.add(fromFqn);

        for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
            List<Map<String, Object>> layerEntries = new ArrayList<>();
            Set<String> nextFrontier = new LinkedHashSet<>();

            for (String currentFqn : frontier) {
                List<CodeRelation> calls = relationRepository
                        .findByProjectIdAndSourceFqnAndRelationType(
                                projectId, currentFqn, CodeRelationType.CALLS);
                for (CodeRelation rel : calls) {
                    String targetFqn = rel.getTargetFqn();
                    // Fall back to matching by target name
                    if (targetFqn == null || targetFqn.isBlank()) {
                        targetFqn = resolveTargetByName(projectId, rel.getTargetName());
                    }
                    if (targetFqn == null || visited.contains(targetFqn)) continue;

                    visited.add(targetFqn);
                    nextFrontier.add(targetFqn);

                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("fqn", targetFqn);
                    entry.put("calledFrom", currentFqn);
                    entry.put("callSite", rel.getFilePath() != null
                            ? rel.getFilePath() + (rel.getLine() != null ? ":" + rel.getLine() : "")
                            : null);
                    entityRepository.findByProjectIdAndFullyQualifiedName(projectId, targetFqn)
                            .ifPresent(e -> {
                                entry.put("entityType", e.getEntityType().name());
                                entry.put("name", e.getName());
                                entry.put("filePath", e.getFilePath());
                                entry.put("language", e.getLanguage());
                            });
                    layerEntries.add(entry);
                }
            }

            if (!layerEntries.isEmpty()) {
                Map<String, Object> layer = new LinkedHashMap<>();
                layer.put("depth", depth + 1);
                layer.put("count", layerEntries.size());
                layer.put("calls", layerEntries);
                layers.add(layer);
            }
            frontier = nextFrontier;
        }

        result.put("layers", layers);
        result.put("totalReachable", visited.size() - 1);
        result.put("reachableFqns", new ArrayList<>(visited));
        return result;
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private boolean isTestFile(CodeEntity entity) {
        String path = entity.getFilePath();
        if (path == null) return false;
        String lower = path.toLowerCase();
        for (String pattern : TEST_PATH_PATTERNS) {
            if (lower.contains(pattern)) return true;
        }
        for (String pattern : TEST_FILE_PATTERNS) {
            if (lower.contains(pattern)) return true;
        }
        return false;
    }

    private boolean isTestAnnotation(String annotName) {
        for (Set<String> annots : TEST_ANNOTATIONS.values()) {
            if (annots.contains(annotName)) return true;
        }
        return false;
    }

    private boolean isTestEntity(CodeEntity entity,
                                  Set<String> annotatedTestFqns,
                                  Set<String> testClassFqns) {
        String fqn = entity.getFullyQualifiedName();

        // 1. Directly annotated with @Test etc.
        if (fqn != null && annotatedTestFqns.contains(fqn)) return true;

        // 2. Parent class is a test class
        String parentFqn = entity.getParentFqn();
        if (parentFqn != null && (annotatedTestFqns.contains(parentFqn)
                || testClassFqns.contains(parentFqn))) return true;

        // 3. In a test file
        if (isTestFile(entity)) return true;

        // 4. Naming conventions
        String name = entity.getName();
        if (name != null) {
            String lang = entity.getLanguage() != null ? entity.getLanguage().toLowerCase() : "";
            // Python: test_ prefix
            if (lang.equals("python") && name.startsWith("test_")) return true;
            // Go: Test prefix + file suffix
            if (lang.equals("go") && name.startsWith("Test")) return true;
            // Rust: function named with test_ or in test module
            if (lang.equals("rust") && name.startsWith("test_")) return true;
            // JS/TS: describe, it, test functions at top level — handled by file path
        }

        // 5. Signature contains @Test
        String sig = entity.getSignature();
        if (sig != null && (sig.contains("@Test") || sig.contains("@test")
                || sig.contains("#[test]") || sig.contains("[Fact]")
                || sig.contains("[TestMethod]"))) return true;

        return false;
    }

    private Set<String> buildAnnotatedTestFqnSet(String projectId) {
        Set<String> result = new HashSet<>();
        List<CodeRelation> annotations = relationRepository
                .findByProjectIdAndRelationType(projectId, CodeRelationType.ANNOTATED_BY);
        for (CodeRelation rel : annotations) {
            if (rel.getTargetName() != null && isTestAnnotation(rel.getTargetName())) {
                result.add(rel.getSourceFqn());
            }
        }
        return result;
    }

    private Set<String> buildTestClassFqnSet(String projectId) {
        Set<String> result = new HashSet<>();
        List<CodeRelation> extendsRels = relationRepository
                .findByProjectIdAndRelationType(projectId, CodeRelationType.EXTENDS);
        for (CodeRelation rel : extendsRels) {
            if (rel.getTargetName() != null && TEST_BASE_CLASSES.contains(rel.getTargetName())) {
                result.add(rel.getSourceFqn());
            }
        }
        return result;
    }

    private String resolveFramework(String language, String annotation) {
        return switch (language) {
            case "java", "kotlin", "scala", "groovy" -> switch (annotation) {
                case "Test", "ParameterizedTest", "RepeatedTest", "TestFactory",
                     "TestTemplate", "Nested", "ExtendWith" -> "JUnit5";
                case "Disabled" -> "JUnit5";
                case "BeforeEach", "AfterEach", "BeforeAll", "AfterAll" -> "JUnit5";
                default -> "JUnit";
            };
            case "csharp" -> switch (annotation) {
                case "Test", "SetUp", "TearDown", "OneTimeSetUp",
                     "OneTimeTearDown", "TestCase" -> "NUnit";
                case "TestMethod" -> "MSTest";
                case "Fact", "Theory" -> "xUnit";
                default -> "Unknown";
            };
            case "rust" -> "cargo test";
            case "python" -> switch (annotation) {
                case "pytest.fixture", "fixture", "parametrize", "mark" -> "pytest";
                default -> "pytest";
            };
            default -> "Unknown";
        };
    }

    private String resolveFrameworkFromBaseClass(String baseClass) {
        return switch (baseClass) {
            case "TestCase", "unittest.TestCase" -> "unittest";
            case "junit.framework.TestCase" -> "JUnit4";
            case "XCTestCase" -> "XCTest";
            case "Specification", "FunSpec", "WordSpec", "FlatSpec" -> "ScalaTest";
            case "AnyFunSuite" -> "ScalaTest";
            default -> "Unknown";
        };
    }

    private String detectFrameworkByConvention(CodeEntity entity, String lang) {
        String path = entity.getFilePath();
        if (path == null) return null;
        String lower = path.toLowerCase();

        return switch (lang) {
            case "go" -> lower.endsWith("_test.go") ? "go test" : null;
            case "python" -> {
                if (lower.contains("conftest.py") || lower.contains("pytest"))
                    yield "pytest";
                if (lower.contains("test_") || lower.contains("_test.py"))
                    yield "pytest/unittest";
                yield null;
            }
            case "javascript", "typescript" -> {
                if (lower.contains(".spec.")) yield "Jest/Mocha/Vitest";
                if (lower.contains(".test.")) yield "Jest/Vitest";
                if (lower.contains("__tests__")) yield "Jest";
                yield null;
            }
            case "rust" -> lower.contains("/tests/") || lower.contains("_test.rs")
                    ? "cargo test" : null;
            case "java", "kotlin" -> {
                if (lower.contains("/test/")) yield "JUnit";
                yield null;
            }
            default -> null;
        };
    }

    private String resolveTargetByName(String projectId, String targetName) {
        if (targetName == null) return null;
        List<CodeEntity> candidates = entityRepository.findByProjectId(projectId).stream()
                .filter(e -> targetName.equals(e.getName()))
                .limit(1)
                .collect(Collectors.toList());
        return candidates.isEmpty() ? null : candidates.get(0).getFullyQualifiedName();
    }

    private Map<String, Object> entitySummary(CodeEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.getName());
        m.put("fqn", e.getFullyQualifiedName());
        m.put("entityType", e.getEntityType().name());
        m.put("filePath", e.getFilePath());
        m.put("language", e.getLanguage());
        if (e.getStartLine() != null) m.put("startLine", e.getStartLine());
        if (e.getEndLine() != null) m.put("endLine", e.getEndLine());
        if (e.getSignature() != null) m.put("signature", e.getSignature());
        if (e.getVisibility() != null) m.put("visibility", e.getVisibility());
        return m;
    }
}
