/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.web.controllers;

import ai.kompile.core.graphrag.GraphRagService;
import ai.kompile.core.graphrag.model.Community;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GraphRagController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GraphRagController")
class GraphRagControllerTest {

    @Mock
    private GraphRagService graphRagService;

    private GraphRagController controller;

    @BeforeEach
    void setUp() {
        controller = new GraphRagController(graphRagService);
    }

    // ─── POST /api/graph-rag/search ──────────────────────────────────────────

    @Nested
    @DisplayName("POST /search")
    class Search {

        @Test
        void serviceNull_returnsUnavailable() {
            var controllerNoService = new GraphRagController(null);
            Map<String, Object> request = Map.of("query", "test");

            ResponseEntity<Map<String, Object>> response = controllerNoService.search(request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(false, response.getBody().get("available"));
            assertNotNull(response.getBody().get("error"));
        }

        @Test
        void emptyQuery_returnsBadRequest() {
            Map<String, Object> request = Map.of("query", "  ");

            ResponseEntity<Map<String, Object>> response = controller.search(request);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertEquals("query is required", response.getBody().get("error"));
        }

        @Test
        void missingQuery_returnsBadRequest() {
            Map<String, Object> request = Map.of();

            ResponseEntity<Map<String, Object>> response = controller.search(request);

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        }

        @Test
        void validQuery_callsServiceWithCorrectParams() throws Exception {
            GraphRagResult result = GraphRagResult.builder()
                    .answer("Test answer")
                    .formattedContext("Context text")
                    .build();
            when(graphRagService.answerQuery(any())).thenReturn(result);

            Map<String, Object> request = new HashMap<>();
            request.put("query", "What is X?");
            request.put("searchType", "GLOBAL");
            request.put("maxResults", 10);
            request.put("conversationId", "conv-1");

            ResponseEntity<Map<String, Object>> response = controller.search(request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals("Test answer", response.getBody().get("answer"));
            assertEquals("Context text", response.getBody().get("context"));
            assertNotNull(response.getBody().get("durationMs"));

            ArgumentCaptor<GraphRagQuery> captor = ArgumentCaptor.forClass(GraphRagQuery.class);
            verify(graphRagService).answerQuery(captor.capture());
            GraphRagQuery captured = captor.getValue();
            assertEquals("What is X?", captured.getQuery());
            assertEquals(SearchType.GLOBAL, captured.getSearchType());
            assertEquals(10, captured.getK());
            assertEquals("conv-1", captured.getConversationId());
        }

        @Test
        void validQuery_defaultsToLocalSearch() throws Exception {
            GraphRagResult result = GraphRagResult.builder()
                    .answer("Answer")
                    .formattedContext("")
                    .build();
            when(graphRagService.answerQuery(any())).thenReturn(result);

            Map<String, Object> request = Map.of("query", "test query");

            controller.search(request);

            ArgumentCaptor<GraphRagQuery> captor = ArgumentCaptor.forClass(GraphRagQuery.class);
            verify(graphRagService).answerQuery(captor.capture());
            assertEquals(SearchType.LOCAL, captor.getValue().getSearchType());
            assertEquals(5, captor.getValue().getK());
            assertEquals("default", captor.getValue().getConversationId());
        }

        @Test
        void invalidSearchType_defaultsToLocal() throws Exception {
            GraphRagResult result = GraphRagResult.builder()
                    .answer("Answer")
                    .formattedContext("")
                    .build();
            when(graphRagService.answerQuery(any())).thenReturn(result);

            Map<String, Object> request = new HashMap<>();
            request.put("query", "test");
            request.put("searchType", "INVALID_TYPE");

            controller.search(request);

            ArgumentCaptor<GraphRagQuery> captor = ArgumentCaptor.forClass(GraphRagQuery.class);
            verify(graphRagService).answerQuery(captor.capture());
            assertEquals(SearchType.LOCAL, captor.getValue().getSearchType());
        }

        @Test
        void structuredResults_includedInResponse() throws Exception {
            Entity entity = new Entity();
            entity.setId("e1");
            entity.setTitle("Alice");
            entity.setType("PERSON");

            Relationship rel = new Relationship();
            rel.setSource("Alice");
            rel.setTarget("ACME");
            rel.setType("WORKS_AT");

            Community community = new Community();
            community.setId("c1");
            community.setSummary("Research Team");

            GraphRagResult result = GraphRagResult.builder()
                    .answer("Alice is a researcher at ACME")
                    .formattedContext("Context")
                    .entities(List.of(entity))
                    .relationships(List.of(rel))
                    .communities(List.of(community))
                    .sourceChunks(List.of("Some text..."))
                    .build();
            when(graphRagService.answerQuery(any())).thenReturn(result);

            Map<String, Object> request = Map.of("query", "Who is Alice?");

            ResponseEntity<Map<String, Object>> response = controller.search(request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            Map<String, Object> body = response.getBody();
            assertNotNull(body);

            @SuppressWarnings("unchecked")
            List<?> entities = (List<?>) body.get("entities");
            assertEquals(1, entities.size());

            @SuppressWarnings("unchecked")
            List<?> relationships = (List<?>) body.get("relationships");
            assertEquals(1, relationships.size());

            @SuppressWarnings("unchecked")
            List<?> communities = (List<?>) body.get("communities");
            assertEquals(1, communities.size());

            @SuppressWarnings("unchecked")
            List<?> sourceChunks = (List<?>) body.get("sourceChunks");
            assertEquals(1, sourceChunks.size());
        }

        @Test
        void nullStructuredResults_emptyLists() throws Exception {
            GraphRagResult result = GraphRagResult.builder()
                    .answer("Answer")
                    .formattedContext("Context")
                    .build();
            when(graphRagService.answerQuery(any())).thenReturn(result);

            Map<String, Object> request = Map.of("query", "test");

            ResponseEntity<Map<String, Object>> response = controller.search(request);
            Map<String, Object> body = response.getBody();

            assertEquals(List.of(), body.get("entities"));
            assertEquals(List.of(), body.get("relationships"));
            assertEquals(List.of(), body.get("communities"));
            assertEquals(List.of(), body.get("sourceChunks"));
        }

        @Test
        void serviceThrows_returnsServerError() throws Exception {
            when(graphRagService.answerQuery(any()))
                    .thenThrow(new RuntimeException("LLM unavailable"));

            Map<String, Object> request = Map.of("query", "test");

            ResponseEntity<Map<String, Object>> response = controller.search(request);

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertTrue(response.getBody().get("error").toString().contains("LLM unavailable"));
        }
    }

    // ─── GET /api/graph-rag/info ─────────────────────────────────────────────

    @Nested
    @DisplayName("GET /info")
    class Info {

        @Test
        void serviceNull_returnsUnavailable() {
            var controllerNoService = new GraphRagController(null);

            ResponseEntity<Map<String, Object>> response = controllerNoService.info();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            Map<String, Object> body = response.getBody();
            assertEquals(false, body.get("available"));
            assertEquals("none", body.get("type"));
        }

        @Test
        void serviceAvailable_returnsInfo() {
            // graphRagService is a mock, class name contains "Mock"
            ResponseEntity<Map<String, Object>> response = controller.info();

            assertEquals(HttpStatus.OK, response.getStatusCode());
            Map<String, Object> body = response.getBody();
            assertEquals(true, body.get("available"));
            assertNotNull(body.get("serviceClass"));
            assertNotNull(body.get("searchTypes"));

            @SuppressWarnings("unchecked")
            List<Map<String, String>> searchTypes = (List<Map<String, String>>) body.get("searchTypes");
            assertEquals(2, searchTypes.size());
        }

        @Test
        void unknownServiceClass_typeFallsBackToUnknown() {
            // Default mock class name won't contain "Neo4j" or "Matrix"
            ResponseEntity<Map<String, Object>> response = controller.info();
            Map<String, Object> body = response.getBody();
            assertEquals("unknown", body.get("type"));
        }
    }
}
