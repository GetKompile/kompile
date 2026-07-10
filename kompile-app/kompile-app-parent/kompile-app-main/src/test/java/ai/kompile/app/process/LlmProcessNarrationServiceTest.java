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

import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.knowledgegraph.staging.ModelTrainedEvent;
import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessSuggestionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The LLM final step: grounded output upgrades the narrative; hallucinated output is rejected in
 * favour of the deterministic template — the mined structure is authoritative, the LLM only
 * narrates it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmProcessNarrationServiceTest {

    private static final long FS = 55L;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private LLMChat llmChat;

    private ProcessSuggestionStore store;
    private LlmProcessNarrationService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        store = new ProcessSuggestionStore(tempDir.resolve("suggestions"));
        service = new LlmProcessNarrationService(store);
        service.setLlmChat(llmChat);
        store.save(minedSuggestion("s-1"));
    }

    private static ProcessSuggestion minedSuggestion(String id) {
        return ProcessSuggestion.builder()
                .id(id)
                .factSheetId(FS)
                .discoverySource("PROCESS_MINING")
                .confidence(0.6)
                .name("Claim Intake → Claim Payout process (fact sheet " + FS + ")")
                .description("Discovered by the Inductive Miner from 4 case(s) over the knowledge graph")
                .narrative("template narrative Claim Intake Coverage Review Claim Payout")
                .narrativeSource("TEMPLATE")
                .phases(List.of(ProcessSuggestion.SuggestedPhase.builder()
                        .name("Phase 1")
                        .steps(List.of(
                                step("Claim Intake"),
                                step("Coverage Review"),
                                step("Claim Payout")))
                        .build()))
                .structuredEvidence(new ArrayList<>(List.of(
                        ProcessSuggestion.StructuredEvidence.builder()
                                .type("ENTAILED")
                                .description("Claim Intake ⊨ precedes Claim Payout (posterior 0.91)")
                                .build())))
                .build();
    }

    private static ProcessSuggestion.SuggestedStep step(String name) {
        ProcessSuggestion.SuggestedStep s = ProcessSuggestion.SuggestedStep.builder()
                .name(name).stepType("AUTO").description("Discovered activity \"" + name + "\"").build();
        s.setDependsOn(new ArrayList<>());
        return s;
    }

    private void stubLlm(String response) {
        when(llmChat.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn(response);
    }

    @Test
    void groundedLlmProse_upgradesNarrative_andPersists() {
        stubLlm("The claims process begins when a Claim Intake is registered. A Coverage Review "
                + "confirms the policy applies, and once approved the Claim Payout settles the claim. "
                + "The order Claim Intake before Claim Payout was additionally entailed by the reasoner.");

        assertEquals(1, service.narrateFactSheet(FS));

        ProcessSuggestion reloaded = store.get("s-1").orElseThrow();
        assertEquals("LLM", reloaded.getNarrativeSource());
        assertTrue(reloaded.getNarrative().startsWith("The claims process begins"));
    }

    @Test
    void driftClause_survivesTheLlmRewrite() {
        // The generation-drift clause lives in DRIFT evidence; the LLM regenerates the narrative,
        // so the clause must be re-appended from the durable evidence.
        ProcessSuggestion drifted = minedSuggestion("s-drift");
        drifted.getStructuredEvidence().add(ProcessSuggestion.StructuredEvidence.builder()
                .type("DRIFT")
                .description("Since 2026-06-12: performer of 'Coverage Review': bob → carol")
                .build());
        store.save(drifted);
        stubLlm("Claim Intake starts the flow, Coverage Review validates it, Claim Payout ends it.");

        assertEquals(2, service.narrateFactSheet(FS));

        ProcessSuggestion reloaded = store.get("s-drift").orElseThrow();
        assertEquals("LLM", reloaded.getNarrativeSource());
        assertTrue(reloaded.getNarrative().endsWith(
                        " Changes since 2026-06-12: performer of 'Coverage Review': bob → carol."),
                "drift must survive the rewrite: " + reloaded.getNarrative());
    }

    @Test
    void hallucinatedProse_isRejected_templateStands() {
        // Mentions an invented step and omits "Coverage Review" — must fail the grounding gate.
        stubLlm("First the Fraud Investigation runs, then Claim Intake and Claim Payout follow.");

        assertEquals(0, service.narrateFactSheet(FS));

        ProcessSuggestion reloaded = store.get("s-1").orElseThrow();
        assertEquals("TEMPLATE", reloaded.getNarrativeSource(),
                "un-grounded LLM output must never replace the deterministic narrative");
        assertFalse(reloaded.getNarrative().contains("Fraud Investigation"));
    }

    @Test
    void noChatModel_leavesTemplateNarratives() {
        service.setLlmChat(null);
        assertEquals(0, service.narrateFactSheet(FS));
        assertEquals("TEMPLATE", store.get("s-1").orElseThrow().getNarrativeSource());
    }

    @Test
    void acceptedAndForeignSuggestions_areSkipped() {
        stubLlm("Claim Intake, Coverage Review, Claim Payout all mentioned.");
        ProcessSuggestion accepted = minedSuggestion("s-accepted");
        accepted.setAccepted(true);
        store.save(accepted);
        ProcessSuggestion legacy = minedSuggestion("s-legacy");
        legacy.setDiscoverySource("EMAIL_FLOW");
        store.save(legacy);

        assertEquals(1, service.narrateFactSheet(FS), "only the pending mined suggestion narrates");
        assertEquals("TEMPLATE", store.get("s-accepted").orElseThrow().getNarrativeSource());
        assertEquals("TEMPLATE", store.get("s-legacy").orElseThrow().getNarrativeSource());
    }

    @Test
    void listenerFiltersToMinedEvents() {
        stubLlm("Claim Intake, Coverage Review, Claim Payout.");
        service.onProcessMined(new ModelTrainedEvent(this, "psl", FS, tempDir.resolve("x.psl"), "psl-cascade"));
        assertEquals("TEMPLATE", store.get("s-1").orElseThrow().getNarrativeSource());

        service.onProcessMined(new ModelTrainedEvent(this, "psl", FS, tempDir.resolve("x.psl"), "psl-mined"));
        assertEquals("LLM", store.get("s-1").orElseThrow().getNarrativeSource());
    }

    @Test
    void promptCarriesTheStructuredFacts() {
        ProcessSuggestion s = minedSuggestion("s-prompt");
        s.getPhases().get(0).getSteps().get(2).getDependsOn().add("Coverage Review");
        String prompt = LlmProcessNarrationService.buildPrompt(
                s, List.of("Claim Intake", "Coverage Review", "Claim Payout"));
        assertTrue(prompt.contains("- Claim Intake"));
        assertTrue(prompt.contains("Claim Payout waits for Coverage Review"));
        assertTrue(prompt.contains("Entailed ordering:"));
        assertTrue(prompt.contains("Confidence: 60%"));
    }

    @Test
    void groundingGate_requiresEveryStepName() {
        List<String> steps = List.of("Claim Intake", "Claim Payout");
        assertTrue(LlmProcessNarrationService.isGrounded(
                "claim intake happens before the CLAIM PAYOUT.", steps), "case-insensitive");
        assertFalse(LlmProcessNarrationService.isGrounded("Only Claim Intake is mentioned.", steps));
        assertFalse(LlmProcessNarrationService.isGrounded("", steps));
        assertFalse(LlmProcessNarrationService.isGrounded(null, steps));
    }
}
