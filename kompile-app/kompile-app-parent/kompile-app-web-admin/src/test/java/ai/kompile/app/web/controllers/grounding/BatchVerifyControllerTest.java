/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.web.controllers.grounding;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BatchVerifyController}.
 *
 * <p>Uses direct controller instantiation with in-memory {@link KbGroundingService}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BatchVerifyController")
class BatchVerifyControllerTest {

    private KbGroundingService groundingService;
    private BatchVerifyController controller;

    @BeforeEach
    void setUp() {
        groundingService = new KbGroundingService();
        controller = new BatchVerifyController(groundingService);
    }

    // ── Validation ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Input validation")
    class Validation {

        @Test
        @DisplayName("null atomKeys throws IllegalArgumentException")
        void nullAtomKeys_throws() {
            BatchVerifyController.BatchVerifyRequest req =
                    new BatchVerifyController.BatchVerifyRequest(1L, null, null);
            assertThrows(IllegalArgumentException.class, () -> controller.batchVerify(req));
        }

        @Test
        @DisplayName("empty atomKeys throws IllegalArgumentException")
        void emptyAtomKeys_throws() {
            BatchVerifyController.BatchVerifyRequest req =
                    new BatchVerifyController.BatchVerifyRequest(1L, List.of(), null);
            assertThrows(IllegalArgumentException.class, () -> controller.batchVerify(req));
        }

        @Test
        @DisplayName("batch size > 200 throws IllegalArgumentException")
        void oversizedBatch_throws() {
            List<String> oversized = java.util.stream.IntStream.range(0, 201)
                    .mapToObj(i -> "foo(" + i + ")")
                    .toList();
            BatchVerifyController.BatchVerifyRequest req =
                    new BatchVerifyController.BatchVerifyRequest(1L, oversized, null);
            assertThrows(IllegalArgumentException.class, () -> controller.batchVerify(req));
        }
    }

    // ── Happy path ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("all unknown atoms return UNKNOWN verdict for each key")
        void unknownAtoms_returnUnknownForEach() {
            List<String> keys = List.of("foo(A)", "bar(B)", "baz(C)");
            BatchVerifyController.BatchVerifyRequest req =
                    new BatchVerifyController.BatchVerifyRequest(99L, keys, null);

            ResponseEntity<BatchVerifyController.BatchVerifyResponse> resp =
                    controller.batchVerify(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertEquals(3, resp.getBody().totalCount());
            assertEquals(99L, resp.getBody().factSheetId());
            assertNotNull(resp.getBody().timestamp());

            for (String key : keys) {
                BatchVerifyController.VerifyResultSummary summary =
                        resp.getBody().results().get(key);
                assertNotNull(summary, "Missing result for " + key);
                assertEquals("UNKNOWN", summary.verdict());
                assertEquals(0.0, summary.confidence());
                assertEquals(0, summary.evidenceCount());
                assertNotNull(summary.evaluatedAt());
            }
        }

        @Test
        @DisplayName("seeded atom returns SUPPORTED in batch result")
        void seededAtom_returnsSupported() {
            long fsId = 10L;
            groundingService.seedInferredFacts(fsId, List.of(
                    InferredFact.of("worksAt(Alice, Corp)", 0.9,
                            List.of(), List.of(), "run-batch", 1L)
            ));

            List<String> keys = List.of("worksAt(Alice, Corp)", "unknown(X)");
            BatchVerifyController.BatchVerifyRequest req =
                    new BatchVerifyController.BatchVerifyRequest(fsId, keys, null);

            ResponseEntity<BatchVerifyController.BatchVerifyResponse> resp =
                    controller.batchVerify(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(2, resp.getBody().totalCount());

            BatchVerifyController.VerifyResultSummary supported =
                    resp.getBody().results().get("worksAt(Alice, Corp)");
            assertNotNull(supported);
            assertEquals("SUPPORTED", supported.verdict());
            assertTrue(supported.confidence() > 0.0);

            BatchVerifyController.VerifyResultSummary unknown =
                    resp.getBody().results().get("unknown(X)");
            assertNotNull(unknown);
            assertEquals("UNKNOWN", unknown.verdict());
        }

        @Test
        @DisplayName("null factSheetId defaults to global (0L)")
        void nullFactSheetId_usesGlobal() {
            BatchVerifyController.BatchVerifyRequest req =
                    new BatchVerifyController.BatchVerifyRequest(null, List.of("foo(X)"), null);

            ResponseEntity<BatchVerifyController.BatchVerifyResponse> resp =
                    controller.batchVerify(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(0L, resp.getBody().factSheetId());
        }

        @Test
        @DisplayName("blank atom keys are skipped without error")
        void blankAtomKeys_skipped() {
            // blank entries in the list should be silently skipped
            List<String> keys = List.of("foo(A)", "", "bar(B)");
            BatchVerifyController.BatchVerifyRequest req =
                    new BatchVerifyController.BatchVerifyRequest(1L, keys, null);

            ResponseEntity<BatchVerifyController.BatchVerifyResponse> resp =
                    controller.batchVerify(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            // Only 2 non-blank keys should be in results
            assertEquals(2, resp.getBody().totalCount());
            assertFalse(resp.getBody().results().containsKey(""));
        }
    }
}
