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
import ai.kompile.process.discovery.mining.convert.ProcessNarrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * The agentic FINAL synthesis step: select an agent from the registry, hand it the mined process
 * skeleton plus the fact sheet's graph — via the kompile MCP toolset it gets injected with
 * ({@code ask_graph_query}/{@code ask_graph_verify}/{@code ask_graph_explain}, graph search/
 * knowledge tools) — and bring back a FULLY DESCRIBED business process document.
 *
 * <p>Division of labour stays what the design mandates: the mined/entailed structure is
 * authoritative and travels IN the prompt; the agent's freedom is to <b>enrich</b> it from the
 * graph (real node titles, relation metadata, ontology types, KB facts it can verify itself) and
 * to write. The result passes the same grounding gate as narration — every mined step name must
 * appear, nothing invented — or it is rejected and the suggestion keeps its current
 * narrative/template. Synthesis is on-demand (agent runs cost minutes), unlike the cheap
 * auto-narration pass.</p>
 */
@Service
public class AgentProcessSynthesisService {

    private static final Logger log = LoggerFactory.getLogger(AgentProcessSynthesisService.class);

    static final int DEFAULT_TIMEOUT_SECONDS = 600;

    private final AgentChatService agentChatService;
    private final ProcessSuggestionStore suggestionStore;

    public AgentProcessSynthesisService(AgentChatService agentChatService,
                                        ProcessSuggestionStore suggestionStore) {
        this.agentChatService = agentChatService;
        this.suggestionStore = suggestionStore;
    }

    /** Outcome of one synthesis run. */
    public record SynthesisResult(boolean success, String document, String error) {
        public static SynthesisResult failure(String error) {
            return new SynthesisResult(false, null, error);
        }
    }

    /**
     * Synthesize the full process document for a stored suggestion with the chosen agent.
     * On success the document is persisted on the suggestion ({@code processDocument} +
     * {@code processDocumentSource}).
     */
    public SynthesisResult synthesize(String suggestionId, String agentName, int timeoutSeconds) {
        ProcessSuggestion suggestion = suggestionStore.get(suggestionId).orElse(null);
        if (suggestion == null) {
            return SynthesisResult.failure("Suggestion not found: " + suggestionId);
        }
        List<String> steps = ProcessNarrator.orderedStepNames(suggestion);
        if (steps.isEmpty()) {
            return SynthesisResult.failure("Suggestion has no steps to describe");
        }
        int timeout = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;

        AgentChatRequest request = new AgentChatRequest();
        request.setAgentName(agentName);
        request.setMessage(buildTask(suggestion, steps));
        request.setFactSheetId(suggestion.getFactSheetId());
        request.setInjectMcpTools(true);   // the whole point: the agent explores the graph itself
        request.setEnableRag(false);
        request.setIncludeHistory(false);
        request.setSkipPermissions(true);
        request.setTimeoutSeconds(timeout);

        log.info("Process synthesis: running agent '{}' for suggestion {} (fact sheet {}, {} steps)",
                agentName, suggestionId, suggestion.getFactSheetId(), steps.size());
        AgentChatService.SyncChatResult result = agentChatService.executeChatSync(request, timeout);
        if (!result.isSuccess()) {
            return SynthesisResult.failure(result.error() != null
                    ? result.error() : "agent exited with code " + result.exitCode());
        }
        String document = result.content() != null ? result.content().trim() : "";
        if (!isGrounded(document, steps)) {
            log.warn("Process synthesis: agent '{}' output failed grounding for suggestion {} — rejected",
                    agentName, suggestionId);
            return SynthesisResult.failure(
                    "Agent output failed the grounding gate (every mined step must appear verbatim)");
        }

        suggestion.setProcessDocument(document);
        suggestion.setProcessDocumentSource(agentName);
        suggestionStore.save(suggestion);
        log.info("Process synthesis: suggestion {} documented by '{}' ({} chars)",
                suggestionId, agentName, document.length());
        return new SynthesisResult(true, document, null);
    }

    /**
     * The task the agent runs: the authoritative mined skeleton + the instruction to enrich it
     * from the graph through its MCP tools, with a fixed output structure.
     */
    static String buildTask(ProcessSuggestion suggestion, List<String> steps) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are documenting a business process that was MINED AND ENTAILED from the ")
                .append("knowledge graph of fact sheet ").append(suggestion.getFactSheetId())
                .append(". The mined structure below is authoritative — do not change it.\n\n");

        sb.append("Process: ").append(suggestion.getName()).append('\n');
        sb.append(String.format(Locale.ROOT, "Confidence: %.0f%%%n", suggestion.getConfidence() * 100));
        if (suggestion.getDescription() != null) {
            sb.append("Discovery: ").append(suggestion.getDescription()).append('\n');
        }
        sb.append("\nSteps in mined order:\n");
        if (suggestion.getPhases() != null) {
            suggestion.getPhases().forEach(phase -> {
                if (phase.getSteps() == null) {
                    return;
                }
                phase.getSteps().forEach(step -> {
                    sb.append("- ").append(step.getName());
                    if (step.getDependsOn() != null && !step.getDependsOn().isEmpty()) {
                        sb.append(" (waits for: ").append(String.join(", ", step.getDependsOn())).append(')');
                    }
                    if (step.getRoleBinding() != null && !step.getRoleBinding().isBlank()) {
                        sb.append(" [role: ").append(step.getRoleBinding()).append(']');
                    }
                    if (step.getGraphNodeIds() != null && !step.getGraphNodeIds().isEmpty()) {
                        sb.append(" [graph nodes: ")
                                .append(String.join(", ", step.getGraphNodeIds().stream().limit(3).toList()))
                                .append(step.getGraphNodeIds().size() > 3 ? ", …]" : "]");
                    }
                    sb.append('\n');
                });
            });
        }
        if (suggestion.getStructuredEvidence() != null) {
            suggestion.getStructuredEvidence().forEach(ev -> {
                if ("ENTAILED".equals(ev.getType()) || "CONTRADICTION".equals(ev.getType())
                        || "HYBRID".equals(ev.getType())) {
                    sb.append(ev.getType()).append(": ").append(ev.getDescription()).append('\n');
                }
            });
        }

        sb.append("""

                YOUR TASK: produce a fully described business process document.
                Use the kompile MCP graph tools to enrich the skeleton with REAL detail from this
                fact sheet's graph before writing:
                - look up the listed graph node ids for titles and metadata,
                - ask_graph_query / ask_graph_verify / ask_graph_explain for the precedes(...) and
                  activity(...) facts and their derivations,
                - graph/ontology tools for the entity types and relation schema around each step.

                OUTPUT exactly this markdown structure and nothing else:
                # <process name>
                ## Purpose
                ## Trigger
                ## Steps
                (one numbered subsection per mined step, IN ORDER, using each step's EXACT name:
                 who performs it, what happens, inputs, outputs, what it waits for)
                ## Exceptions and contradictions
                ## Evidence and confidence

                HARD CONSTRAINTS:
                - Mention every mined step by its exact name. Do NOT invent steps, people, systems,
                  or numbers that are neither in the skeleton nor in the graph.
                - When you use graph detail, name the node/entity it came from.
                """);
        return sb.toString();
    }

    /** Grounding gate: every mined step name must appear (case-insensitive) plus the Steps section. */
    static boolean isGrounded(String document, List<String> steps) {
        if (document == null || document.isBlank() || !document.contains("## Steps")) {
            return false;
        }
        String lower = document.toLowerCase(Locale.ROOT);
        for (String step : steps) {
            if (!lower.contains(step.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }
}
