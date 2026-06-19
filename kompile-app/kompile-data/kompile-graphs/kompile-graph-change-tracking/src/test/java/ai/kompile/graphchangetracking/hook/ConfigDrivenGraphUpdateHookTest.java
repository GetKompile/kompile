/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graphchangetracking.hook;

import ai.kompile.gateway.core.gateway.channel.ChannelAdapter;
import ai.kompile.graphchangetracking.domain.GraphUpdatePipelineConfig;
import ai.kompile.graphchangetracking.service.MutationContextHolder;
import ai.kompile.knowledgegraph.agent.MultiAgentExtractionService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests the EXTRACT_GRAPH pipeline step: a channel message runs LLM extraction and persists
 * the result scoped to the pipeline's target fact sheet (vs. the old flat single-node CRUD).
 */
class ConfigDrivenGraphUpdateHookTest {

    private KnowledgeGraphService graphService;
    private MultiAgentExtractionService extractionService;
    private ConfigDrivenGraphUpdateHook hook;

    @BeforeEach
    void setUp() {
        graphService = mock(KnowledgeGraphService.class);
        extractionService = mock(MultiAgentExtractionService.class);
        hook = new ConfigDrivenGraphUpdateHook(
                graphService, mock(MutationContextHolder.class), new ObjectMapper(), extractionService);
    }

    private ChannelGraphUpdateContext context(GraphUpdatePipelineConfig config) {
        ChannelAdapter.IncomingMessage msg = new ChannelAdapter.IncomingMessage(
                "m1", "u1", "User One", "Acme acquired Beta Corp", "c1", 0L, null, Map.of());
        return new ChannelGraphUpdateContext("p1", "slack", msg, config, "cs1");
    }

    @Test
    void extractGraphStep_runsExtractionOnMessage_persistsToTargetFactSheet() {
        when(extractionService.persistToGraph(any(), any(), any()))
                .thenReturn(new MultiAgentExtractionService.PersistenceSummary(2, 0, 1, 0, List.of()));

        GraphUpdatePipelineConfig config = GraphUpdatePipelineConfig.builder()
                .pipelineId("p1").pipelineName("test").enabled(true)
                .targetFactSheetId(42L)
                .processingSteps("[{\"step\":\"EXTRACT_GRAPH\"}]")
                .build();

        hook.onChannelMessage(context(config));

        // Extraction is invoked on the message content, with the default UNION merge strategy.
        verify(extractionService).runExtraction(
                argThat(docs -> docs.size() == 1 && docs.get(0).getText().contains("Acme")),
                any(), eq("UNION"), any());
        // Persisted via the shared service, scoped to the pipeline's target fact sheet.
        verify(extractionService).persistToGraph(any(), eq(graphService), eq(42L));
    }

    @Test
    void disabledPipeline_doesNothing() {
        GraphUpdatePipelineConfig config = GraphUpdatePipelineConfig.builder()
                .pipelineId("p1").pipelineName("test").enabled(false)
                .processingSteps("[{\"step\":\"EXTRACT_GRAPH\"}]")
                .build();

        hook.onChannelMessage(context(config));

        verifyNoInteractions(extractionService);
    }
}
