/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class GraphReasoningQueryControllerTest {

    @Mock
    private GraphReasoningQueryService queryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new GraphReasoningQueryController(queryService)).build();
    }

    @Test
    void queryRouteDeserializesSharedRequestAndReturnsEngineResult() throws Exception {
        GraphQueryEngine.Result result = new GraphQueryEngine.Result(
                GraphQueryEngine.Status.OK,
                GraphQueryEngine.Intent.CAPABILITIES,
                "Graph query capabilities",
                List.of(), List.of(), List.of(), List.of(), List.of());
        when(queryService.execute(any())).thenReturn(result);

        mockMvc.perform(post(GraphReasoningQueryController.BASE_PATH + "/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"capabilities\",\"factSheetId\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.intent").value("CAPABILITIES"))
                .andExpect(jsonPath("$.summary").value("Graph query capabilities"));

        ArgumentCaptor<GraphReasoningQueryService.QueryRequest> request =
                ArgumentCaptor.forClass(GraphReasoningQueryService.QueryRequest.class);
        verify(queryService).execute(request.capture());
        assertEquals("capabilities", request.getValue().operation());
        assertEquals(42L, request.getValue().factSheetId());
    }

    @Test
    void invalidQueryUsesErrorShapeExpectedByCliClient() throws Exception {
        when(queryService.execute(any()))
                .thenReturn(GraphReasoningQueryService.invalid("Unknown operation 'nope'"));

        mockMvc.perform(post(GraphReasoningQueryController.BASE_PATH + "/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operation\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("INVALID"))
                .andExpect(jsonPath("$.error").value("Unknown operation 'nope'"));
    }
}
