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
import ai.kompile.process.discovery.mining.convert.ProcessNarrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * The LLM FINAL step for mined processes: turn the structured suggestion into a real
 * business-process description. The mining/entailment stack stays load-bearing — the LLM only
 * narrates what was mined, and its output is validated for grounding (every step name must appear
 * verbatim; nothing may be invented). Un-grounded output is discarded in favour of the
 * deterministic {@link ProcessNarrator} template every suggestion already carries, so the
 * narrative field is ALWAYS coherent and never hallucinated.
 *
 * <p>Triggered automatically after each mining run ({@code ModelTrainedEvent(psl-mined)}) when a
 * chat model is configured; also invokable per fact sheet / per suggestion via
 * {@code ProcessNarrationController}.</p>
 */
@Service
public class LlmProcessNarrationService {

    private static final Logger log = LoggerFactory.getLogger(LlmProcessNarrationService.class);

    static final String LLM_SOURCE = "LLM";

    private static final String SYSTEM_PROMPT = """
            You write concise business-process descriptions for operations teams.
            You are given the MINED STRUCTURE of a process discovered from a company's data.
            Rules — these are hard constraints:
            - Describe ONLY the given structure. Mention EVERY step by its exact given name.
            - NEVER invent steps, systems, people, departments, numbers, or timings not provided.
            - 4 to 8 sentences of plain prose. No markdown, no headings, no bullet lists.
            - Cover: what the process does end to end, the order of work including any explicit
              dependencies, who is involved when roles are given, what was inferred beyond direct
              observation when entailed orderings are given, and caveats when contradictions are given.
            """;

    private final ProcessSuggestionStore suggestionStore;

    /** Optional — absent when no chat model is configured; the template narrative then stands. */
    @Autowired(required = false)
    private LLMChat llmChat;

    public LlmProcessNarrationService(ProcessSuggestionStore suggestionStore) {
        this.suggestionStore = suggestionStore;
    }

    /** Test seam for injecting (or clearing) the optional chat model. */
    void setLlmChat(LLMChat llmChat) {
        this.llmChat = llmChat;
    }

    /** Mining publishes {@code ModelTrainedEvent(psl, factSheetId, rules, "psl-mined")} per run. */
    @Async
    @EventListener
    public void onProcessMined(ModelTrainedEvent event) {
        if (!"psl-mined".equals(event.getBaseModelId())) {
            return;
        }
        narrateFactSheet(event.getFactSheetId());
    }

    /**
     * Upgrade the narrative of every pending mined suggestion for the fact sheet to LLM prose.
     *
     * @return number of suggestions whose narrative was upgraded
     */
    public int narrateFactSheet(long factSheetId) {
        if (llmChat == null) {
            log.debug("Process narration: no chat model configured — template narratives stand");
            return 0;
        }
        int upgraded = 0;
        for (ProcessSuggestion suggestion : suggestionStore.listByFactSheet(factSheetId)) {
            if (!"PROCESS_MINING".equals(suggestion.getDiscoverySource())
                    || Boolean.TRUE.equals(suggestion.getAccepted())) {
                continue;
            }
            if (narrateSuggestion(suggestion)) {
                upgraded++;
            }
        }
        if (upgraded > 0) {
            log.info("Process narration: upgraded {} suggestion narrative(s) for fact sheet {}",
                    upgraded, factSheetId);
        }
        return upgraded;
    }

    /**
     * Narrate one suggestion with the LLM; keep (or restore) the deterministic template when the
     * output fails the grounding gate.
     *
     * @return true when the LLM narrative passed grounding and was saved
     */
    public boolean narrateSuggestion(ProcessSuggestion suggestion) {
        if (llmChat == null || suggestion == null) {
            return false;
        }
        List<String> steps = ProcessNarrator.orderedStepNames(suggestion);
        if (steps.isEmpty()) {
            return false;
        }
        try {
            String content = llmChat.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(buildPrompt(suggestion, steps))
                    .call()
                    .content();
            if (isGrounded(content, steps)) {
                // The LLM regenerates the whole narrative — re-append the generation-drift clause
                // (rebuilt from the durable DRIFT evidence) so "what changed since last mine"
                // survives the rewrite.
                suggestion.setNarrative(content.trim() + ProcessNarrator.driftClause(suggestion)
                        + ProcessNarrator.conflictClause(suggestion));
                suggestion.setNarrativeSource(LLM_SOURCE);
                suggestionStore.save(suggestion);
                return true;
            }
            log.warn("Process narration: LLM output failed grounding for suggestion {} — keeping template",
                    suggestion.getId());
        } catch (Exception e) {
            log.warn("Process narration failed for suggestion {} — {}", suggestion.getId(), e.getMessage());
        }
        // Fallback: make sure the deterministic narrative is present and marked as such.
        if (suggestion.getNarrative() == null || suggestion.getNarrative().isBlank()
                || LLM_SOURCE.equals(suggestion.getNarrativeSource())) {
            suggestion.setNarrative(ProcessNarrator.narrate(suggestion) + ProcessNarrator.driftClause(suggestion)
                    + ProcessNarrator.conflictClause(suggestion));
            suggestion.setNarrativeSource(ProcessNarrator.TEMPLATE_SOURCE);
            suggestionStore.save(suggestion);
        }
        return false;
    }

    /** The structured, non-negotiable facts the LLM narrates from. */
    static String buildPrompt(ProcessSuggestion suggestion, List<String> steps) {
        StringBuilder sb = new StringBuilder();
        sb.append("Process name: ").append(suggestion.getName()).append('\n');
        sb.append(String.format(Locale.ROOT, "Confidence: %.0f%%%n", suggestion.getConfidence() * 100));
        if (suggestion.getDescription() != null) {
            sb.append("Discovery summary: ").append(suggestion.getDescription()).append('\n');
        }
        sb.append("Steps in mined order (use these exact names):\n");
        for (String step : steps) {
            sb.append("- ").append(step).append('\n');
        }
        if (suggestion.getPhases() != null) {
            suggestion.getPhases().forEach(phase -> {
                if (phase.getSteps() == null) {
                    return;
                }
                phase.getSteps().forEach(step -> {
                    if (step.getDependsOn() != null && !step.getDependsOn().isEmpty()) {
                        sb.append("Dependency: ").append(step.getName())
                                .append(" waits for ").append(String.join(", ", step.getDependsOn()))
                                .append('\n');
                    }
                    if (step.getRoleBinding() != null && !step.getRoleBinding().isBlank()) {
                        sb.append("Role: ").append(step.getName())
                                .append(" is performed by ").append(step.getRoleBinding()).append('\n');
                    }
                });
            });
        }
        if (suggestion.getStructuredEvidence() != null) {
            suggestion.getStructuredEvidence().forEach(ev -> {
                if ("ENTAILED".equals(ev.getType())) {
                    sb.append("Entailed ordering: ").append(ev.getDescription()).append('\n');
                } else if ("CONTRADICTION".equals(ev.getType())) {
                    sb.append("Contradiction: ").append(ev.getDescription()).append('\n');
                }
            });
        }
        return sb.toString();
    }

    /** Grounding gate: every mined step name must appear in the narrative (case-insensitive). */
    static boolean isGrounded(String narrative, List<String> steps) {
        if (narrative == null || narrative.isBlank()) {
            return false;
        }
        String lower = narrative.toLowerCase(Locale.ROOT);
        for (String step : steps) {
            if (!lower.contains(step.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }
}
