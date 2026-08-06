/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.process.discovery;

import ai.kompile.core.graphrag.agent.ExtractionLlmService;
import ai.kompile.utils.StringUtils;
import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.text.BreakIterator;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Uses an LLM to discover business processes from knowledge graph data.
 * <p>
 * The service serializes graph nodes and edges into a structured text context,
 * prompts the LLM to identify business processes (with phases, steps, actors,
 * and hierarchies), and parses the JSON response into {@link ProcessSuggestion}
 * objects that can be accepted into the process engine.
 * <p>
 * This complements the pattern-based discovery in {@link ProcessDiscoveryServiceImpl}
 * by handling cases where processes are described in natural language rather than
 * expressed through structural graph patterns (e.g., an email that describes a
 * multi-step approval workflow, or a document that outlines an onboarding procedure).
 */
@Slf4j
@Service
@ConditionalOnBean({KnowledgeGraphService.class, ExtractionLlmServiceRegistry.class})
public class LlmProcessDiscoveryService {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private static final int MAX_NODES_FOR_CONTEXT = 200;
    private static final int MAX_SNIPPETS_PER_DOCUMENT = 10;
    private static final int MAX_CONTENT_CHARS = 6000;

    private final KnowledgeGraphService knowledgeGraphService;
    private final ExtractionLlmServiceRegistry llmServiceRegistry;

    @Autowired
    public LlmProcessDiscoveryService(KnowledgeGraphService knowledgeGraphService,
                                       ExtractionLlmServiceRegistry llmServiceRegistry) {
        this.knowledgeGraphService = knowledgeGraphService;
        this.llmServiceRegistry = llmServiceRegistry;
    }

    /**
     * Use an LLM to discover processes from knowledge graph data.
     *
     * @param graphNodeIds optional scope (null = all nodes)
     * @param options      options including "llmProvider" (String) and "minConfidence" (Double)
     * @return list of LLM-discovered process suggestions
     */
    public List<ProcessSuggestion> discoverProcesses(List<String> graphNodeIds, Map<String, Object> options) {
        String preferredProvider = options != null ? (String) options.get("llmProvider") : null;
        ExtractionLlmService llmService = llmServiceRegistry.getOrFallback(preferredProvider);
        if (llmService == null) {
            log.warn("No LLM provider available for process discovery");
            return List.of();
        }

        // Gather graph context
        GraphContext ctx = buildGraphContext(graphNodeIds);
        if (ctx.nodes.isEmpty()) {
            log.debug("No graph nodes found for LLM process discovery");
            return List.of();
        }

        log.info("Calling LLM ({}) for split process discovery with {} nodes, {} snippets, {} edges",
                llmService.getId(), ctx.nodes.size(), ctx.snippets.size(), ctx.edges.size());
        List<ProcessSuggestion> suggestions = new ArrayList<>(
                runSplitDiscovery(ctx, llmService, "process discovery"));

        // Apply confidence filter
        double minConfidence = 0.5;
        if (options != null && options.get("minConfidence") instanceof Number n) {
            minConfidence = n.doubleValue();
        }
        double finalMinConfidence = minConfidence;
        suggestions.removeIf(s -> s.getConfidence() < finalMinConfidence);

        log.info("LLM process discovery found {} suggestions (provider: {})",
                suggestions.size(), llmService.getId());
        return suggestions;
    }

    /**
     * Check if any LLM provider is available for process discovery.
     */
    public boolean isAvailable() {
        return llmServiceRegistry.getOrFallback(null) != null;
    }

    /**
     * Discover processes scoped to a specific fact sheet's knowledge graph.
     *
     * @param factSheetId the fact sheet whose KG to analyze
     * @param options     options including "llmProvider" and "minConfidence"
     * @return LLM-discovered process suggestions with factSheetId set
     */
    public List<ProcessSuggestion> discoverProcessesForFactSheet(Long factSheetId, Map<String, Object> options) {
        String preferredProvider = options != null ? (String) options.get("llmProvider") : null;
        ExtractionLlmService llmService = llmServiceRegistry.getOrFallback(preferredProvider);
        if (llmService == null) {
            log.warn("No LLM provider available for process discovery");
            return List.of();
        }

        // Gather graph context scoped to the fact sheet
        GraphContext ctx = buildGraphContextForFactSheet(factSheetId);
        if (ctx.nodes.isEmpty()) {
            log.debug("No graph nodes found for factSheetId={}", factSheetId);
            return List.of();
        }

        log.info("Calling LLM ({}) for split fact-sheet process discovery (factSheetId={}) with {} nodes, {} snippets, {} edges",
                llmService.getId(), factSheetId, ctx.nodes.size(), ctx.snippets.size(), ctx.edges.size());
        List<ProcessSuggestion> suggestions = new ArrayList<>(
                runSplitDiscovery(ctx, llmService, "factSheetId=" + factSheetId));

        double minConfidence = 0.5;
        if (options != null && options.get("minConfidence") instanceof Number n) {
            minConfidence = n.doubleValue();
        }
        double finalMinConfidence = minConfidence;
        suggestions.removeIf(s -> s.getConfidence() < finalMinConfidence);

        // Stamp factSheetId on all suggestions
        suggestions.forEach(s -> s.setFactSheetId(factSheetId));

        log.info("LLM process discovery for factSheetId={} found {} suggestions (provider: {})",
                factSheetId, suggestions.size(), llmService.getId());
        return suggestions;
    }

    // ── Graph context gathering ─────────────────────────────────────────────

    record GraphContext(List<GraphNode> nodes, List<GraphNode> snippets, List<GraphEdge> edges) {}

    /** One grounded activity from the first model decision; IDs are assigned by the engine. */
    record DiscoveredStep(String id, String name, String description, String stepType,
                          String sourceNodeId, String evidence) {}

    /** Actor ballot entry owned by the engine; the model sees only the ordinal and display name. */
    record ActorCandidate(int ordinal, String name, String nodeId) {}

    private List<ProcessSuggestion> runSplitDiscovery(GraphContext context,
                                                       ExtractionLlmService llmService,
                                                       String scope) {
        String stepResponse;
        try {
            stepResponse = llmService.complete(buildStepExtractionPrompt(context));
        } catch (Exception e) {
            log.error("LLM process step extraction failed for {}: {}", scope, e.getMessage());
            return List.of();
        }

        JsonNode first = responseObject(stepResponse);
        if (first != null && first.has("processes")) {
            // Source compatibility for providers/tests that still answer the former one-shot
            // contract. New prompts never request this shape, but accepting it avoids a flag day.
            return parseResponse(stepResponse);
        }
        List<DiscoveredStep> steps = parseStepExtractionResponse(stepResponse, context);
        if (steps.isEmpty() && isExplicitEmptyStepBallot(first)) {
            try {
                stepResponse = llmService.complete(buildStepExtractionRepairPrompt(context));
                steps = parseStepExtractionResponse(stepResponse, context);
            } catch (Exception e) {
                log.error("LLM process step validation retry failed for {}: {}", scope, e.getMessage());
                return List.of();
            }
        }
        if (steps.isEmpty()) {
            return List.of();
        }

        String organizationResponse;
        try {
            organizationResponse = llmService.complete(buildOrganizationPrompt(context, steps));
        } catch (Exception e) {
            log.error("LLM process organization failed for {}: {}", scope, e.getMessage());
            return List.of();
        }
        Map<String, String> assigneeByStep = runActorAssignments(
                context, llmService, steps, scope);
        return parseOrganizationResponse(organizationResponse, context, steps, assigneeByStep);
    }

    String buildStepExtractionPrompt(GraphContext context) {
        return """
                TASK: Extract only the explicit process activities supported by the source material.
                Do not name a process, create phases, assign actors, or infer missing activities.

                ACTIVITY CHECK:
                - A source statement that a person, team, system, or role performs work is an explicit
                  activity even when it is ordinary prose rather than a numbered procedure.
                - Coordinated action verbs are separate activities. Preserve their source order.
                - If at least one explicit action is present, returning an empty steps array is invalid
                  and the response will be returned with validation feedback.

                """ + graphEvidenceBlock(context) + """

                OUTPUT CONTRACT:
                - Begin exactly with {"steps":[ and return one raw JSON object.
                - Each step object requires: "name", "description", "stepType", and "evidence".
                - "stepType" is exactly one of AUTO, HUMAN, APPROVE, TOOL_CALL, EXCEL_COMPUTE,
                  SCRIPT, or HTTP_CALL.
                - "evidence" is one exact contiguous substring copied from the source material above.
                  When one source sentence contains coordinated activities, copy that whole sentence for
                  each supported activity; never manufacture punctuation to turn a clause into a sentence.
                - Optional "sourceNodeId" must be copied from an ID printed in square brackets above.
                - Preserve source order. Emit each distinct activity once. Do not emit IDs or actors.
                - If no activity is explicitly supported, return exactly {"steps":[]}.
                - Output JSON only, with no markdown or commentary.
                """;
    }

    String buildStepExtractionRepairPrompt(GraphContext context) {
        return """
                VALIDATION RETRY: The previous response returned an empty steps array. Re-read every
                source sentence and coordinated clause. An explicit actor performing an action is a
                supported activity; do not require numbered instructions or the word "process".
                Return all explicit activities that pass the evidence-copy rules. Keep steps empty only
                when the source truly states no action.

                """ + buildStepExtractionPrompt(context);
    }

    private static boolean isExplicitEmptyStepBallot(JsonNode root) {
        JsonNode steps = root == null ? null : root.get("steps");
        return steps != null && steps.isArray() && steps.isEmpty();
    }

    String buildOrganizationPrompt(GraphContext context, List<DiscoveredStep> steps) {
        StringBuilder prompt = new StringBuilder("""
                TASK: Group and order the engine-fixed activities into supported processes.
                Decide only process membership, process name, and step order. Actor assignment is a
                separate decision. Never add, rename, rewrite, or omit an activity's evidence.

                ENGINE-FIXED STEP BALLOT:
                """);
        for (DiscoveredStep step : steps) {
            prompt.append("- id=").append(step.id())
                    .append(" | name=").append(step.name())
                    .append(" | type=").append(step.stepType())
                    .append(" | evidence=").append(step.evidence()).append('\n');
        }
        prompt.append("""

                OUTPUT CONTRACT:
                - Return one raw JSON object with top-level key "processes" (array).
                - Each process requires "name", "description", "confidence", and "orderedStepIds".
                  Optional "phaseName" names the single evidence-supported phase. Confidence is a
                  number from 0.0 through 1.0.
                - orderedStepIds is a flat array of ids copied exactly from the fixed ballot in source
                  order. Do not emit nested phases, assignments, actors, step objects, graph node IDs,
                  evidence strings, discoverySource, generic phases, or child processes.
                - Quote every complete id, including its "step-" prefix. For example, copy "step-2";
                  never shorten it to the number 2.
                - Use each fixed step id at most once across the response. Include every fixed step that
                  belongs to a coherent process; never substitute a different id for the current step.
                - A source sequence connected by words such as First, Next, Then, and Finally normally
                  belongs to one coherent process. Emit multiple processes only when the source supports
                  genuinely separate workflows. Process names describe the source work; never name a
                  process after this prompt, the ballot, or an engine instruction.
                - If the fixed steps do not support a coherent process, return exactly {"processes":[]}.
                - Output JSON only, with no markdown or commentary.
                """);
        return prompt.toString();
    }

    String buildActorAssignmentPrompt(GraphContext context, DiscoveredStep step) {
        Integer hintedOrdinal = uniqueActorHintOrdinal(context, step);
        StringBuilder prompt = new StringBuilder("""
                TASK: Assign at most one actor to ONE engine-fixed process activity.
                Do only this actor decision. The activity and its evidence are fixed.

                ENGINE-FIXED STEP:
                """);
        prompt.append("- id=").append(step.id())
                .append(" | name=").append(step.name())
                .append(" | evidence=").append(step.evidence()).append('\n');
        prompt.append("\nENGINE-FIXED ACTOR BALLOT:\n");
        List<ActorCandidate> actors = actorCandidates(context);
        if (actors.isEmpty()) {
            prompt.append("none\n");
        } else {
            for (ActorCandidate actor : actors) {
                prompt.append("- ordinal=").append(actor.ordinal())
                        .append(" | name=").append(actor.name()).append('\n');
            }
        }
        prompt.append("\nENGINE LEXICAL CANDIDATE HINT:\n");
        if (hintedOrdinal == null) {
            prompt.append("- none or ambiguous; decide only from the fixed step and evidence.\n");
        } else {
            ActorCandidate hinted = actors.stream()
                    .filter(actor -> actor.ordinal() == hintedOrdinal).findFirst().orElseThrow();
            prompt.append("- exactly one ballot actor begins the fixed activity name or its evidence: ordinal=")
                    .append(hinted.ordinal()).append(" | name=").append(hinted.name()).append("\n")
                    .append("- The performer is explicit, so actorOrdinal must be ")
                    .append(hinted.ordinal()).append(" and must not be null.\n")
                    .append("- SAFE SHAPE FOR THIS BALLOT: {\"assignment\":{\"actorOrdinal\":")
                    .append(hinted.ordinal())
                    .append(",\"confidence\":1.0,\"reason\":\"fixed activity explicitly names the ballot actor\"}}\n");
        }
        prompt.append("""

                OUTPUT CONTRACT:
                - Begin exactly with {"assignment":{"actorOrdinal": and return actorOrdinal,
                  confidence, and reason inside that one assignment object.
                - actorOrdinal is the ballot ordinal only when the fixed evidence explicitly identifies
                  that actor as performing this activity. Otherwise leave it null.
                - Match the actor to the fixed activity's action. When the step name starts with one
                  ballot actor and the evidence also mentions another actor doing a different action,
                  choose the actor who performs the fixed step, not the earlier co-mention.
                - confidence is a JSON number from 0.0 through 1.0. Do not output a step id, actor name,
                  graph node id, process, phase, evidence, or second assignment.
                - Output JSON only, with no markdown or commentary.
                """);
        return prompt.toString();
    }

    private Map<String, String> runActorAssignments(GraphContext context,
                                                     ExtractionLlmService llmService,
                                                     List<DiscoveredStep> steps,
                                                     String scope) {
        Map<Integer, ActorCandidate> actors = actorCandidates(context).stream()
                .collect(Collectors.toMap(ActorCandidate::ordinal, actor -> actor));
        if (actors.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (DiscoveredStep step : steps) {
            try {
                String response = llmService.complete(buildActorAssignmentPrompt(context, step));
                Integer parsedOrdinal = parseActorOrdinal(response);
                Integer hintedOrdinal = uniqueActorHintOrdinal(context, step);
                if (hintedOrdinal != null && !hintedOrdinal.equals(parsedOrdinal)) {
                    response = llmService.complete(buildActorAssignmentCorrectionPrompt(
                            context, step, response));
                    parsedOrdinal = parseActorOrdinal(response);
                }
                if (hintedOrdinal != null && !hintedOrdinal.equals(parsedOrdinal)) {
                    log.warn("LLM actor assignment contradicted unambiguous engine hint for {} {}",
                            scope, step.id());
                    continue;
                }
                int ordinal = parsedOrdinal == null ? -1 : parsedOrdinal;
                ActorCandidate actor = actors.get(ordinal);
                if (actor != null) {
                    result.put(step.id(), actor.name());
                }
            } catch (Exception e) {
                log.warn("LLM actor assignment failed for {} {}: {}", scope, step.id(), e.getMessage());
            }
        }
        return Map.copyOf(result);
    }

    String buildActorAssignmentCorrectionPrompt(GraphContext context, DiscoveredStep step,
                                                String rejectedResponse) {
        Integer hintedOrdinal = uniqueActorHintOrdinal(context, step);
        return "VALIDATION CORRECTION: The previous actor assignment was null, malformed, or "
                + "contradicted the unambiguous engine lexical hint. The fixed activity explicitly "
                + "names ballot actor ordinal " + hintedOrdinal + " as its performer. Return that "
                + "ordinal in the assignment object; do not copy null from the rejected response.\n"
                + "The previous response is diagnostic text, never source evidence:\n"
                + "<<<REJECTED_RESPONSE\n" + boundedActorDiagnostic(rejectedResponse)
                + "\nREJECTED_RESPONSE>>>\n\n"
                + buildActorAssignmentPrompt(context, step);
    }

    private Integer uniqueActorHintOrdinal(GraphContext context, DiscoveredStep step) {
        List<ActorCandidate> actors = actorCandidates(context);
        List<ActorCandidate> nameHints = actors.stream()
                .filter(actor -> beginsWithActor(step.name(), actor.name())).toList();
        if (nameHints.size() == 1) {
            return nameHints.get(0).ordinal();
        }
        if (nameHints.size() > 1) {
            return null;
        }
        List<ActorCandidate> evidenceHints = actors.stream()
                .filter(actor -> beginsWithActor(step.evidence(), actor.name())).toList();
        return evidenceHints.size() == 1 ? evidenceHints.get(0).ordinal() : null;
    }

    private boolean beginsWithActor(String text, String actorName) {
        if (text == null || actorName == null || actorName.isBlank()) {
            return false;
        }
        String value = text.strip().toLowerCase(Locale.ROOT)
                .replaceFirst("^(first|next|then|finally|afterward|afterwards|and)\\s*,?\\s+", "");
        String actor = actorName.strip().toLowerCase(Locale.ROOT);
        return value.equals(actor) || value.startsWith(actor + " ")
                || value.startsWith(actor + ",") || value.startsWith(actor + ":");
    }

    private String boundedActorDiagnostic(String response) {
        String value = response == null ? "<null>" : response.strip();
        return value.length() <= 1_000 ? value : value.substring(0, 1_000) + "\n[TRUNCATED]";
    }

    /** Parses the one-step actor ballot without exposing graph ids to the model. */
    Integer parseActorOrdinal(String response) {
        JsonNode root = responseObject(response);
        JsonNode assignment = root == null ? null : root.get("assignment");
        if (assignment == null || !assignment.isObject()) {
            return null;
        }
        int ordinal = assignment.path("actorOrdinal").asInt(-1);
        return ordinal > 0 ? ordinal : null;
    }

    List<DiscoveredStep> parseStepExtractionResponse(String response, GraphContext context) {
        JsonNode root = responseObject(response);
        JsonNode stepsNode = root == null ? null : root.get("steps");
        if (stepsNode == null || !stepsNode.isArray()) {
            return List.of();
        }
        Set<String> allowedNodeIds = allNodeIds(context);
        List<DiscoveredStep> steps = new ArrayList<>();
        for (JsonNode stepNode : stepsNode) {
            String name = textOrNull(stepNode, "name");
            String evidence = textOrNull(stepNode, "evidence");
            String description = textOrNull(stepNode, "description");
            String groundedEvidence = groundStepEvidence(context, evidence, name);
            if (name == null || groundedEvidence == null) {
                continue;
            }
            String sourceNodeId = textOrNull(stepNode, "sourceNodeId");
            if (sourceNodeId != null && !allowedNodeIds.contains(sourceNodeId)) {
                sourceNodeId = null;
            }
            steps.add(new DiscoveredStep("step-" + (steps.size() + 1), name,
                    description == null ? name : description,
                    normalizeStepType(textOrNull(stepNode, "stepType")), sourceNodeId,
                    groundedEvidence));
        }
        return List.copyOf(steps);
    }

    private String groundStepEvidence(GraphContext context, String proposedEvidence, String stepName) {
        if (proposedEvidence == null || stepName == null) {
            return null;
        }
        List<String> sources = processSourceTexts(context);
        for (String source : sources) {
            if (source.contains(proposedEvidence)) {
                return proposedEvidence;
            }
        }
        Set<String> required = significantTokens(stepName);
        if (required.isEmpty()) {
            return null;
        }
        for (String source : sources) {
            for (String sentence : sourceSentences(source)) {
                if (significantTokens(sentence).containsAll(required)) {
                    return sentence;
                }
            }
        }
        return null;
    }

    private List<String> processSourceTexts(GraphContext context) {
        List<String> texts = new ArrayList<>();
        for (GraphNode node : context.nodes()) {
            if (node.getNodeType() != NodeLevel.DOCUMENT && node.getNodeType() != NodeLevel.TABLE) {
                continue;
            }
            if (node.getDescription() != null && !node.getDescription().isBlank()) {
                texts.add(node.getDescription());
            }
            if (node.getContentPreview() != null && !node.getContentPreview().isBlank()) {
                texts.add(node.getContentPreview());
            }
        }
        for (GraphNode snippet : context.snippets()) {
            if (snippet.getDescription() != null && !snippet.getDescription().isBlank()) {
                texts.add(snippet.getDescription());
            }
            if (snippet.getContentPreview() != null && !snippet.getContentPreview().isBlank()) {
                texts.add(snippet.getContentPreview());
            }
        }
        return List.copyOf(texts);
    }

    private List<String> sourceSentences(String source) {
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ROOT);
        iterator.setText(source);
        List<String> sentences = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE;
             start = end, end = iterator.next()) {
            String sentence = source.substring(start, end).strip();
            if (!sentence.isBlank()) {
                sentences.add(sentence);
            }
        }
        return sentences;
    }

    private Set<String> significantTokens(String value) {
        if (value == null) {
            return Set.of();
        }
        Set<String> ignored = Set.of("the", "and", "for", "from", "into", "with", "that",
                "this", "then", "first", "next", "finally", "step");
        return Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(token -> token.length() >= 3 && !ignored.contains(token))
                .map(this::tokenStem)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String tokenStem(String token) {
        String value = token;
        if (value.length() > 5 && value.endsWith("ing")) {
            value = value.substring(0, value.length() - 3);
        } else if (value.length() > 4 && value.endsWith("ed")) {
            value = value.substring(0, value.length() - 2);
        } else if (value.length() > 4 && value.endsWith("es")) {
            value = value.substring(0, value.length() - 2);
        } else if (value.length() > 3 && value.endsWith("s")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.length() > 4 && value.endsWith("e")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    List<ProcessSuggestion> parseOrganizationResponse(String response, GraphContext context,
                                                       List<DiscoveredStep> steps) {
        return parseOrganizationResponse(response, context, steps, Map.of());
    }

    List<ProcessSuggestion> parseOrganizationResponse(String response, GraphContext context,
                                                       List<DiscoveredStep> steps,
                                                       Map<String, String> fixedAssignees) {
        JsonNode root = responseObject(response);
        JsonNode processesNode = root == null ? null : root.get("processes");
        if (processesNode == null || !processesNode.isArray()) {
            return List.of();
        }
        Map<String, DiscoveredStep> stepsById = steps.stream()
                .collect(Collectors.toMap(DiscoveredStep::id, step -> step,
                        (first, ignored) -> first, LinkedHashMap::new));
        Map<Integer, ActorCandidate> actorsByOrdinal = actorCandidates(context).stream()
                .collect(Collectors.toMap(ActorCandidate::ordinal, actor -> actor));
        Set<String> globallyUsed = new LinkedHashSet<>();
        List<ProcessSuggestion> suggestions = new ArrayList<>();
        for (JsonNode processNode : processesNode) {
            String name = textOrNull(processNode, "name");
            if (name == null) {
                continue;
            }
            Map<String, String> assigneeByStep = new LinkedHashMap<>();
            if (fixedAssignees != null) {
                assigneeByStep.putAll(fixedAssignees);
            }
            // Read the former combined contract only for provider/transcript compatibility.
            assigneeByStep.putAll(parseAssignments(
                    processNode.get("assignments"), stepsById, actorsByOrdinal));

            List<ProcessSuggestion.SuggestedPhase> phases = new ArrayList<>();
            List<String> sourceNodeIds = new ArrayList<>();
            List<String> evidence = new ArrayList<>();
            JsonNode orderedStepIds = processNode.get("orderedStepIds");
            if (orderedStepIds != null && orderedStepIds.isArray()) {
                List<ProcessSuggestion.SuggestedStep> orderedSteps = materializeSteps(
                        orderedStepIds, stepsById, globallyUsed, assigneeByStep,
                        sourceNodeIds, evidence);
                if (!orderedSteps.isEmpty()) {
                    String phaseName = textOrNull(processNode, "phaseName");
                    phaseName = phaseName == null ? name : phaseName;
                    phases.add(ProcessSuggestion.SuggestedPhase.builder()
                            .name(phaseName)
                            .description(phaseName)
                            .steps(orderedSteps)
                            .build());
                }
            } else {
                JsonNode phasesNode = processNode.get("phases");
                if (phasesNode != null && phasesNode.isArray()) {
                    for (JsonNode phaseNode : phasesNode) {
                        String phaseName = textOrNull(phaseNode, "name");
                        JsonNode stepIds = phaseNode.get("stepIds");
                        if (phaseName == null || stepIds == null || !stepIds.isArray()) {
                            continue;
                        }
                        List<ProcessSuggestion.SuggestedStep> phaseSteps = materializeSteps(
                                stepIds, stepsById, globallyUsed, assigneeByStep,
                                sourceNodeIds, evidence);
                        if (!phaseSteps.isEmpty()) {
                            String phaseDescription = textOrNull(phaseNode, "description");
                            phases.add(ProcessSuggestion.SuggestedPhase.builder()
                                    .name(phaseName)
                                    .description(phaseDescription == null ? phaseName : phaseDescription)
                                    .steps(phaseSteps)
                                    .build());
                        }
                    }
                }
            }
            if (phases.isEmpty()) {
                continue;
            }
            String description = textOrNull(processNode, "description");
            double confidence = processNode.has("confidence")
                    ? Math.max(0.0, Math.min(1.0, processNode.get("confidence").asDouble(0.5)))
                    : 0.5;
            suggestions.add(ProcessSuggestion.builder()
                    .name(name)
                    .description(description == null ? name : description)
                    .discoverySource("LLM_ANALYSIS")
                    .confidence(confidence)
                    .phases(phases)
                    .sourceGraphNodeIds(sourceNodeIds)
                    .evidence(evidence)
                    .childSuggestions(List.of())
                    .build());
        }
        return List.copyOf(suggestions);
    }

    private List<ProcessSuggestion.SuggestedStep> materializeSteps(
            JsonNode stepIds,
            Map<String, DiscoveredStep> stepsById,
            Set<String> globallyUsed,
            Map<String, String> assigneeByStep,
            List<String> sourceNodeIds,
            List<String> evidence) {
        List<ProcessSuggestion.SuggestedStep> result = new ArrayList<>();
        for (JsonNode stepIdNode : stepIds) {
            String stepId = normalizedStepId(stepIdNode, stepsById);
            DiscoveredStep step = stepsById.get(stepId);
            if (step == null || !globallyUsed.add(stepId)) {
                continue;
            }
            if (step.sourceNodeId() != null && !sourceNodeIds.contains(step.sourceNodeId())) {
                sourceNodeIds.add(step.sourceNodeId());
            }
            if (!evidence.contains(step.evidence())) {
                evidence.add(step.evidence());
            }
            result.add(ProcessSuggestion.SuggestedStep.builder()
                    .name(step.name())
                    .stepType(step.stepType())
                    .description(step.description())
                    .suggestedAssignee(assigneeByStep.get(step.id()))
                    .graphNodeIds(step.sourceNodeId() == null
                            ? List.of() : List.of(step.sourceNodeId()))
                    .build());
        }
        return result;
    }

    /**
     * The engine owns step ids, so a model that returns the unambiguous ballot ordinal can be
     * normalized without trusting a fabricated id. Arbitrary strings remain rejected.
     */
    private String normalizedStepId(JsonNode node, Map<String, DiscoveredStep> stepsById) {
        if (node == null) {
            return null;
        }
        String raw = node.isIntegralNumber() ? Integer.toString(node.asInt())
                : node.isTextual() ? node.asText().strip() : null;
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (stepsById.containsKey(raw)) {
            return raw;
        }
        if (raw.chars().allMatch(Character::isDigit)) {
            String byOrdinal = "step-" + raw;
            return stepsById.containsKey(byOrdinal) ? byOrdinal : null;
        }
        return null;
    }

    private Map<String, String> parseAssignments(JsonNode assignments,
                                                  Map<String, DiscoveredStep> stepsById,
                                                  Map<Integer, ActorCandidate> actorsByOrdinal) {
        Map<String, String> result = new HashMap<>();
        if (assignments == null || !assignments.isArray()) {
            return result;
        }
        for (JsonNode assignment : assignments) {
            String stepId = textOrNull(assignment, "stepId");
            int ordinal = assignment.has("actorOrdinal")
                    ? assignment.get("actorOrdinal").asInt(-1) : -1;
            ActorCandidate actor = actorsByOrdinal.get(ordinal);
            if (stepsById.containsKey(stepId) && actor != null) {
                result.put(stepId, actor.name());
            }
        }
        return result;
    }

    private List<ActorCandidate> actorCandidates(GraphContext context) {
        List<ActorCandidate> actors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (GraphNode node : context.nodes()) {
            if (node.getNodeType() == NodeLevel.ENTITY && node.getTitle() != null
                    && !node.getTitle().isBlank() && seen.add(node.getTitle())) {
                actors.add(new ActorCandidate(actors.size() + 1, node.getTitle(), node.getNodeId()));
            }
        }
        return List.copyOf(actors);
    }

    private Set<String> allNodeIds(GraphContext context) {
        Set<String> ids = new HashSet<>();
        context.nodes().forEach(node -> ids.add(node.getNodeId()));
        context.snippets().forEach(node -> ids.add(node.getNodeId()));
        ids.remove(null);
        return ids;
    }

    private String graphEvidenceBlock(GraphContext context) {
        String prompt = buildPrompt(context);
        int start = prompt.indexOf("=== KNOWLEDGE GRAPH NODES ===");
        if (start < 0) {
            start = 0;
        }
        int end = prompt.indexOf("\n=== OUTPUT FORMAT ===", start);
        if (end < 0) {
            end = prompt.indexOf("\nReturn exactly one raw JSON object", start);
        }
        return prompt.substring(start, end < 0 ? prompt.length() : end).strip();
    }

    private JsonNode responseObject(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        String cleaned = response.strip();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.strip();
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(cleaned.substring(start, end + 1));
            return root != null && root.isObject() ? root : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    GraphContext buildGraphContext(List<String> graphNodeIds) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphNode> snippets = new ArrayList<>();
        Set<String> seenNodeIds = new HashSet<>();

        if (graphNodeIds != null && !graphNodeIds.isEmpty()) {
            for (String nodeId : graphNodeIds) {
                knowledgeGraphService.getNode(nodeId).ifPresent(node -> {
                    if (seenNodeIds.add(node.getNodeId())) nodes.add(node);
                });
                // Include immediate neighbors for richer context
                List<GraphNode> connected = knowledgeGraphService.getConnectedNodes(nodeId, 1);
                for (GraphNode n : connected) {
                    if (seenNodeIds.add(n.getNodeId())) nodes.add(n);
                }
            }
        } else {
            // Fetch document, entity, and table nodes
            for (NodeLevel level : List.of(NodeLevel.DOCUMENT, NodeLevel.ENTITY, NodeLevel.TABLE)) {
                List<GraphNode> levelNodes = knowledgeGraphService.searchNodes("", level, MAX_NODES_FOR_CONTEXT);
                for (GraphNode n : levelNodes) {
                    if (seenNodeIds.add(n.getNodeId())) nodes.add(n);
                    if (nodes.size() >= MAX_NODES_FOR_CONTEXT) break;
                }
                if (nodes.size() >= MAX_NODES_FOR_CONTEXT) break;
            }
        }

        // For every DOCUMENT node, fetch its SNIPPET children — these contain
        // the actual chunk content where processes are described
        for (GraphNode node : new ArrayList<>(nodes)) {
            if (node.getNodeType() == NodeLevel.DOCUMENT) {
                List<GraphNode> children = knowledgeGraphService.getChildren(node.getNodeId());
                int count = 0;
                for (GraphNode child : children) {
                    if (child.getNodeType() == NodeLevel.SNIPPET && seenNodeIds.add(child.getNodeId())) {
                        snippets.add(child);
                        if (++count >= MAX_SNIPPETS_PER_DOCUMENT) break;
                    }
                }
            }
        }

        // Gather edges for the main nodes (not snippets — they only add bulk)
        List<GraphEdge> edges = new ArrayList<>();
        Set<String> seenEdgeKeys = new HashSet<>();
        for (GraphNode node : nodes) {
            List<GraphEdge> nodeEdges = knowledgeGraphService.getEdgesForNode(node.getNodeId());
            for (GraphEdge edge : nodeEdges) {
                String edgeKey = edge.getSourceNode().getNodeId() + "->" + edge.getTargetNode().getNodeId()
                        + ":" + (edge.getLabel() != null ? edge.getLabel() : edge.getDescription());
                if (seenEdgeKeys.add(edgeKey)) {
                    edges.add(edge);
                }
            }
        }

        return new GraphContext(nodes, snippets, edges);
    }

    /**
     * Builds graph context scoped to a specific fact sheet using the repository
     * for efficient fact-sheet-level queries.
     */
    GraphContext buildGraphContextForFactSheet(Long factSheetId) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphNode> snippets = new ArrayList<>();
        Set<String> seenNodeIds = new HashSet<>();

        // Use KnowledgeGraphService for fact-sheet-scoped queries
        for (NodeLevel level : List.of(NodeLevel.DOCUMENT, NodeLevel.ENTITY, NodeLevel.TABLE)) {
            List<GraphNode> levelNodes = knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, level);
            for (GraphNode n : levelNodes) {
                if (seenNodeIds.add(n.getNodeId())) nodes.add(n);
                if (nodes.size() >= MAX_NODES_FOR_CONTEXT) break;
            }
            if (nodes.size() >= MAX_NODES_FOR_CONTEXT) break;
        }

        // Fetch SNIPPET children for DOCUMENT nodes
        for (GraphNode node : new ArrayList<>(nodes)) {
            if (node.getNodeType() == NodeLevel.DOCUMENT) {
                List<GraphNode> children = knowledgeGraphService.getChildren(node.getNodeId());
                int count = 0;
                for (GraphNode child : children) {
                    if (child.getNodeType() == NodeLevel.SNIPPET && seenNodeIds.add(child.getNodeId())) {
                        snippets.add(child);
                        if (++count >= MAX_SNIPPETS_PER_DOCUMENT) break;
                    }
                }
            }
        }

        // Gather edges for the main nodes
        List<GraphEdge> edges = new ArrayList<>();
        Set<String> seenEdgeKeys = new HashSet<>();
        for (GraphNode node : nodes) {
            List<GraphEdge> nodeEdges = knowledgeGraphService.getEdgesForNode(node.getNodeId());
            for (GraphEdge edge : nodeEdges) {
                String edgeKey = edge.getSourceNode().getNodeId() + "->" + edge.getTargetNode().getNodeId()
                        + ":" + (edge.getLabel() != null ? edge.getLabel() : edge.getDescription());
                if (seenEdgeKeys.add(edgeKey)) {
                    edges.add(edge);
                }
            }
        }

        return new GraphContext(nodes, snippets, edges);
    }

    // ── Prompt construction ─────────────────────────────────────────────────

    String buildPrompt(GraphContext ctx) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are a business process analyst. Analyze the following knowledge graph data ");
        sb.append("and identify business processes, workflows, or procedures that are described or implied.\n\n");
        sb.append("A business process is a repeatable sequence of steps performed by people or systems ");
        sb.append("to achieve a goal. Look for:\n");
        sb.append("- Procedures described in documents (e.g., \"Step 1: Fill out form, Step 2: Get approval\")\n");
        sb.append("- Workflows implied by email communications (e.g., request → review → approve → notify)\n");
        sb.append("- Data processing pipelines (e.g., input data → compute → validate → report)\n");
        sb.append("- Multi-document processes where one document describes how to use another\n");
        sb.append("- Approval chains, onboarding procedures, reporting cycles\n");
        sb.append("- Processes that contain sub-processes (hierarchical workflows)\n\n");
        sb.append("The data includes document-level nodes (metadata) AND their chunk content (actual text ");
        sb.append("from the indexed documents). Pay close attention to the DOCUMENT CHUNKS section — ");
        sb.append("that is where process descriptions, step-by-step instructions, and workflow details ");
        sb.append("are most likely to appear.\n\n");

        // Serialize nodes
        sb.append("=== KNOWLEDGE GRAPH NODES ===\n");
        for (GraphNode node : ctx.nodes) {
            sb.append("Node[").append(node.getNodeId()).append("]: ");
            sb.append("type=").append(node.getNodeType());
            if (node.getTitle() != null) sb.append(", title=\"").append(node.getTitle()).append("\"");
            if (node.getDescription() != null) {
                String desc = StringUtils.truncate(node.getDescription(), 200);
                sb.append(", description=\"").append(desc).append("\"");
            }
            if (node.getContentPreview() != null) {
                String preview = StringUtils.truncate(node.getContentPreview(), 300);
                sb.append(", content=\"").append(preview).append("\"");
            }
            if (node.getMetadataJson() != null) {
                String entityType = extractEntityType(node.getMetadataJson());
                if (entityType != null) sb.append(", entity_type=").append(entityType);
            }
            sb.append("\n");
        }

        // Serialize document chunk content — this is where process descriptions live
        if (!ctx.snippets.isEmpty()) {
            sb.append("\n=== DOCUMENT CHUNKS (actual content from indexed documents) ===\n");

            // Group snippets by parent document
            Map<String, List<GraphNode>> snippetsByParent = new LinkedHashMap<>();
            for (GraphNode snippet : ctx.snippets) {
                String parentId = snippet.getParentId() != null ? snippet.getParentId() : "unknown";
                snippetsByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(snippet);
            }

            for (Map.Entry<String, List<GraphNode>> entry : snippetsByParent.entrySet()) {
                // Find the parent document title
                String parentTitle = entry.getKey();
                for (GraphNode node : ctx.nodes) {
                    if (node.getNodeId().equals(entry.getKey()) && node.getTitle() != null) {
                        parentTitle = node.getTitle();
                        break;
                    }
                }
                sb.append("\n--- Document: ").append(parentTitle)
                        .append(" [").append(entry.getKey()).append("] ---\n");

                for (GraphNode snippet : entry.getValue()) {
                    if (snippet.getContentPreview() != null && !snippet.getContentPreview().isBlank()) {
                        sb.append("  Chunk ").append(snippet.getTitle() != null ? snippet.getTitle() : "")
                                .append(" [").append(snippet.getNodeId()).append("]")
                                .append(": ");
                        sb.append(StringUtils.truncate(snippet.getContentPreview(), 400));
                        sb.append("\n");
                    }
                }
            }
        }

        // Serialize edges. Endpoints resolve against ctx.nodes: store-loaded edges embed hollow
        // id-only nodes, and nodeRef on those would feed the LLM raw ids instead of titles.
        sb.append("\n=== KNOWLEDGE GRAPH EDGES ===\n");
        Map<String, GraphNode> promptNodesById = new HashMap<>();
        for (GraphNode n : ctx.nodes) {
            if (n.getNodeId() != null) {
                promptNodesById.put(n.getNodeId(), n);
            }
        }
        for (GraphEdge edge : ctx.edges) {
            GraphNode src = promptNodesById.getOrDefault(edge.getSourceNodeId(), edge.getSourceNode());
            GraphNode tgt = promptNodesById.getOrDefault(edge.getTargetNodeId(), edge.getTargetNode());
            sb.append("Edge: ");
            sb.append(nodeRef(src));
            sb.append(" --[").append(edge.getLabel() != null ? edge.getLabel() : "RELATED_TO").append("]--> ");
            sb.append(nodeRef(tgt));
            if (edge.getDescription() != null) {
                sb.append(" (").append(StringUtils.truncate(edge.getDescription(), 100)).append(")");
            }
            sb.append("\n");
        }

        // Output schema
        sb.append("\n=== OUTPUT FORMAT ===\n");
        sb.append(getProcessDiscoveryPromptInstructions());

        // Truncate the overall prompt if it exceeds the limit
        if (sb.length() > MAX_CONTENT_CHARS * 3) {
            sb.setLength(MAX_CONTENT_CHARS * 3);
            sb.append("\n... (truncated)\n\n");
            sb.append(getProcessDiscoveryPromptInstructions());
        }

        return sb.toString();
    }

    private String nodeRef(GraphNode node) {
        if (node == null) return "?";
        String title = node.getTitle() != null ? node.getTitle() : node.getNodeId();
        return "\"" + StringUtils.truncate(title, 60) + "\"[" + node.getNodeId() + "]";
    }

    private String extractEntityType(String metadataJson) {
        if (metadataJson == null) return null;
        int idx = metadataJson.indexOf("\"entity_type\":\"");
        if (idx < 0) return null;
        int start = idx + "\"entity_type\":\"".length();
        int end = metadataJson.indexOf("\"", start);
        if (end < 0) return null;
        return metadataJson.substring(start, end);
    }

    public static String getProcessDiscoveryPromptInstructions() {
        return """
                Return exactly one raw JSON object with one top-level key: "processes".
                The value of "processes" MUST be an array. If the supplied graph supports no process,
                return exactly {"processes":[]}.

                PROCESS OBJECT CONTRACT:
                - Required keys: "name" (string), "description" (string), "confidence" (number in
                  [0.0, 1.0]), "discoverySource" (the literal string "LLM_ANALYSIS"), and "phases" (array).
                - Optional keys: "evidence" (array of exact supporting source snippets),
                  "sourceNodeIds" (array of supplied graph node IDs), and "childProcesses" (array of
                  process objects using this same contract, without further childProcesses).

                PHASE OBJECT CONTRACT:
                - Required keys: "name" (string grounded in the supplied graph) and "steps" (array).
                - Optional key: "description" (string grounded in the supplied graph).

                STEP OBJECT CONTRACT:
                - Required keys: "name" (string), "stepType" (string), and "description" (string).
                - "stepType" must be exactly one of AUTO, HUMAN, APPROVE, TOOL_CALL, EXCEL_COMPUTE,
                  SCRIPT, or HTTP_CALL.
                - Optional keys: "actor" (a supplied person/role name) and "nodeId" (a supplied graph node ID).

                GROUNDING RULES:
                - Derive every process, phase, step, actor, evidence string, and child process only from
                  the KNOWLEDGE GRAPH sections above. Never copy a value from this output contract.
                - Preserve source order when it establishes step order. Do not add generic workflow steps.
                - Copy sourceNodeIds and nodeId values only from IDs printed in square brackets above.
                - Omit unsupported optional fields rather than inventing them.
                - Output only valid JSON: no markdown fences, commentary, ellipses, or second root object.
                """;
    }

    // ── Response parsing ────────────────────────────────────────────────────

    public List<ProcessSuggestion> parseResponse(String response) {
        if (response == null || response.isBlank()) return List.of();

        // Strip markdown fences if present
        String cleaned = response.strip();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) cleaned = cleaned.substring(firstNewline + 1);
            if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
            cleaned = cleaned.strip();
        }

        // Find the outermost JSON object
        int braceStart = cleaned.indexOf('{');
        int braceEnd = cleaned.lastIndexOf('}');
        if (braceStart < 0 || braceEnd <= braceStart) {
            log.warn("LLM process discovery response contains no valid JSON object");
            return List.of();
        }
        cleaned = cleaned.substring(braceStart, braceEnd + 1);

        try {
            JsonNode root = MAPPER.readTree(cleaned);
            JsonNode processesNode = root.get("processes");
            if (processesNode == null || !processesNode.isArray()) {
                log.warn("LLM response missing 'processes' array");
                return List.of();
            }

            List<ProcessSuggestion> suggestions = new ArrayList<>();
            for (JsonNode processNode : processesNode) {
                ProcessSuggestion suggestion = parseProcessNode(processNode);
                if (suggestion != null) {
                    suggestions.add(suggestion);
                }
            }
            return suggestions;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse LLM process discovery response: {}", e.getMessage());
            return List.of();
        }
    }

    private ProcessSuggestion parseProcessNode(JsonNode processNode) {
        String name = textOrNull(processNode, "name");
        if (name == null) return null;

        String description = textOrNull(processNode, "description");
        double confidence = processNode.has("confidence") ? processNode.get("confidence").asDouble(0.5) : 0.5;
        String discoverySource = textOrNull(processNode, "discoverySource");
        if (discoverySource == null) discoverySource = "LLM_ANALYSIS";

        // Parse evidence
        List<String> evidence = new ArrayList<>();
        if (processNode.has("evidence") && processNode.get("evidence").isArray()) {
            for (JsonNode e : processNode.get("evidence")) {
                if (e.isTextual()) evidence.add(e.asText());
            }
        }

        // Parse source node IDs
        List<String> sourceNodeIds = new ArrayList<>();
        if (processNode.has("sourceNodeIds") && processNode.get("sourceNodeIds").isArray()) {
            for (JsonNode n : processNode.get("sourceNodeIds")) {
                if (n.isTextual()) sourceNodeIds.add(n.asText());
            }
        }

        // Parse phases
        List<ProcessSuggestion.SuggestedPhase> phases = new ArrayList<>();
        if (processNode.has("phases") && processNode.get("phases").isArray()) {
            for (JsonNode phaseNode : processNode.get("phases")) {
                ProcessSuggestion.SuggestedPhase phase = parsePhaseNode(phaseNode);
                if (phase != null) phases.add(phase);
            }
        }
        // If no phases were parsed, create a default one
        if (phases.isEmpty()) {
            phases.add(ProcessSuggestion.SuggestedPhase.builder()
                    .name("Main")
                    .description(description != null ? description : name)
                    .steps(List.of(ProcessSuggestion.SuggestedStep.builder()
                            .name(name)
                            .stepType("HUMAN")
                            .description(description != null ? description : name)
                            .build()))
                    .build());
        }

        // Parse child processes
        List<ProcessSuggestion> childSuggestions = new ArrayList<>();
        if (processNode.has("childProcesses") && processNode.get("childProcesses").isArray()) {
            for (JsonNode childNode : processNode.get("childProcesses")) {
                ProcessSuggestion child = parseProcessNode(childNode);
                if (child != null) {
                    child.setParentSuggestionId(name);
                    childSuggestions.add(child);
                }
            }
        }

        return ProcessSuggestion.builder()
                .name(name)
                .description(description != null ? description : name)
                .discoverySource(discoverySource)
                .confidence(confidence)
                .phases(phases)
                .sourceGraphNodeIds(sourceNodeIds)
                .evidence(evidence)
                .childSuggestions(childSuggestions)
                .build();
    }

    private ProcessSuggestion.SuggestedPhase parsePhaseNode(JsonNode phaseNode) {
        String name = textOrNull(phaseNode, "name");
        if (name == null) return null;

        String description = textOrNull(phaseNode, "description");

        List<ProcessSuggestion.SuggestedStep> steps = new ArrayList<>();
        if (phaseNode.has("steps") && phaseNode.get("steps").isArray()) {
            for (JsonNode stepNode : phaseNode.get("steps")) {
                ProcessSuggestion.SuggestedStep step = parseStepNode(stepNode);
                if (step != null) steps.add(step);
            }
        }

        if (steps.isEmpty()) return null;

        return ProcessSuggestion.SuggestedPhase.builder()
                .name(name)
                .description(description != null ? description : name)
                .steps(steps)
                .build();
    }

    private ProcessSuggestion.SuggestedStep parseStepNode(JsonNode stepNode) {
        String name = textOrNull(stepNode, "name");
        if (name == null) return null;

        String stepType = textOrNull(stepNode, "stepType");
        if (stepType == null) stepType = "HUMAN";
        // Normalize to valid step types
        stepType = normalizeStepType(stepType);

        String description = textOrNull(stepNode, "description");
        String actor = textOrNull(stepNode, "actor");
        String nodeId = textOrNull(stepNode, "nodeId");
        String toolName = textOrNull(stepNode, "toolName");

        List<String> graphNodeIds = new ArrayList<>();
        if (nodeId != null) graphNodeIds.add(nodeId);

        return ProcessSuggestion.SuggestedStep.builder()
                .name(name)
                .stepType(stepType)
                .description(description != null ? description : name)
                .suggestedAssignee(actor)
                .graphNodeIds(graphNodeIds)
                .toolName(toolName)
                .build();
    }

    private String normalizeStepType(String stepType) {
        if (stepType == null) return "HUMAN";
        return switch (stepType.toUpperCase()) {
            case "AUTO", "AUTOMATIC", "AUTOMATED", "SYSTEM" -> "AUTO";
            case "HUMAN", "MANUAL", "USER" -> "HUMAN";
            case "APPROVE", "APPROVAL", "REVIEW" -> "APPROVE";
            case "TOOL_CALL", "TOOL" -> "TOOL_CALL";
            case "EXCEL_COMPUTE", "COMPUTE", "CALCULATE", "EXCEL" -> "EXCEL_COMPUTE";
            case "SCRIPT", "CODE", "TRANSFORM" -> "SCRIPT";
            case "HTTP_CALL", "API", "HTTP", "REST" -> "HTTP_CALL";
            default -> "HUMAN";
        };
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field)) return null;
        JsonNode val = node.get(field);
        if (val.isNull() || !val.isTextual()) return null;
        String text = val.asText();
        return text.isBlank() ? null : text;
    }
}
