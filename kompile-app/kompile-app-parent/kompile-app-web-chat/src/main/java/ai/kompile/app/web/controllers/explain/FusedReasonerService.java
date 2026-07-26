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

import ai.kompile.core.graphrag.GraphRagService;
import ai.kompile.core.graphrag.agent.ExtractionLlmService;
import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import ai.kompile.graph.reasoning.explain.CompositeReasoningTrail;
import ai.kompile.graph.reasoning.explain.EvidenceAccumulator;
import ai.kompile.graph.reasoning.explain.ModalityEvidence;
import ai.kompile.graph.reasoning.explain.ModalityKind;
import ai.kompile.graph.reasoning.explain.ReasoningTrail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Fan-out fuser: given a target / question, runs ALL applicable reasoning engines
 * concurrently and merges their evidence into a single {@link CompositeReasoningTrail}.
 *
 * <h3>Engines invoked (in parallel)</h3>
 * <ol>
 *   <li>GROUNDING — FOL KB derivation tree (only when the target looks like an atom key)</li>
 *   <li>HYBRID — structural + semantic ranking</li>
 *   <li>PSL — HL-MRF soft-logic MAP inference</li>
 *   <li>MEBN — multi-entity Bayesian variable elimination</li>
 *   <li>CAUSAL — causal attribution chains</li>
 *   <li>GRAPH-RAG — entity / relationship evidence + source text chunks</li>
 * </ol>
 *
 * <p>Existing single-mode paths (via {@link ExplainOrchestrator#explain}) are not changed;
 * this service delegates to the orchestrator for the symbolic modes so it does not
 * duplicate any engine logic.</p>
 *
 * <h3>LLM synthesis</h3>
 * After all modalities finish, {@link ExtractionLlmServiceRegistry#getOrFallback} is called
 * to find an available LLM provider.  The provider synthesises a single natural-language answer
 * citing which modality supports each claim.  Synthesis is fully guarded: if no provider is
 * configured, unavailable, or throws, the fused call still returns a valid
 * {@link CompositeReasoningTrail} with {@code naturalLanguageAnswer} empty.
 * Toggle via {@link #setSynthesisEnabled(boolean)}.
 */
@Slf4j
@Service
public class FusedReasonerService {

    private static final int MAX_DETAILS_PER_MODALITY = 5;
    private static final int MAX_PROMPT_CHARS = 12_000;

    private final ExplainOrchestrator orchestrator;

    @Nullable
    private final GraphRagService graphRagService;

    /**
     * Optional: when present, synthesis is attempted after all modalities finish.
     * Injected via {@link Autowired}; absent in test contexts that don't wire the registry.
     */
    @Nullable
    @Autowired(required = false)
    private ExtractionLlmServiceRegistry extractionLlmServiceRegistry;

    /**
     * Global toggle for LLM synthesis.  Defaults to {@code true}; set to {@code false}
     * to skip synthesis without removing the registry (e.g. for testing or low-latency mode).
     */
    private volatile boolean synthesisEnabled = true;

    @Autowired
    public FusedReasonerService(ExplainOrchestrator orchestrator,
                                @Nullable GraphRagService graphRagService) {
        this.orchestrator = orchestrator;
        this.graphRagService = graphRagService;
    }

    /** Test seam — inject (or null out) the optional LLM registry. */
    void setExtractionLlmServiceRegistry(@Nullable ExtractionLlmServiceRegistry registry) {
        this.extractionLlmServiceRegistry = registry;
    }

    /** Toggle LLM synthesis without removing the registry. */
    public void setSynthesisEnabled(boolean enabled) {
        this.synthesisEnabled = enabled;
    }

    /**
     * Run all engines concurrently for {@code target} scoped to {@code factSheetId}, and return
     * a single fused evidence trace.
     *
     * @param target      atom key, entity id, or natural-language question
     * @param factSheetId fact-sheet scope (0 = global)
     * @param depthHint   derivation depth cap for grounding (0 = lib default)
     * @param question    optional natural-language question string (used in trace; may be null)
     * @return merged {@link CompositeReasoningTrail} containing one entry per modality that ran
     */
    public CompositeReasoningTrail explainAll(String target,
                                              long factSheetId,
                                              int depthHint,
                                              @Nullable String question) {

        String effectiveQuestion = question != null && !question.isBlank()
                ? question
                : "Explain: " + target;

        EvidenceAccumulator accumulator = new EvidenceAccumulator(target, effectiveQuestion);

        // ── Launch all symbolic engines concurrently ──────────────────────────────
        boolean isAtomKey = target.contains("(") && target.contains(")");
        boolean isCausalTarget = target.startsWith("causal:");

        List<CompletableFuture<ModalityEvidence>> futures = new ArrayList<>();

        // GROUNDING — only for atom-key shaped targets
        if (isAtomKey) {
            futures.add(runMode("GROUNDING", target, factSheetId, depthHint, ModalityKind.GROUNDING));
        }

        // HYBRID — structural + semantic ranking; most useful for bare entity ids
        futures.add(runMode("HYBRID", target, factSheetId, depthHint, ModalityKind.HYBRID));

        // PSL — soft-logic
        futures.add(runMode("PSL", target, factSheetId, depthHint, ModalityKind.PSL));

        // MEBN — Bayesian
        futures.add(runMode("MEBN", target, factSheetId, depthHint, ModalityKind.MEBN));

        // CAUSAL — strip "causal:" prefix for the query; use the raw target for bare ids
        String causalTarget = isCausalTarget ? target : "causal:" + target;
        futures.add(runMode("CAUSAL", causalTarget, factSheetId, depthHint, ModalityKind.CAUSAL));

        // GRAPH-RAG — entity + text-chunk evidence
        if (graphRagService != null) {
            futures.add(runGraphRag(target, factSheetId));
        }

        // ── Await all engines and collect results ─────────────────────────────────
        List<ModalityEvidence> collected = new ArrayList<>();
        for (CompletableFuture<ModalityEvidence> future : futures) {
            try {
                ModalityEvidence ev = future.join();
                if (ev != null) {
                    accumulator.add(ev);
                    collected.add(ev);
                }
            } catch (RuntimeException ex) {
                log.warn("FusedReasonerService: one engine future failed (target='{}'): {}",
                        target, ex.getMessage());
                // Individual engine failures do not abort the fused run; they are simply skipped.
            }
        }

        // ── LLM synthesis — guarded; fused call never fails because synthesis failed ─
        if (synthesisEnabled && extractionLlmServiceRegistry != null) {
            try {
                ExtractionLlmService llmService =
                        extractionLlmServiceRegistry.getOrFallback(null);
                if (llmService != null && llmService.isAvailable()) {
                    String answer = synthesize(llmService, effectiveQuestion, target, collected);
                    if (answer != null && !answer.isBlank()) {
                        accumulator.withAnswer(answer);
                    }
                } else {
                    log.debug("FusedReasonerService: synthesis skipped — no LLM provider available");
                }
            } catch (Exception ex) {
                log.debug("FusedReasonerService: synthesis failed (target='{}'), continuing without answer: {}",
                        target, ex.getMessage());
            }
        }

        CompositeReasoningTrail trail = accumulator.build();
        log.info("FusedReasonerService: target='{}' factSheet={} modalities={} fused={}",
                target, factSheetId, trail.modalities().size(),
                String.format("%.3f", trail.fusedConfidence()));
        return trail;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /**
     * Run one symbolic mode via the existing {@link ExplainOrchestrator} on a background thread.
     */
    private CompletableFuture<ModalityEvidence> runMode(String mode,
                                                         String target,
                                                         long factSheetId,
                                                         int depthHint,
                                                         ModalityKind kind) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ReasoningTrail trail = orchestrator.explain(target, factSheetId, depthHint, mode);
                return ModalityEvidence.fromTrail(kind, trail);
            } catch (Exception ex) {
                log.debug("FusedReasonerService: mode={} failed for target='{}': {}",
                        mode, target, ex.getMessage());
                return ModalityEvidence.of(kind, Double.NaN,
                        mode + " engine unavailable: " + ex.getMessage(),
                        List.of());
            }
        });
    }

    /**
     * Build a synthesis prompt from the question and collected modality evidence, then
     * call the LLM and return its text answer.
     *
     * <p>The prompt asks for a single concise natural-language answer that:
     * <ul>
     *   <li>directly addresses the question / target</li>
     *   <li>names which modality/evidence supports each key claim</li>
     *   <li>stays under ~250 words</li>
     * </ul>
     *
     * <p>The prompt is capped at {@value #MAX_PROMPT_CHARS} characters to avoid blowing
     * the context window on very large evidence sets.</p>
     */
    private String synthesize(ExtractionLlmService llmService,
                              String question,
                              String target,
                              List<ModalityEvidence> evidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a reasoning assistant synthesizing evidence from multiple reasoning engines.\n\n");
        sb.append("Question / target: ").append(question).append("\n\n");
        sb.append("Evidence collected from ").append(evidence.size()).append(" reasoning modalities:\n\n");

        for (ModalityEvidence ev : evidence) {
            sb.append("## ").append(ev.kind().name());
            if (!Double.isNaN(ev.confidence())) {
                sb.append(String.format(" (confidence=%.3f)", ev.confidence()));
            }
            sb.append("\n");
            if (!ev.summary().isBlank()) {
                sb.append("Summary: ").append(ev.summary()).append("\n");
            }
            if (!ev.details().isEmpty()) {
                StringJoiner detailJoiner = new StringJoiner("\n  - ", "Key details:\n  - ", "\n");
                ev.details().stream().limit(MAX_DETAILS_PER_MODALITY).forEach(detailJoiner::add);
                sb.append(detailJoiner);
            }
            sb.append("\n");
            // Safety cap to avoid massive prompts
            if (sb.length() > MAX_PROMPT_CHARS) {
                sb.append("[...evidence truncated for brevity...]\n");
                break;
            }
        }

        sb.append("---\n");
        sb.append("Using ALL the evidence above, write ONE concise natural-language answer (under 250 words) ");
        sb.append("that directly addresses the question '").append(target).append("'. ");
        sb.append("For each key claim, indicate which modality supports it ");
        sb.append("(e.g. '[PSL]', '[CAUSAL]', '[GRAPH_RAG]'). ");
        sb.append("If evidence is conflicting or weak, say so. Do NOT invent facts not in the evidence.");

        String prompt = sb.toString();
        log.debug("FusedReasonerService: calling synthesis LLM, prompt length={}", prompt.length());
        return llmService.complete(prompt);
    }

    /**
     * Run a HYBRID graph-RAG query and convert the result into {@link ModalityEvidence}.
     */
    private CompletableFuture<ModalityEvidence> runGraphRag(String target, long factSheetId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                GraphRagQuery query = GraphRagQuery.builder()
                        .query(target)
                        .searchType(SearchType.HYBRID)
                        .k(10)
                        .hopDepth(2)
                        .maxTraversalNodes(50)
                        .factSheetId(factSheetId > 0 ? factSheetId : null)
                        .conversationId(UUID.randomUUID().toString())
                        .build();

                GraphRagResult result = graphRagService.answerQuery(query);

                List<String> details = new ArrayList<>();
                if (result.getEntities() != null) {
                    result.getEntities().forEach(e -> details.add(
                            "entity:" + e.getTitle()
                            + (e.getType() != null ? " [" + e.getType() + "]" : "")));
                }
                if (result.getRelationships() != null) {
                    result.getRelationships().forEach(r -> details.add(
                            "rel:" + r.getDescription()));
                }
                if (result.getSourceChunks() != null) {
                    result.getSourceChunks().stream()
                            .limit(5)
                            .forEach(chunk -> details.add("chunk:" + chunk));
                }

                String summary = result.getAnswer() != null && !result.getAnswer().isBlank()
                        ? result.getAnswer()
                        : "Graph-RAG retrieved " + details.size() + " evidence item(s) for '" + target + "'.";

                // Graph-RAG does not expose a scalar confidence; use a nominal 0.5 when it found evidence
                double conf = details.isEmpty() ? 0.0 : 0.5;

                return ModalityEvidence.of(ModalityKind.GRAPH_RAG, conf, summary, details);
            } catch (Exception ex) {
                log.debug("FusedReasonerService: graph-RAG failed for target='{}': {}", target, ex.getMessage());
                return ModalityEvidence.of(ModalityKind.GRAPH_RAG, Double.NaN,
                        "Graph-RAG unavailable: " + ex.getMessage(), List.of());
            }
        });
    }
}
