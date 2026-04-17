/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.eval.service;

import ai.kompile.app.eval.domain.EvalCaseEntity;
import ai.kompile.app.eval.domain.EvalSuiteEntity;
import ai.kompile.app.eval.repository.EvalCaseRepository;
import ai.kompile.app.eval.repository.EvalSuiteRepository;
import ai.kompile.app.eval.repository.EvalSuiteResultRepository;
import ai.kompile.app.eval.repository.EvalTestResultRepository;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.react.eval.model.EvalCase;
import ai.kompile.react.eval.model.EvalSuite;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for EvalSetService.
 */
@DisplayName("EvalSetService Tests")
class EvalSetServiceTest {

    private EvalSuiteRepository suiteRepository;
    private EvalCaseRepository caseRepository;
    private EvalTestResultRepository testResultRepository;
    private EvalSuiteResultRepository suiteResultRepository;
    private FactSheetService factSheetService;
    private EvalEntityConverter converter;
    private JpaEvalTracker evalTracker;
    private EvalSetService evalSetService;

    @BeforeEach
    void setUp() {
        // Create mocks explicitly using mockito-core's mock() method
        suiteRepository = mock(EvalSuiteRepository.class);
        caseRepository = mock(EvalCaseRepository.class);
        testResultRepository = mock(EvalTestResultRepository.class);
        suiteResultRepository = mock(EvalSuiteResultRepository.class);
        factSheetService = mock(FactSheetService.class);

        ObjectMapper objectMapper = new ObjectMapper();
        converter = new EvalEntityConverter(objectMapper);
        evalTracker = new JpaEvalTracker(
                caseRepository,
                suiteRepository,
                testResultRepository,
                suiteResultRepository,
                converter
        );
        evalSetService = new EvalSetService(evalTracker, factSheetService);
    }

    // ========================================
    // Suite Operations Tests
    // ========================================

    @Nested
    @DisplayName("Suite Operations")
    class SuiteOperations {

        @Test
        @DisplayName("Should get suites for fact sheet")
        void shouldGetSuitesForFactSheet() {
            // Arrange
            Long factSheetId = 1L;
            List<EvalSuiteEntity> entities = List.of(
                    createSuiteEntity("suite-1", "Suite 1", factSheetId),
                    createSuiteEntity("suite-2", "Suite 2", factSheetId)
            );

            // JpaEvalTracker uses findByFactSheetIdOrderByNameAsc
            when(suiteRepository.findByFactSheetIdOrderByNameAsc(factSheetId)).thenReturn(entities);

            // Act
            List<EvalSuite> result = evalSetService.getSuitesForFactSheet(factSheetId);

            // Assert
            assertEquals(2, result.size());
            assertEquals("Suite 1", result.get(0).getName());
            assertEquals("Suite 2", result.get(1).getName());
        }

        @Test
        @DisplayName("Should create suite for fact sheet")
        void shouldCreateSuiteForFactSheet() {
            // Arrange
            Long factSheetId = 1L;
            String name = "New Suite";
            String description = "A new test suite";

            when(suiteRepository.save(any(EvalSuiteEntity.class))).thenAnswer(invocation -> invocation.getArguments()[0]);

            // Act
            EvalSuite result = evalSetService.createSuiteForFactSheet(factSheetId, name, description);

            // Assert
            assertNotNull(result);
            assertEquals(name, result.getName());
            assertEquals(description, result.getDescription());
            assertEquals(factSheetId, result.getFactSheetId());
            assertTrue(result.isEnabled());
            assertEquals(0.8, result.getRequiredPassRate());
        }

        @Test
        @DisplayName("Should get suite by ID")
        void shouldGetSuiteById() {
            // Arrange
            String suiteId = "suite-123";
            EvalSuiteEntity entity = createSuiteEntity(suiteId, "Test Suite", 1L);

            // JpaEvalTracker.getSuite uses findByIdWithTestCases
            when(suiteRepository.findByIdWithTestCases(suiteId)).thenReturn(Optional.of(entity));

            // Act
            Optional<EvalSuite> result = evalSetService.getSuiteById(suiteId);

            // Assert
            assertTrue(result.isPresent());
            assertEquals("Test Suite", result.get().getName());
        }

        @Test
        @DisplayName("Should update suite")
        void shouldUpdateSuite() {
            // Arrange
            String suiteId = "suite-to-update";
            EvalSuiteEntity existingEntity = createSuiteEntity(suiteId, "Old Name", 1L);

            EvalSuite updatedSuite = EvalSuite.builder()
                    .id(suiteId)
                    .name("New Name")
                    .description("New Description")
                    .factSheetId(1L)
                    .enabled(false)
                    .requiredPassRate(0.9)
                    .tags(List.of("tag1"))
                    .build();

            when(suiteRepository.findById(suiteId)).thenReturn(Optional.of(existingEntity));
            when(suiteRepository.save(any(EvalSuiteEntity.class))).thenAnswer(invocation -> invocation.getArguments()[0]);

            // Act
            evalSetService.updateSuite(updatedSuite);

            // Assert
            verify(suiteRepository).save(any(EvalSuiteEntity.class));
        }

        @Test
        @DisplayName("Should delete suite")
        void shouldDeleteSuite() {
            // Arrange
            String suiteId = "suite-to-delete";
            when(suiteRepository.existsById(suiteId)).thenReturn(true);

            // Act
            boolean result = evalSetService.deleteSuite(suiteId);

            // Assert
            assertTrue(result);
            verify(suiteRepository).deleteById(suiteId);
        }
    }

    // ========================================
    // Test Case Operations Tests
    // ========================================

    @Nested
    @DisplayName("Test Case Operations")
    class TestCaseOperations {

        @Test
        @DisplayName("Should create test case in suite")
        void shouldCreateTestCaseInSuite() {
            // Arrange
            String suiteId = "suite-1";
            EvalCase testCase = EvalCase.builder()
                    .name("New Test Case")
                    .query("Test query?")
                    .expectedAnswer("Expected answer")
                    .build();

            EvalSuiteEntity suiteEntity = createSuiteEntity(suiteId, "Suite", 1L);
            suiteEntity.setTestCases(new ArrayList<>());

            // First save is for registerTestCase
            when(caseRepository.save(any(EvalCaseEntity.class))).thenAnswer(invocation -> invocation.getArguments()[0]);
            // addTestCaseToSuite calls findById (not findByIdWithTestCases)
            when(suiteRepository.findById(suiteId)).thenReturn(Optional.of(suiteEntity));
            // addTestCaseToSuite needs to find the case after it was registered
            when(caseRepository.findById(any(String.class))).thenAnswer(invocation -> {
                String id = invocation.getArgument(0);
                EvalCaseEntity entity = createCaseEntity(id, "New Test Case");
                return Optional.of(entity);
            });

            // Act
            EvalCase result = evalSetService.createTestCaseInSuite(suiteId, testCase);

            // Assert
            assertNotNull(result);
            assertNotNull(result.getId());
            assertEquals("New Test Case", result.getName());
            // Two saves: once for register, once for addToSuite
            verify(caseRepository, times(2)).save(any(EvalCaseEntity.class));
        }

        @Test
        @DisplayName("Should handle creating test case when suite not found")
        void shouldHandleCreatingTestCaseWhenSuiteNotFound() {
            // Arrange
            String suiteId = "non-existent";
            EvalCase testCase = EvalCase.builder()
                    .name("Test")
                    .query("Query")
                    .build();

            when(caseRepository.save(any(EvalCaseEntity.class))).thenAnswer(invocation -> invocation.getArguments()[0]);
            // Suite not found
            when(suiteRepository.findById(suiteId)).thenReturn(Optional.empty());
            when(caseRepository.findById(any(String.class))).thenAnswer(invocation -> {
                String id = invocation.getArgument(0);
                return Optional.of(createCaseEntity(id, "Test"));
            });

            // Act - Note: The current implementation doesn't throw, it silently doesn't add to suite
            EvalCase result = evalSetService.createTestCaseInSuite(suiteId, testCase);

            // Assert - Case is created but not added to suite (addTestCaseToSuite silently fails)
            assertNotNull(result);
            // Only one save - the registerTestCase, not the addToSuite since suite wasn't found
            verify(caseRepository, times(1)).save(any(EvalCaseEntity.class));
        }

        @Test
        @DisplayName("Should get test cases in suite")
        void shouldGetTestCasesInSuite() {
            // Arrange
            String suiteId = "suite-1";
            List<EvalCaseEntity> entities = List.of(
                    createCaseEntity("case-1", "Case 1"),
                    createCaseEntity("case-2", "Case 2")
            );

            when(caseRepository.findBySuiteId(suiteId)).thenReturn(entities);

            // Act
            List<EvalCase> result = evalSetService.getTestCasesInSuite(suiteId);

            // Assert
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("Should update test case")
        void shouldUpdateTestCase() {
            // Arrange
            String caseId = "case-to-update";
            EvalCaseEntity existingEntity = createCaseEntity(caseId, "Old Name");

            EvalCase updatedCase = EvalCase.builder()
                    .id(caseId)
                    .name("New Name")
                    .description("New Description")
                    .query("New Query?")
                    .expectedAnswer("New Answer")
                    .build();

            when(caseRepository.findById(caseId)).thenReturn(Optional.of(existingEntity));
            when(caseRepository.save(any(EvalCaseEntity.class))).thenAnswer(invocation -> invocation.getArguments()[0]);

            // Act
            evalSetService.updateTestCase(updatedCase);

            // Assert
            verify(caseRepository).save(any(EvalCaseEntity.class));
        }

        @Test
        @DisplayName("Should delete test case")
        void shouldDeleteTestCase() {
            // Arrange
            String caseId = "case-to-delete";
            when(caseRepository.existsById(caseId)).thenReturn(true);

            // Act
            boolean result = evalSetService.deleteTestCase(caseId);

            // Assert
            assertTrue(result);
            verify(caseRepository).deleteById(caseId);
        }

        @Test
        @DisplayName("Should move test case to different suite")
        void shouldMoveTestCaseToDifferentSuite() {
            // Arrange
            String caseId = "case-to-move";
            String sourceSuiteId = "source-suite";
            String targetSuiteId = "target-suite";

            EvalSuiteEntity sourceEntity = createSuiteEntity(sourceSuiteId, "Source", 1L);
            EvalSuiteEntity targetEntity = createSuiteEntity(targetSuiteId, "Target", 1L);
            EvalCaseEntity caseEntity = createCaseEntity(caseId, "Case");
            caseEntity.setSuite(sourceEntity);

            sourceEntity.setTestCases(new ArrayList<>(List.of(caseEntity)));
            targetEntity.setTestCases(new ArrayList<>());

            when(caseRepository.findById(caseId)).thenReturn(Optional.of(caseEntity));
            when(suiteRepository.findById(targetSuiteId)).thenReturn(Optional.of(targetEntity));
            when(caseRepository.save(any(EvalCaseEntity.class))).thenAnswer(invocation -> invocation.getArguments()[0]);

            // Act
            evalSetService.moveTestCaseToSuite(caseId, targetSuiteId);

            // Assert - Two saves: removeFromSuite sets suite to null, addToSuite sets new suite
            verify(caseRepository, times(2)).save(any(EvalCaseEntity.class));
        }
    }

    // ========================================
    // Import/Export Tests
    // ========================================

    @Nested
    @DisplayName("Import/Export Operations")
    class ImportExportOperations {

        @Test
        @DisplayName("Should export suite with test cases")
        void shouldExportSuiteWithTestCases() {
            // Arrange
            String suiteId = "suite-to-export";
            EvalSuiteEntity suiteEntity = createSuiteEntity(suiteId, "Export Suite", 1L);
            List<EvalCaseEntity> cases = List.of(
                    createCaseEntity("case-1", "Case 1"),
                    createCaseEntity("case-2", "Case 2")
            );
            suiteEntity.setTestCases(cases);

            // exportSuite calls evalTracker.getSuite which uses findByIdWithTestCases
            when(suiteRepository.findByIdWithTestCases(suiteId)).thenReturn(Optional.of(suiteEntity));

            // Act
            EvalSuite result = evalSetService.exportSuite(suiteId);

            // Assert
            assertNotNull(result);
            assertEquals("Export Suite", result.getName());
        }

        @Test
        @DisplayName("Should return null when exporting non-existent suite")
        void shouldReturnNullWhenExportingNonExistentSuite() {
            // Arrange
            String suiteId = "non-existent";
            when(suiteRepository.findByIdWithTestCases(suiteId)).thenReturn(Optional.empty());

            // Act
            EvalSuite result = evalSetService.exportSuite(suiteId);

            // Assert
            assertNull(result);
        }

        @Test
        @DisplayName("Should import suite to fact sheet")
        void shouldImportSuiteToFactSheet() {
            // Arrange
            Long targetFactSheetId = 2L;
            EvalSuite importSuite = EvalSuite.builder()
                    .id("old-id")
                    .name("Imported Suite")
                    .description("From export")
                    .testCases(List.of(
                            EvalCase.builder()
                                    .id("old-case-id")
                                    .name("Case 1")
                                    .query("Query")
                                    .build()
                    ))
                    .build();

            when(suiteRepository.save(any(EvalSuiteEntity.class))).thenAnswer(invocation -> invocation.getArguments()[0]);
            when(caseRepository.save(any(EvalCaseEntity.class))).thenAnswer(invocation -> invocation.getArguments()[0]);
            // For addTestCaseToSuite
            when(suiteRepository.findById(any(String.class))).thenAnswer(invocation -> {
                String id = invocation.getArgument(0);
                return Optional.of(createSuiteEntity(id, "Imported Suite", targetFactSheetId));
            });
            when(caseRepository.findById(any(String.class))).thenAnswer(invocation -> {
                String id = invocation.getArgument(0);
                return Optional.of(createCaseEntity(id, "Case 1"));
            });
            // For final getSuite call
            when(suiteRepository.findByIdWithTestCases(any(String.class))).thenAnswer(invocation -> {
                String id = invocation.getArgument(0);
                EvalSuiteEntity entity = createSuiteEntity(id, "Imported Suite", targetFactSheetId);
                return Optional.of(entity);
            });

            // Act
            EvalSuite result = evalSetService.importSuite(importSuite, targetFactSheetId);

            // Assert
            assertNotNull(result);
            assertNotEquals("old-id", result.getId()); // Should have new ID
            assertEquals("Imported Suite", result.getName());
            assertEquals(targetFactSheetId, result.getFactSheetId());
        }
    }

    // ========================================
    // Helper Methods
    // ========================================

    private EvalSuiteEntity createSuiteEntity(String id, String name, Long factSheetId) {
        return EvalSuiteEntity.builder()
                .id(id)
                .name(name)
                .factSheetId(factSheetId)
                .enabled(true)
                .requiredPassRate(0.8)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private EvalCaseEntity createCaseEntity(String id, String name) {
        return EvalCaseEntity.builder()
                .id(id)
                .name(name)
                .query("Test query")
                .priority(3)
                .enabled(true)
                .timeoutMs(30000L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
