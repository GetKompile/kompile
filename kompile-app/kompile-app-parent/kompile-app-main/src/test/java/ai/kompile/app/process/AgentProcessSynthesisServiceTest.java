/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ai.kompile.app.process;

import ai.kompile.app.services.agent.AgentChatService;
import ai.kompile.app.web.dto.AgentChatRequest;
import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessSuggestionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The agentic synthesis step: the mined skeleton travels in the task, the agent gets MCP tools
 * and the fact-sheet scope, and only grounded documents land on the suggestion.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentProcessSynthesisServiceTest {

    private static final long FS = 66L;

    @Mock
    private AgentChatService agentChatService;

    private ProcessSuggestionStore store;
    private AgentProcessSynthesisService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        store = new ProcessSuggestionStore(tempDir.resolve("suggestions"));
        service = new AgentProcessSynthesisService(agentChatService, store);

        ProcessSuggestion.SuggestedStep intake = ProcessSuggestion.SuggestedStep.builder()
                .name("Claim Intake").stepType("AUTO").graphNodeIds(List.of("n-intake-1")).build();
        intake.setDependsOn(new ArrayList<>());
        ProcessSuggestion.SuggestedStep payout = ProcessSuggestion.SuggestedStep.builder()
                .name("Claim Payout").stepType("AUTO").build();
        payout.setDependsOn(new ArrayList<>(List.of("Claim Intake")));

        store.save(ProcessSuggestion.builder()
                .id("s-1").factSheetId(FS).discoverySource("PROCESS_MINING").confidence(0.7)
                .name("Claim Intake → Claim Payout process (fact sheet " + FS + ")")
                .description("Discovered by the Inductive Miner from 4 case(s) over the knowledge graph")
                .phases(List.of(ProcessSuggestion.SuggestedPhase.builder()
                        .name("Phase 1").steps(List.of(intake, payout)).build()))
                .structuredEvidence(new ArrayList<>(List.of(
                        ProcessSuggestion.StructuredEvidence.builder()
                                .type("ENTAILED")
                                .description("Claim Intake ⊨ precedes Claim Payout (posterior 0.91)")
                                .build())))
                .build());
    }

    private void stubAgent(String content, String error, int exitCode) {
        when(agentChatService.executeChatSync(any(), anyInt())).thenReturn(
                new AgentChatService.SyncChatResult(content, "p-1", exitCode, 1000L,
                        List.of(), List.of(), Map.of(), error));
    }

    @Test
    void groundedAgentDocument_isPersisted_withMcpToolsAndFactSheetScope() {
        stubAgent("""
                # Claim Intake → Claim Payout process
                ## Purpose
                Settle insurance claims.
                ## Trigger
                A policyholder files a claim.
                ## Steps
                1. Claim Intake — the claims agent registers the claim (node n-intake-1: "Laptop claim").
                2. Claim Payout — finance settles once Claim Intake is complete.
                ## Exceptions and contradictions
                None observed.
                ## Evidence and confidence
                Entailed: Claim Intake precedes Claim Payout (posterior 0.91). Confidence 70%.
                """, null, 0);

        AgentProcessSynthesisService.SynthesisResult result = service.synthesize("s-1", "claude", 0);

        assertTrue(result.success(), () -> "expected success, got: " + result.error());
        ProcessSuggestion reloaded = store.get("s-1").orElseThrow();
        assertTrue(reloaded.getProcessDocument().contains("## Steps"));
        assertEquals("claude", reloaded.getProcessDocumentSource());

        ArgumentCaptor<AgentChatRequest> captor = ArgumentCaptor.forClass(AgentChatRequest.class);
        verify(agentChatService).executeChatSync(captor.capture(), anyInt());
        AgentChatRequest request = captor.getValue();
        assertEquals("claude", request.getAgentName());
        assertEquals(FS, request.getFactSheetId());
        assertTrue(request.isInjectMcpTools(), "the agent must get the MCP graph tools");
        assertTrue(request.getMessage().contains("- Claim Intake"),
                "the mined skeleton travels in the task");
        assertTrue(request.getMessage().contains("ask_graph_query"),
                "the task instructs graph exploration through the MCP tools");
        assertTrue(request.getMessage().contains("waits for: Claim Intake"),
                "dependencies travel in the task");
    }

    @Test
    void unGroundedDocument_isRejected_suggestionUntouched() {
        stubAgent("""
                # Some process
                ## Steps
                1. Fraud Investigation runs first.
                2. Claim Payout follows.
                """, null, 0);

        AgentProcessSynthesisService.SynthesisResult result = service.synthesize("s-1", "claude", 0);

        assertFalse(result.success());
        assertTrue(result.error().contains("grounding"));
        assertNull(store.get("s-1").orElseThrow().getProcessDocument(),
                "an un-grounded document must never land on the suggestion");
    }

    @Test
    void agentFailure_propagates() {
        stubAgent("", "Agent not available: opencode", -1);
        AgentProcessSynthesisService.SynthesisResult result = service.synthesize("s-1", "opencode", 0);
        assertFalse(result.success());
        assertTrue(result.error().contains("not available"));
    }

    @Test
    void unknownSuggestion_fails() {
        assertFalse(service.synthesize("nope", "claude", 0).success());
    }

    @Test
    void groundingGate_requiresStepsSectionAndEveryStep() {
        List<String> steps = List.of("Claim Intake", "Claim Payout");
        assertTrue(AgentProcessSynthesisService.isGrounded(
                "# X\n## Steps\nclaim intake then CLAIM PAYOUT", steps));
        assertFalse(AgentProcessSynthesisService.isGrounded(
                "claim intake then claim payout without the section", steps));
        assertFalse(AgentProcessSynthesisService.isGrounded(
                "# X\n## Steps\nonly Claim Intake", steps));
        assertFalse(AgentProcessSynthesisService.isGrounded(null, steps));
    }
}
