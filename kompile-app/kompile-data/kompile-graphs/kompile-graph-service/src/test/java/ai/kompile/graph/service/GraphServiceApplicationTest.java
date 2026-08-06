/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.service;

import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.GraphReasoningQueryService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Boots the isolated graph process and verifies its health and Phase 1 HTTP contracts. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:graph-service-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "management.endpoint.health.show-details=always"
})
@AutoConfigureMockMvc
class GraphServiceApplicationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KnowledgeGraphService knowledgeGraphService;

    @Autowired
    private MatrixGraphStore matrixGraphStore;

    @Autowired
    private UnifiedGraphBridge unifiedGraphBridge;

    @Autowired
    private GraphReasoningQueryService graphReasoningQueryService;

    @Test
    void contextLoadsWithIsolatedGraphStorage() {
        assertNotNull(knowledgeGraphService);
        assertNotNull(matrixGraphStore);
        assertNotNull(unifiedGraphBridge);
        assertNotNull(graphReasoningQueryService);
    }

    @Test
    void exposesHealthAndGraphContracts() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.graphService.status").value("UP"));

        mockMvc.perform(post("/api/graph/reasoning/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"CAPABILITIES\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/graph/unified/export"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(result -> assertTrue(result.getResponse().getContentAsByteArray().length > 0));
    }
}
