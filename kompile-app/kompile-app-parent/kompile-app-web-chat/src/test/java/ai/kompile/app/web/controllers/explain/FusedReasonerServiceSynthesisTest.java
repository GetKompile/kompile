/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.web.controllers.explain;

import ai.kompile.core.graphrag.agent.ExtractionLlmService;
import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry;
import ai.kompile.graph.reasoning.explain.CompositeReasoningTrail;
import ai.kompile.graph.reasoning.explain.ReasoningTrail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FusedReasonerService} LLM synthesis behaviour.
 *
 * <p>Uses Mockito to stub {@link ExplainOrchestrator} (so no Spring context is needed)
 * and an in-process {@link ExtractionLlmServiceRegistry} / {@link ExtractionLlmService}
 * mock to drive the synthesis path.</p>
 *
 * <p>Two key contracts are verified:
 * <ol>
 *   <li>When the LLM returns a non-blank text the {@code naturalLanguageAnswer} is
 *       propagated through the {@link CompositeReasoningTrail}.</li>
 *   <li>When the LLM is null or throws, the method returns normally (no exception)
 *       and {@code naturalLanguageAnswer} is empty.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FusedReasonerService — LLM synthesis")
class FusedReasonerServiceSynthesisTest {

    @Mock
    private ExplainOrchestrator orchestrator;

    @Mock
    private ExtractionLlmServiceRegistry registry;

    @Mock
    private ExtractionLlmService llmService;

    private FusedReasonerService service;

    /** A minimal ReasoningTrail returned by the mocked orchestrator for every engine call. */
    private static ReasoningTrail minimalTrail(String mode) {
        return ReasoningTrail.builder("test-target")
                .inferenceMode(mode)
                .confidence(0.4)
                .naturalLanguageSummary(mode + " found some evidence.")
                .build();
    }

    @BeforeEach
    void setUp() {
        // The orchestrator mock returns a minimal trail for any engine call.
        when(orchestrator.explain(anyString(), anyLong(), anyInt(), anyString()))
                .thenAnswer(inv -> minimalTrail(inv.getArgument(3)));

        // Service under test with no graphRagService (optional component → null)
        service = new FusedReasonerService(orchestrator, null);
    }

    @Nested
    @DisplayName("when LLM registry is present and provider is available")
    class WhenLlmAvailable {

        @BeforeEach
        void wireLlm() {
            when(registry.getOrFallback(null)).thenReturn(llmService);
            when(llmService.isAvailable()).thenReturn(true);
            when(llmService.complete(anyString()))
                    .thenReturn("Synthesised answer: the target is supported by HYBRID [PSL] evidence.");
            service.setExtractionLlmServiceRegistry(registry);
        }

        @Test
        @DisplayName("naturalLanguageAnswer is populated in the trail")
        void answerIsSet() {
            CompositeReasoningTrail trail = service.explainAll("test-target", 0L, 0, "What is test-target?");
            assertThat(trail.hasAnswer()).isTrue();
            assertThat(trail.naturalLanguageAnswer())
                    .contains("Synthesised answer");
        }

        @Test
        @DisplayName("FusedExplainResponse.fromTrail propagates the answer to the response DTO")
        void answerFlowsToResponse() {
            CompositeReasoningTrail trail = service.explainAll("test-target", 0L, 0, "What is test-target?");
            FusedExplainResponse response = FusedExplainResponse.fromTrail(trail);
            assertThat(response.naturalLanguageAnswer()).isEqualTo(trail.naturalLanguageAnswer());
            assertThat(response.naturalLanguageAnswer()).isNotBlank();
            assertThat(response.llmContext()).contains("Reasoning trace (FUSION): id=trace.root; target=test-target");
            assertThat(response.llmContext()).contains("Answer: Synthesised answer");
            assertThat(response.attributionIndex())
                    .anyMatch(step -> "trace.root".equals(step.stepId()))
                    .anyMatch(step -> step.stepId().startsWith("trace.modality."));
            assertThat(response.attributionIndex())
                    .anyMatch(step -> step.stepId().startsWith("trace.modality.")
                            && step.evidenceRefs().stream().anyMatch(ref -> ref.modality() != null));
            assertThat(response.traceGaps())
                    .anyMatch(gap -> "MISSING_MODALITY_DETAIL".equals(gap.gapType())
                            && gap.relatedStepIds().contains("trace.modality.0"));
            // Four independent modalities at 0.4 fuse to ~0.67 — EvidenceAccumulator combines
            // corroborating opinions, so the fused root sits ABOVE the analyzer's 0.60
            // LOW_CONFIDENCE threshold even though every individual modality is below it.
            // Asserting the absence here is what pins that fusion behaviour down; the
            // LOW_CONFIDENCE gap itself is covered by ReasoningTraceGapAnalyzerTest.
            assertThat(trail.fusedConfidence()).isGreaterThan(0.60);
            assertThat(response.traceGaps())
                    .noneMatch(gap -> "LOW_CONFIDENCE".equals(gap.gapType()));
        }
    }

    @Nested
    @DisplayName("when LLM registry is null")
    class WhenRegistryNull {

        @Test
        @DisplayName("explainAll returns normally — no exception, no answer")
        void noExceptionWhenRegistryNull() {
            service.setExtractionLlmServiceRegistry(null);
            CompositeReasoningTrail trail = assertDoesNotThrow(
                    () -> service.explainAll("test-target", 0L, 0, null));
            assertNotNull(trail);
            assertFalse(trail.hasAnswer());
        }
    }

    @Nested
    @DisplayName("when LLM provider throws")
    class WhenLlmThrows {

        @BeforeEach
        void wireLlm() {
            when(registry.getOrFallback(null)).thenReturn(llmService);
            when(llmService.isAvailable()).thenReturn(true);
            doThrow(new ExtractionLlmService.ExtractionLlmException("simulated LLM failure"))
                    .when(llmService).complete(anyString());
            service.setExtractionLlmServiceRegistry(registry);
        }

        @Test
        @DisplayName("explainAll returns normally — no exception propagated, no answer set")
        void noExceptionWhenLlmThrows() {
            CompositeReasoningTrail trail = assertDoesNotThrow(
                    () -> service.explainAll("test-target", 0L, 0, "Why?"));
            assertNotNull(trail);
            assertFalse(trail.hasAnswer());
        }
    }

    @Nested
    @DisplayName("when synthesis is disabled via toggle")
    class WhenSynthesisDisabled {

        @Test
        @DisplayName("LLM is not called and answer is empty even when registry is wired")
        void synthesisSkippedWhenToggleOff() {
            // Wire registry but disable synthesis — complete() must NOT be called
            service.setExtractionLlmServiceRegistry(registry);
            service.setSynthesisEnabled(false);

            CompositeReasoningTrail trail = service.explainAll("test-target", 0L, 0, "Q?");

            assertThat(trail).isNotNull();
            assertThat(trail.hasAnswer()).isFalse();
            // Verify the LLM was never consulted
            verify(registry, never()).getOrFallback(any());
        }
    }

    @Nested
    @DisplayName("when LLM provider is unavailable")
    class WhenLlmUnavailable {

        @Test
        @DisplayName("explainAll skips synthesis silently — no exception, no answer")
        void noAnswerWhenLlmUnavailable() {
            when(registry.getOrFallback(null)).thenReturn(llmService);
            when(llmService.isAvailable()).thenReturn(false);
            service.setExtractionLlmServiceRegistry(registry);

            CompositeReasoningTrail trail = service.explainAll("test-target", 0L, 0, null);

            assertThat(trail).isNotNull();
            assertThat(trail.hasAnswer()).isFalse();
        }
    }
}
