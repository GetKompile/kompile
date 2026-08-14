/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.crawl.graph.passes;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.crawl.graph.GraphExtractionConfig.DecomposedPromptTier;
import ai.kompile.core.crawl.graph.GraphExtractionConfig.ExtractionTarget;
import ai.kompile.core.graphrag.GraphConstructor.ConceptHint;
import ai.kompile.core.graphrag.GraphConstructor.ExtractionTaskContext;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.passes.DecomposedExtractionPipeline.LlmCaller;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One model loop for graph extraction.
 *
 * <p>Both execution modes use the same backend tools and staged validation. Every model call is
 * a fresh task request: durable crawl transcripts remain audit data and are never replayed as model
 * memory. Only explicit bounded task state (a tool observation, rejected proposal, or validator
 * result) is rendered into the next fresh prompt. The loop ends only when
 * {@code submit_graph_delta} validates a staged delta.</p>
 */
public final class ToolDrivenExtractionExecutor {

    public static final String PASS_ID = "tool_agent";
    private static final int MAX_TOOL_ROUNDS = 8;
    private static final String EXPLICIT_TOOL_STATE_MARKER =
            "CURRENT EXPLICIT TOOL STATE (fresh request; not conversation history):";
    private static final String COMPACTED_TOOL_STATE_MARKER =
            "CURRENT EXPLICIT TOOL STATE (compacted fresh request; not conversation history):";
    private static final String REJECTED_SUBMISSION_GUIDANCE =
            "PREVIOUS SUBMISSION HAD REJECTED ITEMS. correction.alreadyRetained is accepted, not "
                    + "evidence. Repair or omit only rejected or unsupported items using SOURCE; retained "
                    + "entity ids may be used by corrected relations. Graph, schema, hints, and tool state "
                    + "are context, never evidence.";
    private static final String REJECTED_CANDIDATE_DRAFT_LABEL =
            "Rejected candidate draft (UNACCEPTED; NOT EVIDENCE; SOURCE recheck required):";
    private static final String VALIDATOR_CLEAN_REPAIR_SEED_LABEL =
            "Already retained validator-clean subset (ACCEPTED; NOT EVIDENCE):";
    private static final String COMPLETE_REPLACEMENT_GUIDANCE =
            "The retained subset is already accumulated, not a replacement draft. Submit only "
                    + "corrected source-supported additions (with both arrays present); a relation may "
                    + "refer to a retained entity id. Repair or omit rejected items using SOURCE.";
    static final String TOOL_USE_REQUIREMENT = """
            FINAL: Invoke exactly one declared function through the native tool interface now.
            Return no prose or hand-written function syntax; the chat template owns the wire format.
            """;
    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper();

    public record Result(
            String json,
            ExtractionResult extraction,
            int rounds,
            int toolCalls,
            List<String> toolsUsed,
            List<String> notes) {

        public Result {
            toolsUsed = toolsUsed == null ? List.of() : List.copyOf(toolsUsed);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        public boolean usable() {
            return extraction != null && json != null && !json.isBlank();
        }

        public String summary() {
            return "tool_agent rounds=" + rounds + " calls=" + toolCalls
                    + " tools=" + toolsUsed
                    + (extraction == null ? " no accepted delta"
                    : " entities=" + extraction.entities().size()
                    + " relations=" + extraction.relations().size());
        }
    }

    @FunctionalInterface
    public interface StructuredLlmCaller {
        StructuredResponse call(String passId, StructuredRequest request);
    }

    public record StructuredRequest(
            List<ChatMessage> messages,
            List<ExtractionToolBackend.ToolDefinition> tools) {
        public StructuredRequest {
            messages = messages == null ? List.of() : List.copyOf(messages);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    public record StructuredResponse(
            String rawText,
            String content,
            List<ToolRequest> toolCalls,
            List<String> parseErrors) {
        public StructuredResponse {
            rawText = rawText == null ? "" : rawText;
            content = content == null ? "" : content;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            parseErrors = parseErrors == null ? List.of() : List.copyOf(parseErrors);
        }
    }

    public record ToolRequest(String id, String name, Map<String, Object> arguments) {
        public ToolRequest {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            arguments = arguments == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        }
    }

    /**
     * Exact post-parser assessment of one model-authored call. The request and its arguments are
     * retained verbatim so metrics and retry feedback describe what the model actually emitted.
     */
    public record StructuredCallAssessment(
            ToolRequest request,
            boolean declared,
            boolean schemaValid,
            List<String> errors) {

        public StructuredCallAssessment {
            request = request == null ? new ToolRequest("", "", Map.of()) : request;
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public boolean executable() {
            return declared && schemaValid;
        }
    }

    /**
     * Production protocol boundary between native parsing and tool execution.
     *
     * <p>Parsing, declared-name matching, and schema validity are intentionally separate. A parser
     * may recover a call while its name or arguments remain invalid; that call must be observable
     * but must never reach the backend.</p>
     */
    public record StructuredResponseAssessment(
            boolean parserClean,
            int parsedCalls,
            int declaredCalls,
            int schemaValidCalls,
            List<StructuredCallAssessment> calls,
            List<String> parseErrors) {

        public StructuredResponseAssessment {
            calls = calls == null ? List.of() : List.copyOf(calls);
            parseErrors = parseErrors == null ? List.of() : List.copyOf(parseErrors);
        }

        public boolean protocolValid() {
            return parserClean
                    && parsedCalls > 0
                    && declaredCalls == parsedCalls
                    && schemaValidCalls == parsedCalls;
        }
    }

    /**
     * Assess a structured response against exactly the function definitions visible in its request.
     * No fields are renamed, omitted, coerced, or defaulted.
     */
    public static StructuredResponseAssessment assessStructuredResponse(
            StructuredRequest request,
            StructuredResponse response) {
        List<ExtractionToolBackend.ToolDefinition> tools =
                request == null ? List.of() : request.tools();
        StructuredResponse actual = response == null
                ? new StructuredResponse("", "", List.of(), List.of())
                : response;
        List<StructuredCallAssessment> assessments = new ArrayList<>();
        int declaredCalls = 0;
        int schemaValidCalls = 0;
        for (ToolRequest call : actual.toolCalls()) {
            ToolRequest exactCall = call == null
                    ? new ToolRequest("", "", Map.of())
                    : call;
            ExtractionToolBackend.ToolDefinition definition = tools.stream()
                    .filter(tool -> tool.name().equals(exactCall.name()))
                    .findFirst()
                    .orElse(null);
            if (definition == null) {
                assessments.add(new StructuredCallAssessment(
                        exactCall,
                        false,
                        false,
                        List.of("undeclared function '" + exactCall.name() + "'")));
                continue;
            }
            declaredCalls++;
            ToolArgumentSchemaValidator.ValidationResult validation =
                    ToolArgumentSchemaValidator.validate(
                            definition.parameters(), exactCall.arguments());
            if (validation.valid()) {
                schemaValidCalls++;
            }
            assessments.add(new StructuredCallAssessment(
                    exactCall, true, validation.valid(), validation.errors()));
        }
        return new StructuredResponseAssessment(
                actual.parseErrors().isEmpty(),
                actual.toolCalls().size(),
                declaredCalls,
                schemaValidCalls,
                assessments,
                actual.parseErrors());
    }

    public record ChatMessage(
            String role,
            String content,
            List<ToolRequest> toolCalls,
            String toolCallId,
            String name) {
        public ChatMessage {
            role = role == null ? "" : role;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public static ChatMessage text(String role, String content) {
            return new ChatMessage(role, content, List.of(), null, null);
        }

        public static ChatMessage assistant(String content, List<ToolRequest> calls) {
            return new ChatMessage("assistant", content, calls, null, null);
        }

        public static ChatMessage tool(String id, String name, String result) {
            return new ChatMessage("tool", result, List.of(), id, name);
        }
    }

    private record ToolState(
            String modelCall,
            String toolResult,
            String protocolFeedback) {

        private ToolState {
            modelCall = modelCall == null ? "" : modelCall;
            toolResult = toolResult == null ? "" : toolResult;
            protocolFeedback = protocolFeedback == null ? "" : protocolFeedback;
        }

        ToolState withProtocolFeedback(String feedback) {
            return new ToolState(modelCall, toolResult, feedback);
        }
    }

    private record StructuredToolObservation(ToolRequest request, String result) {
    }

    private record StructuredRetryState(
            List<StructuredToolObservation> observations,
            String protocolFeedback,
            String repairSeed,
            boolean sourceRecheckRequired) {

        private StructuredRetryState {
            observations = observations == null ? List.of() : List.copyOf(observations);
            protocolFeedback = protocolFeedback == null ? "" : protocolFeedback;
            repairSeed = repairSeed == null ? "" : repairSeed;
        }

        static StructuredRetryState protocol(String feedback) {
            return new StructuredRetryState(List.of(), feedback, "", false);
        }

        static StructuredRetryState tools(
                List<StructuredToolObservation> observations,
                String previousRepairSeed,
                boolean previousSourceRecheckRequired) {
            String nextRepairSeed = observations == null ? "" : observations.stream()
                    .map(StructuredToolObservation::result)
                    .map(ToolDrivenExtractionExecutor::validatorCleanRepairSeed)
                    .filter(seed -> !seed.isBlank())
                    .reduce((first, second) -> second)
                    .orElse(previousRepairSeed == null ? "" : previousRepairSeed);
            boolean sourceRecheckRequired = previousSourceRecheckRequired
                    || (observations != null && observations.stream()
                    .anyMatch(ToolDrivenExtractionExecutor::requiresRejectedCandidateDraft));
            return new StructuredRetryState(
                    observations, "", nextRepairSeed, sourceRecheckRequired);
        }

        StructuredRetryState withProtocolFeedback(String feedback) {
            return new StructuredRetryState(
                    observations, feedback, repairSeed, sourceRecheckRequired);
        }
    }

    private record RenderedToolState(String content, boolean compacted) {
    }

    private record ToolCall(String name, JsonNode arguments) {
    }

    private record StructuredContract(
            DecomposedPromptTier tier,
            List<ExtractionToolBackend.ToolDefinition> tools,
            String system,
            String user,
            int fixedChars) {
    }

    public Result extract(String chunkText,
                          ExtractionTaskContext taskContext,
                          ExtractionToolBackend backend,
                          LlmCaller caller,
                          DecomposedExtractionExecutor.PromptProfile profile) {
        return extract(chunkText, taskContext, backend, caller, profile, null);
    }

    public Result extract(String chunkText,
                          ExtractionTaskContext taskContext,
                          ExtractionToolBackend backend,
                          LlmCaller caller,
                          DecomposedExtractionExecutor.PromptProfile profile,
                          String additionalInstructions) {
        if (chunkText == null || chunkText.isBlank()) {
            return new Result(null, null, 0, 0, List.of(), List.of("source shard is empty"));
        }
        if (backend == null) {
            return new Result(null, null, 0, 0, List.of(), List.of("tool backend is unavailable"));
        }
        if (caller == null) {
            return new Result(null, null, 0, 0, List.of(), List.of("LLM caller is unavailable"));
        }

        int maxPromptChars = profile == null ? 14_336 : profile.maxPromptChars();
        String base = basePrompt(chunkText, taskContext,
                backend.catalogJson(profile == null ? null : profile.tier()), profile,
                backend.extractionTarget(), additionalInstructions);
        if (base.length() >= maxPromptChars) {
            return new Result(null, null, 0, 0, List.of(),
                    List.of("source shard and tool contract exceed the executable prompt budget: "
                            + base.length() + " > " + maxPromptChars + " characters"));
        }

        ToolState toolState = null;
        List<String> toolsUsed = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int calls = 0;
        int rounds = 0;

        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            rounds = round;
            // Ontology updates are crawl-scoped. Rebuild the catalog every turn so a model that
            // extends it sees the new revision and type vocabulary before its next submission.
            base = basePrompt(chunkText, taskContext,
                    backend.catalogJson(profile == null ? null : profile.tier()), profile,
                    backend.extractionTarget(), additionalInstructions);
            if (base.length() >= maxPromptChars) {
                notes.add("updated ontology and tool contract exceed the executable prompt budget: "
                        + base.length() + " > " + maxPromptChars + " characters");
                break;
            }
            String prompt = renderPrompt(base, toolState, maxPromptChars);
            final String response;
            try {
                response = caller.call(PASS_ID, prompt);
            } catch (RuntimeException e) {
                notes.add("model call failed at round " + round + ": " + message(e));
                break;
            }

            Optional<ToolCall> parsed;
            try {
                parsed = parseToolCall(response);
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "Malformed tool-call envelope at round " + round + ": " + message(e),
                        e);
            }
            if (parsed.isEmpty()) {
                String feedback = invalidToolCallFeedback(backend.extractionTarget());
                toolState = toolState == null
                        ? new ToolState("", "", feedback)
                        : toolState.withProtocolFeedback(feedback);
                notes.add("round " + round
                        + " did not contain a valid tool request; malformed text was not re-injected");
                continue;
            }

            ToolCall toolCall = parsed.get();
            calls++;
            toolsUsed.add(toolCall.name());
            ExtractionToolBackend.ToolExecution execution;
            try {
                execution = backend.execute(toolCall.name(), toolCall.arguments(), chunkText);
            } catch (RuntimeException e) {
                execution = ExtractionToolBackend.ToolExecution.continuing(
                        errorJson("tool_execution_failed", message(e)));
                notes.add(toolCall.name() + " failed at round " + round + ": " + message(e));
            }
            toolState = new ToolState(response, execution.json(), "");

            Optional<ExtractionResult> accepted = backend.acceptedResult();
            if (execution.terminal() && accepted.isPresent()) {
                try {
                    return new Result(
                            GraphExtractionValidator.toJson(accepted.get()),
                            accepted.get(),
                            rounds,
                            calls,
                            toolsUsed,
                            notes);
                } catch (Exception e) {
                    notes.add("accepted delta could not be serialized: " + message(e));
                    break;
                }
            }
        }

        return retainedResult(backend, rounds, calls, toolsUsed, notes,
                "retry rounds ended after retaining validator-clean facts");
    }

    /**
     * Run the same staged extraction loop through a tokenizer/model-owned native tool protocol.
     * The caller is responsible only for adapting the portable request and response records to its
     * chat runtime; tool execution and graph admission remain owned by this executor and backend.
     */
    public Result extractStructured(String chunkText,
                                    ExtractionTaskContext taskContext,
                                    ExtractionToolBackend backend,
                                    StructuredLlmCaller caller,
                                    DecomposedExtractionExecutor.PromptProfile profile) {
        return extractStructured(chunkText, taskContext, backend, caller, profile, null);
    }

    public Result extractStructured(String chunkText,
                                    ExtractionTaskContext taskContext,
                                    ExtractionToolBackend backend,
                                    StructuredLlmCaller caller,
                                    DecomposedExtractionExecutor.PromptProfile profile,
                                    String additionalInstructions) {
        if (chunkText == null || chunkText.isBlank()) {
            return new Result(null, null, 0, 0, List.of(), List.of("source shard is empty"));
        }
        if (backend == null) {
            return new Result(null, null, 0, 0, List.of(), List.of("tool backend is unavailable"));
        }
        if (caller == null) {
            return new Result(null, null, 0, 0, List.of(), List.of("LLM caller is unavailable"));
        }

        int maxPromptChars = profile == null ? 14_336 : profile.maxPromptChars();
        DecomposedPromptTier requestedTier = effectiveStructuredTier(
                profile == null ? null : profile.tier());
        StructuredContract contract = structuredContract(
                chunkText, taskContext, backend, requestedTier, additionalInstructions);
        List<String> notes = new ArrayList<>();
        int requestedFixedChars = contract.fixedChars();
        while (contract.fixedChars() >= maxPromptChars) {
            DecomposedPromptTier lowerTier = lowerPresentationTier(contract.tier());
            if (lowerTier == null) {
                break;
            }
            StructuredContract lower = structuredContract(
                    chunkText, taskContext, backend, lowerTier, additionalInstructions);
            if (lower.tools().isEmpty()) {
                break;
            }
            contract = lower;
        }
        if (contract.tier() != requestedTier) {
            notes.add("native tool presentation reduced from " + requestedTier + " to "
                    + contract.tier() + " to fit the executable prompt budget: "
                    + requestedFixedChars + " -> " + contract.fixedChars() + " <= "
                    + maxPromptChars + " characters");
        }
        if (contract.tools().isEmpty()) {
            notes.add("tool backend exposes no standard function definitions");
            return new Result(null, null, 0, 0, List.of(), notes);
        }
        if (contract.fixedChars() >= maxPromptChars) {
            notes.add("source shard and native tool contract exceed the executable prompt budget: "
                    + contract.fixedChars() + " > " + maxPromptChars + " characters"
                    + " after compact presentation");
            return new Result(null, null, 0, 0, List.of(), notes);
        }

        DecomposedPromptTier presentationTier = contract.tier();
        List<ExtractionToolBackend.ToolDefinition> tools = contract.tools();
        String submissionTool = preferredSubmissionTool(tools);
        String system = contract.system();
        String user = contract.user();
        StructuredRetryState retryState = null;
        List<String> toolsUsed = new ArrayList<>();
        int calls = 0;
        int rounds = 0;

        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            rounds = round;
            // Refresh native function enums and CURRENT STATE after an ontology update. If the
            // enlarged ontology no longer fits, reduce only its presentation tier, never its contents.
            contract = structuredContract(
                    chunkText, taskContext, backend, presentationTier, additionalInstructions);
            while (contract.fixedChars() >= maxPromptChars) {
                DecomposedPromptTier lowerTier = lowerPresentationTier(contract.tier());
                if (lowerTier == null) {
                    break;
                }
                StructuredContract lower = structuredContract(
                        chunkText, taskContext, backend, lowerTier, additionalInstructions);
                if (lower.tools().isEmpty()) {
                    break;
                }
                contract = lower;
            }
            if (contract.tools().isEmpty() || contract.fixedChars() >= maxPromptChars) {
                notes.add("updated ontology and native tool contract exceed the executable prompt budget: "
                        + contract.fixedChars() + " > " + maxPromptChars + " characters");
                break;
            }
            if (contract.tier() != presentationTier) {
                notes.add("native tool presentation reduced from " + presentationTier + " to "
                        + contract.tier() + " after an ontology update");
            }
            presentationTier = contract.tier();
            tools = contract.tools();
            submissionTool = preferredSubmissionTool(tools);
            system = contract.system();
            user = contract.user();
            boolean minimalDirectRequest = backend.extractionTarget() == ExtractionTarget.ENTITIES_ONLY
                    || (!backend.ontologyUpdatesAllowed()
                    && presentationTier == DecomposedPromptTier.COMPACT);
            List<ChatMessage> messages = structuredMessages(
                    system, user, retryState, tools, maxPromptChars, !minimalDirectRequest);
            if (retryState != null && notes.stream().noneMatch(note -> note.contains(
                    "fresh request instead of conversation history"))) {
                notes.add("rendered explicit tool state into a fresh request instead of "
                        + "conversation history");
            }
            if (messages.stream().anyMatch(message -> message.content() != null
                    && message.content().contains(COMPACTED_TOOL_STATE_MARKER))
                    && notes.stream().noneMatch(note -> note.contains(
                    "compacted explicit tool state"))) {
                notes.add("compacted explicit tool state so validation remained visible "
                        + "inside the executable prompt budget");
            }
            StructuredRequest request = new StructuredRequest(messages, tools);
            final StructuredResponse response;
            try {
                response = caller.call(PASS_ID, request);
                if (response == null) {
                    throw new IllegalStateException("structured model call returned no response");
                }
            } catch (RuntimeException e) {
                notes.add("model call failed at round " + round + ": " + message(e));
                break;
            }

            StructuredResponseAssessment assessment =
                    assessStructuredResponse(request, response);
            String parseFeedback = null;
            if (!assessment.parseErrors().isEmpty()) {
                parseFeedback = invalidStructuredToolCallFeedback(
                        assessment.parseErrors(),
                        response.rawText(),
                        submissionTool);
                notes.add("round " + round + " parser returned malformed tool-call diagnostics: "
                        + String.join("; ", assessment.parseErrors()));
            }
            if (!assessment.parseErrors().isEmpty()) {
                String feedback = parseFeedback == null
                        ? invalidStructuredToolCallFeedback(
                                assessment.parseErrors(), response.rawText(), submissionTool)
                        : parseFeedback;
                retryState = retryState == null
                        ? StructuredRetryState.protocol(feedback)
                        : retryState.withProtocolFeedback(feedback);
                continue;
            }
            if (assessment.calls().isEmpty()) {
                String feedback = invalidStructuredToolCallFeedback(
                        List.of(),
                        response.rawText(),
                        submissionTool);
                retryState = retryState == null
                        ? StructuredRetryState.protocol(feedback)
                        : retryState.withProtocolFeedback(feedback);
                notes.add("round " + round
                        + " did not contain an executable native tool call; raw text was included in protocol feedback");
                continue;
            }

            List<ToolRequest> executable = new ArrayList<>();
            List<StructuredToolObservation> observations = new ArrayList<>();
            for (int index = 0; index < assessment.calls().size(); index++) {
                StructuredCallAssessment callAssessment = assessment.calls().get(index);
                ToolRequest call = callAssessment.request();
                String id = call.id().isBlank()
                        ? "call-" + round + "-" + (index + 1) : call.id();
                ToolRequest exactCall = new ToolRequest(
                        id, call.name(), call.arguments());
                if (!callAssessment.declared()) {
                    observations.add(new StructuredToolObservation(
                            exactCall, undeclaredToolJson(exactCall, tools)));
                    notes.add("round " + round + " rejected undeclared function '"
                            + exactCall.name() + "' without backend execution");
                    continue;
                }

                if (!callAssessment.schemaValid()) {
                    observations.add(new StructuredToolObservation(
                            exactCall,
                            invalidToolArgumentsJson(exactCall, callAssessment.errors())));
                    notes.add("round " + round + " rejected " + exactCall.name()
                            + " arguments without mutation or backend execution: "
                            + String.join("; ", callAssessment.errors()));
                    continue;
                }
                executable.add(exactCall);
            }
            if (executable.isEmpty()) {
                retryState = observations.isEmpty()
                        ? (retryState == null
                                ? StructuredRetryState.protocol(
                                        invalidStructuredToolCallFeedback(submissionTool))
                                : retryState.withProtocolFeedback(
                                        invalidStructuredToolCallFeedback(submissionTool)))
                        : StructuredRetryState.tools(
                                observations,
                                retryState == null ? "" : retryState.repairSeed(),
                                retryState != null && retryState.sourceRecheckRequired());
                continue;
            }

            for (ToolRequest toolCall : executable) {
                calls++;
                toolsUsed.add(toolCall.name());
                ExtractionToolBackend.ToolExecution execution;
                try {
                    execution = backend.execute(
                            toolCall.name(), MAPPER.valueToTree(toolCall.arguments()), chunkText);
                } catch (RuntimeException e) {
                    execution = ExtractionToolBackend.ToolExecution.continuing(
                            errorJson("tool_execution_failed", message(e)));
                    notes.add(toolCall.name() + " failed at round " + round + ": " + message(e));
                }
                observations.add(new StructuredToolObservation(toolCall, execution.json()));

                Optional<ExtractionResult> accepted = backend.acceptedResult();
                if (execution.terminal() && accepted.isPresent()) {
                    try {
                        return new Result(
                                GraphExtractionValidator.toJson(accepted.get()),
                                accepted.get(),
                                rounds,
                                calls,
                                toolsUsed,
                                notes);
                    } catch (Exception e) {
                        notes.add("accepted delta could not be serialized: " + message(e));
                        return new Result(null, null, rounds, calls, toolsUsed, notes);
                    }
                }
            }
            retryState = StructuredRetryState.tools(
                    observations,
                    retryState == null ? "" : retryState.repairSeed(),
                    retryState != null && retryState.sourceRecheckRequired());
        }

        return retainedResult(backend, rounds, calls, toolsUsed, notes,
                "native retry rounds ended after retaining validator-clean facts");
    }

    private static StructuredContract structuredContract(
            String chunkText,
            ExtractionTaskContext taskContext,
            ExtractionToolBackend backend,
            DecomposedPromptTier tier,
            String additionalInstructions) {
        List<ExtractionToolBackend.ToolDefinition> tools = backend.toolDefinitions(tier);
        String system = structuredSystemPrompt(
                backend.extractionTarget(),
                backend.ontologyUpdatesAllowed(),
                tier);
        if (!backend.ontologyUpdatesAllowed()
                && tier == DecomposedPromptTier.COMPACT
                && additionalInstructions != null
                && !additionalInstructions.isBlank()) {
            system = system + " Directive: " + additionalInstructions.strip();
        }
        String user = structuredUserPrompt(
                chunkText,
                taskContext,
                backend.toolContextJson(tier, taskContext),
                additionalInstructions,
                backend.extractionTarget(),
                backend.ontologyUpdatesAllowed(),
                tier);
        return new StructuredContract(
                tier, tools, system, user,
                system.length() + user.length() + tools.toString().length()
                        + TOOL_USE_REQUIREMENT.length());
    }

    private static DecomposedPromptTier effectiveStructuredTier(DecomposedPromptTier tier) {
        return tier == null || tier == DecomposedPromptTier.AUTO
                ? DecomposedPromptTier.STANDARD : tier;
    }

    private static DecomposedPromptTier lowerPresentationTier(DecomposedPromptTier tier) {
        return switch (effectiveStructuredTier(tier)) {
            case EXPANDED -> DecomposedPromptTier.RICH;
            case RICH -> DecomposedPromptTier.STANDARD;
            case STANDARD -> DecomposedPromptTier.COMPACT;
            case COMPACT, AUTO -> null;
        };
    }

    private static String basePrompt(String source,
                                     ExtractionTaskContext task,
                                     String catalog,
                                     DecomposedExtractionExecutor.PromptProfile profile,
                                     ExtractionTarget extractionTarget,
                                     String additionalInstructions) {
        StringBuilder prompt = new StringBuilder();
        if (extractionTarget == ExtractionTarget.ENTITIES_ONLY) {
            prompt.append("""
                    Copy every distinct entity name explicitly present in SOURCE.
                    Call submit_entities(names=["NAME 1", "NAME 2"]) directly. Copy each exact source
                    name once into the names array. The engine creates stable ids and
                    provisional records; ontology typing is a later phase.
                    Do not infer relations. Return only the tool call with no prose, markdown, or planning.
                    TOOLS:
                    """);
        } else {
            prompt.append("""
                    Build a knowledge-graph delta from SOURCE as one window of the unified corpus. Call one tool per turn.
                    TOOLS.graphSchema is the current prepass-derived ontology. Use its entity types,
                    relationship types, and directed endpoint patterns; a relationship has its own type.
                    TOOLS.callShape is the exact JSON envelope: return only its top-level tool and args
                    keys, with no prose, markdown, catalog fields, or state fields. It defaults to
                    submit_graph_delta; fill both arrays with every source-supported entity and relation.
                    Search the graph by names, emails, or aliases before creating an ambiguous identity;
                    reuse an existing id and record source-supported aliases instead of duplicating it.
                    Use graph_reasoning_query for existing graph structure, embeddings, logic, and schema;
                    use unified_corpus for missing cross-source evidence. If update_ontology is declared and SOURCE
                    establishes a reusable type or endpoint pattern missing from the ontology, call it and use
                    the refreshed revision on the next turn. Tool results are context; SOURCE and retrieved passages are evidence.
                    Correct validation feedback and resubmit. Arrays are unlimited.
                    TOOLS:
                    """);
        }
        prompt.append(catalog == null ? "{}" : catalog).append('\n');

        appendTaskHints(prompt, task);
        appendAdditionalInstructions(prompt, additionalInstructions);
        prompt.append("SOURCE SHARD:\n").append(source).append("\nEND SOURCE SHARD\n");
        return prompt.toString();
    }

    private static String structuredSystemPrompt(
            ExtractionTarget extractionTarget,
            boolean ontologyUpdatesAllowed,
            DecomposedPromptTier tier) {
        if (extractionTarget == ExtractionTarget.ENTITIES_ONLY) {
            return "Call submit_entities with each distinct name copied exactly from the text.";
        }
        if (!ontologyUpdatesAllowed && tier == DecomposedPromptTier.COMPACT) {
            return "Extract Text with submit_graph_delta(format=\"indexed\"). "
                    + "Copy distinct named entities once in Text order. Names come only from Text—not "
                    + "instructions, tool names, or schema. Assign stated ontology types. For each "
                    + "directed relation A to B, source is A's zero-based entity "
                    + "index and target is B's. Close both arrays and the call. No prose.";
        }
        if (!ontologyUpdatesAllowed) {
            return """
                    Extract only facts explicitly stated in SOURCE SHARD.
                    Allowed entity types, relation types, and endpoint patterns are labels, not evidence.
                    Never copy prompt, tool, schema, or validation text into an id, name, type, or relation.
                    Call submit_graph_delta. For each named entity, copy its exact SOURCE name, assign an
                    allowed type, and create a short stable id. For each explicit directed relation, use the
                    endpoint ids and an allowed relation type. Include both endpoint entities unless their ids
                    already exist in a populated current graph.
                    Use a lookup tool only when the current graph is populated and identity is ambiguous.
                    Do not submit empty arrays when SOURCE explicitly states matching entities or relations.
                    """;
        }
        return """
                Extract every SOURCE-supported fact into a graph delta with the declared tools.
                Evidence is the current SOURCE window and exact passages returned by unified_corpus;
                graph, ontology, hints, and other tool state are context, not evidence.
                All windows belong to one unified logical corpus. The crawl started with a schema prepass,
                and CURRENT GRAPH AND CORPUS STATE.graphSchema is the current authoritative ontology revision.
                Use node types for entities. Extract every supported directed relation and assign it a
                relationship type allowed by relationshipTypes and its source/relation/target pattern.
                Search graph_reasoning_query by a name, email, or alias before creating an ambiguous entity.
                Reuse the resolved id, attach source-supported aliases, and do not create duplicate identities.
                Use unified_corpus when the current window needs exact cross-source evidence.
                If update_ontology is declared and evidence establishes a missing reusable node type,
                relationship type, property, alias, or endpoint pattern, call it alone and use the refreshed
                ontology on the next turn. When it is not declared, the configured ontology is closed.
                CURRENT GRAPH AND CORPUS STATE.recommendedFirstTool is guidance. Call a function, not a plan.
                Finish with submit_graph_delta and include every supported entity and typed relation.
                Each relation endpoint must copy an id from this submission or the current graph.
                After rejection, retained facts stay accepted; repair or omit rejected items using evidence.
                """;
    }

    private static String structuredUserPrompt(
            String source,
            ExtractionTaskContext task,
            String toolContext,
            String additionalInstructions,
            ExtractionTarget extractionTarget,
            boolean ontologyUpdatesAllowed,
            DecomposedPromptTier tier) {
        if (extractionTarget == ExtractionTarget.ENTITIES_ONLY
                || (!ontologyUpdatesAllowed && tier == DecomposedPromptTier.COMPACT)) {
            return "Text: " + source.strip();
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("CURRENT GRAPH AND CORPUS STATE (context, not evidence):\n")
                .append(toolContext == null ? "{}" : toolContext)
                .append('\n');
        appendTaskHints(prompt, task);
        appendAdditionalInstructions(prompt, additionalInstructions);
        prompt.append("SOURCE SHARD:\n").append(source).append("\nEND SOURCE SHARD\n");
        return prompt.toString();
    }

    private static void appendAdditionalInstructions(
            StringBuilder prompt, String additionalInstructions) {
        if (additionalInstructions == null || additionalInstructions.isBlank()) {
            return;
        }
        prompt.append("PROJECT EXTRACTION INSTRUCTIONS (rules, not evidence):\n")
                .append(additionalInstructions.strip())
                .append("\nEND PROJECT EXTRACTION INSTRUCTIONS\n");
    }

    private static List<ChatMessage> structuredMessages(
            String system,
            String user,
            StructuredRetryState state,
            List<ExtractionToolBackend.ToolDefinition> tools,
            int maxChars,
            boolean appendToolRequirement) {
        int fixed = system.length() + user.length() + tools.toString().length()
                + (appendToolRequirement ? TOOL_USE_REQUIREMENT.length() : 0) + 32;
        int available = Math.max(0, maxChars - fixed - 64);
        String freshUser = user;
        if (state != null && available > 0) {
            RenderedToolState rendered = renderStructuredToolState(
                    state, available, preferredSubmissionTool(tools));
            if (!rendered.content().isBlank()) {
                freshUser = user + "\n" + rendered.content();
            }
        }
        if (appendToolRequirement) {
            freshUser = freshUser + "\n" + TOOL_USE_REQUIREMENT;
        }
        return List.of(
                ChatMessage.text("system", system),
                ChatMessage.text("user", freshUser));
    }

    private static RenderedToolState renderStructuredToolState(
            StructuredRetryState state, int availableChars, String submissionTool) {
        if (state == null || availableChars < 128) {
            return new RenderedToolState("", false);
        }
        String suffix = "\nUse only this explicit task state and the current request above. "
                + "Call the appropriate function now; finish with " + submissionTool + ".";
        if (state.protocolFeedback().isBlank() && state.observations().isEmpty()) {
            return new RenderedToolState("", false);
        }

        String protocolSection = state.protocolFeedback().isBlank()
                ? ""
                : "Protocol result:\n" + state.protocolFeedback() + "\n";
        StringBuilder full = new StringBuilder(EXPLICIT_TOOL_STATE_MARKER).append('\n')
                .append(protocolSection);
        boolean hasPersistentRepairSeed = !state.repairSeed().isBlank();
        if (hasPersistentRepairSeed) {
            full.append(COMPLETE_REPLACEMENT_GUIDANCE).append('\n')
                    .append(VALIDATOR_CLEAN_REPAIR_SEED_LABEL).append('\n')
                    .append(state.repairSeed()).append('\n');
        }
        for (StructuredToolObservation observation : state.observations()) {
            boolean includeRejectedDraft = rejectedSubmission(observation)
                    && !sourceGroundingRejected(observation)
                    && (!hasPersistentRepairSeed || state.sourceRecheckRequired());
            appendObservation(full, observation, includeRejectedDraft);
        }
        full.append(suffix);
        if (full.length() <= availableChars) {
            return new RenderedToolState(full.toString(), false);
        }

        if (state.observations().isEmpty()) {
            return fitState(COMPACTED_TOOL_STATE_MARKER + "\n"
                    + protocolSection + suffix, availableChars, true);
        }
        StructuredToolObservation latest =
                state.observations().get(state.observations().size() - 1);
        boolean rejected = rejectedSubmission(latest);
        String repairSeed = state.repairSeed();
        boolean hasRepairSeed = !repairSeed.isBlank();
        boolean includeRejectedDraft = rejected
                && !sourceGroundingRejected(latest)
                && (!hasRepairSeed || state.sourceRecheckRequired());
        String prefix = COMPACTED_TOOL_STATE_MARKER + "\n"
                + protocolSection
                + "Function: " + latest.request().name() + "\n"
                + (rejected ? REJECTED_SUBMISSION_GUIDANCE + "\n" : "")
                + (hasRepairSeed ? COMPLETE_REPLACEMENT_GUIDANCE + "\n" : "");
        StringBuilder argumentsContext = new StringBuilder();
        if (hasRepairSeed) {
            argumentsContext.append(VALIDATOR_CLEAN_REPAIR_SEED_LABEL).append('\n')
                    .append(repairSeed).append('\n');
        }
        if (includeRejectedDraft) {
            argumentsContext.append(REJECTED_CANDIDATE_DRAFT_LABEL).append('\n');
        } else if (!rejected) {
            argumentsContext.append("Previous arguments:\n");
        }
        String argumentsLabel = argumentsContext.toString();
        String resultLabel = rejected
                ? "Tool or validator result:\n" : "\nTool or validator result:\n";
        String arguments = includeRejectedDraft
                ? rejectedCandidateDraft(latest)
                : !rejected ? json(latest.request().arguments()) : "";
        String result = compactToolResult(latest.result(), hasRepairSeed);
        int bodyBudget = availableChars - prefix.length() - suffix.length()
                - argumentsLabel.length() - resultLabel.length();
        if (bodyBudget < 96) {
            String actionable = prefix + argumentsLabel + arguments + "\n" + suffix;
            return fitState(actionable, availableChars, true);
        }

        int minimumResultBudget = Math.min(96, bodyBudget);
        int preferredArgumentBudget = hasRepairSeed
                ? Math.max(0, bodyBudget - minimumResultBudget)
                : Math.max(96, bodyBudget / 3);
        int argumentBudget = Math.min(arguments.length(), preferredArgumentBudget);
        int resultBudget = Math.max(0, bodyBudget - argumentBudget);
        String compacted = prefix
                + argumentsLabel + fitComponent(arguments, argumentBudget)
                + resultLabel + fitComponent(result, resultBudget)
                + suffix;
        return fitState(compacted, availableChars, true);
    }

    private static void appendObservation(
            StringBuilder target,
            StructuredToolObservation observation,
            boolean includeRejectedDraft) {
        target.append("Function: ").append(observation.request().name()).append('\n');
        boolean rejected = rejectedSubmission(observation);
        if (rejected) {
            target.append(REJECTED_SUBMISSION_GUIDANCE).append('\n');
        }
        if (!rejected || includeRejectedDraft) {
            target.append(rejected ? REJECTED_CANDIDATE_DRAFT_LABEL : "Previous arguments:")
                    .append('\n')
                    .append(json(observation.request().arguments())).append('\n');
        }
        target.append("Tool or validator result:\n")
                .append(compactToolResult(observation.result())).append('\n');
    }

    private static boolean rejectedSubmission(StructuredToolObservation observation) {
        if (observation == null || observation.request() == null
                || !CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA.equalsIgnoreCase(
                        observation.request().name())
                || observation.result() == null || observation.result().isBlank()) {
            return false;
        }
        try {
            JsonNode result = MAPPER.readTree(observation.result());
            return result != null && result.isObject()
                    && result.has("ok") && !result.path("ok").asBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean requiresRejectedCandidateDraft(StructuredToolObservation observation) {
        if (!rejectedSubmission(observation)) {
            return false;
        }
        try {
            JsonNode result = MAPPER.readTree(observation.result());
            if (sourceGroundingRejected(result)) {
                return false;
            }
            JsonNode correction = result.path("correction");
            JsonNode rejectedEntities = correction.path("rejectedEntities");
            if (rejectedEntities.isArray() && !rejectedEntities.isEmpty()) {
                return true;
            }
            JsonNode rejectedRelations = correction.path("rejectedRelations");
            return rejectedRelations.isArray() && rejectedRelations.size() > 1;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean sourceGroundingRejected(StructuredToolObservation observation) {
        if (!rejectedSubmission(observation)) {
            return false;
        }
        try {
            return sourceGroundingRejected(MAPPER.readTree(observation.result()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean sourceGroundingRejected(JsonNode result) {
        JsonNode errors = result == null ? null : result.path("errors");
        if (errors == null || !errors.isArray()) {
            return false;
        }
        for (JsonNode error : errors) {
            if (error.asText("").contains("[SOURCE_GROUNDING]")) {
                return true;
            }
        }
        return false;
    }

    private static String rejectedCandidateDraft(StructuredToolObservation observation) {
        try {
            JsonNode submitted = MAPPER.valueToTree(observation.request().arguments());
            JsonNode correction = MAPPER.readTree(observation.result()).path("correction");
            ObjectNode draft = MAPPER.createObjectNode();
            ArrayNode entities = draft.putArray("entities");
            ArrayNode relations = draft.putArray("relations");
            copyRejectedItems(entities, submitted.path("entities"), correction.path("rejectedEntities"));
            copyRejectedItems(relations, submitted.path("relations"), correction.path("rejectedRelations"));
            if (!entities.isEmpty() || !relations.isEmpty()) {
                return MAPPER.writeValueAsString(draft);
            }
        } catch (Exception ignored) {
            // Fall through to the exact submitted arguments when diagnostics are incomplete.
        }
        return json(observation.request().arguments());
    }

    private static void copyRejectedItems(
            ArrayNode target, JsonNode submitted, JsonNode rejectedItems) {
        if (!submitted.isArray() || !rejectedItems.isArray()) {
            return;
        }
        for (JsonNode rejected : rejectedItems) {
            int index = rejected.path("index").asInt(-1);
            if (index >= 0 && index < submitted.size()) {
                target.add(submitted.get(index));
            }
        }
    }

    /**
     * Return the backend-retained validator-clean subset for compact retry rendering. It is not
     * evidence, but its ids are accepted and may be referenced by corrected additions.
     */
    private static String validatorCleanRepairSeed(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return "";
        }
        try {
            JsonNode correction = MAPPER.readTree(toolResult).path("correction");
            JsonNode seed = correction.path("repairSeed");
            if (!correction.path("repairSeedValid").asBoolean(false)
                    || !seed.isObject()
                    || !seed.path("entities").isArray()
                    || !seed.path("relations").isArray()
                    || (seed.path("entities").isEmpty() && seed.path("relations").isEmpty())) {
                return "";
            }
            return MAPPER.writeValueAsString(seed);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static RenderedToolState fitState(
            String state, int availableChars, boolean compacted) {
        if (state == null || state.isBlank() || availableChars <= 0) {
            return new RenderedToolState("", compacted);
        }
        if (state.length() <= availableChars) {
            return new RenderedToolState(state, compacted);
        }
        return new RenderedToolState(
                fitComponent(state, availableChars), true);
    }

    private static String fitComponent(String content, int maxChars) {
        if (content == null || maxChars <= 0) {
            return "";
        }
        if (content.length() <= maxChars) {
            return content;
        }
        String omitted = "\n[remaining explicit state omitted]";
        if (maxChars <= omitted.length()) {
            return content.substring(0, maxChars);
        }
        return content.substring(0, maxChars - omitted.length()) + omitted;
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String compactToolResult(String content) {
        return compactToolResult(content, false);
    }

    private static String compactToolResult(String content, boolean omitRenderedRepairSeed) {
        if (content == null || content.isBlank()) {
            return "{}";
        }
        try {
            JsonNode parsed = MAPPER.readTree(content);
            if (parsed != null && parsed.isObject()) {
                ObjectNode compact = MAPPER.createObjectNode();
                for (String field : List.of(
                        "ok", "error", "detail", "errors", "warnings", "correction",
                        "guidance", "tool", "receivedTool", "declaredTools",
                        "argumentsPreserved", "receivedTopLevelKeys", "graphEntities",
                        "graphRelations", "result", "matches", "exactText", "passages",
                        "candidates", "snapshotId", "corpusPassages",
                        "incompletePassagesExcluded", "embedding", "offset", "returned",
                        "totalMatches", "results", "chunkId", "chunkIndex", "start", "end",
                        "totalChars", "hasMore", "metadata")) {
                    if (parsed.has(field)) {
                        if (omitRenderedRepairSeed && "correction".equals(field)
                                && parsed.get(field).isObject()) {
                            ObjectNode correction = parsed.get(field).deepCopy();
                            correction.remove("repairSeed");
                            compact.set(field, correction);
                        } else {
                            compact.set(field, parsed.get(field));
                        }
                    }
                }
                boolean hasValidationErrors = parsed.path("errors").isArray()
                        && !parsed.path("errors").isEmpty();
                if (parsed.has("requiredShape")
                        && (!hasValidationErrors || parsed.hasNonNull("error"))) {
                    compact.set("requiredShape", parsed.get("requiredShape"));
                }
                if (!compact.isEmpty()) {
                    return MAPPER.writeValueAsString(compact);
                }
            }
        } catch (Exception ignored) {
            // Tool output is model-facing diagnostic text; preserve it verbatim below.
        }
        return content;
    }

    private static void appendTaskHints(StringBuilder prompt, ExtractionTaskContext task) {
        if (task == null) {
            return;
        }
        prompt.append("TASK ROUTING CONTEXT (not evidence):\n");
        append(prompt, "graphRevision", task.graphRevision());
        if (!task.subjects().isEmpty()) {
            append(prompt, "subjects", String.join(" | ", task.subjects()));
        }

        List<String> conceptHints = task.conceptHints() == null ? List.of() : task.conceptHints().stream()
                .filter(hint -> hint != null
                        && hint.term() != null && !hint.term().isBlank()
                        && hint.category() != null && !hint.category().isBlank()
                        && isDeterministicPrepassHint(hint))
                .map(hint -> hint.term().trim() + " [" + hint.category().trim() + "]")
                .distinct()
                .limit(16)
                .toList();
        if (!conceptHints.isEmpty()) {
            prompt.append("HINTS (candidate boundaries and types; not evidence):\n");
            for (String hint : conceptHints) {
                prompt.append(hint).append('\n');
            }
        }
    }

    private static boolean isDeterministicPrepassHint(ConceptHint hint) {
        if (hint == null || hint.provenance() == null) {
            return false;
        }
        String source = hint.provenance().toLowerCase(Locale.ROOT);
        return source.contains("deterministic");
    }

    private static void append(StringBuilder target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.append(key).append(": ").append(value).append('\n');
        }
    }

    private static String renderPrompt(String base, ToolState state, int maxChars) {
        String tail = "Return exactly the next tool call envelope now; use only top-level tool and args.\n";
        int available = Math.max(0, maxChars - base.length() - tail.length() - 32);
        StringBuilder prompt = new StringBuilder(base);
        if (state != null && available > 0) {
            String prefix = EXPLICIT_TOOL_STATE_MARKER + "\n";
            String suffix = "\nUse this explicit state in a fresh request; do not continue prior prose.\n";
            String modelCall = state.modelCall();
            String toolResult = compactToolResult(state.toolResult());
            String full = prefix;
            if (!state.protocolFeedback().isBlank()) {
                full += "Protocol result:\n" + state.protocolFeedback() + "\n";
            }
            if (!modelCall.isBlank()) {
                full += "Previous tool request:\n" + modelCall + "\n";
            }
            if (!state.toolResult().isBlank()) {
                full += "Tool or validator result:\n" + toolResult + suffix;
            } else {
                full += suffix;
            }
            if (full.length() > available) {
                String compactPrefix = COMPACTED_TOOL_STATE_MARKER + "\n";
                String protocol = state.protocolFeedback().isBlank()
                        ? ""
                        : "Protocol result:\n" + state.protocolFeedback() + "\n";
                int resultBudget = Math.max(0,
                        available - compactPrefix.length() - protocol.length()
                                - suffix.length());
                full = compactPrefix + protocol
                        + fitComponent(toolResult, resultBudget) + suffix;
            }
            prompt.append(fitComponent(full, available));
        }
        prompt.append(tail);
        return prompt.toString();
    }

    private static Optional<ToolCall> parseToolCall(String response) {
        if (response == null || response.isBlank()) {
            return Optional.empty();
        }
        for (int start = response.indexOf('{'); start >= 0; start = response.indexOf('{', start + 1)) {
            String candidate = balancedObjectAt(response, start);
            if (candidate == null) {
                continue;
            }
            try {
                JsonNode root = MAPPER.readTree(candidate);
                JsonNode tool = root.get("tool");
                if (tool == null || !tool.isTextual() || tool.asText().isBlank()) {
                    throw new IllegalStateException("Missing or invalid top-level tool field.");
                }
                JsonNode args = root.get("args");
                if (args == null || args.isNull()) {
                    args = MAPPER.createObjectNode();
                }
                if (!args.isObject()) {
                    throw new IllegalStateException("Top-level args is not an object.");
                }
                return Optional.of(new ToolCall(tool.asText(), args));
            } catch (Exception ignored) {
                if (looksLikeToolEnvelope(candidate)) {
                    throw new IllegalStateException("Tool envelope text is not valid JSON.", ignored);
                }
                // Keep scanning: a reasoning prefix may contain a different brace pair.
            }
        }
        return Optional.empty();
    }

    private static boolean looksLikeToolEnvelope(String text) {
        return text != null && text.contains("\"tool\"")
                && (text.contains("\"args\"") || text.contains("\"tool\""));
    }

    private static String balancedObjectAt(String text, int start) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    quoted = false;
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String invalidToolCallFeedback(ExtractionTarget extractionTarget) {
        if (extractionTarget == ExtractionTarget.ENTITIES_ONLY) {
            return "{\"ok\":false,\"error\":\"invalid_tool_call\","
                    + "\"required\":\"Use only top-level tool and args\","
                    + "\"defaultShape\":{\"tool\":\"submit_entities\","
                    + "\"args\":{\"names\":[\"NAME 1\",\"NAME 2\"]}}}";
        }
        return "{\"ok\":false,\"error\":\"invalid_tool_call\","
                + "\"required\":\"Use only top-level tool and args\","
                + "\"defaultShape\":{\"tool\":\"submit_graph_delta\","
                + "\"args\":{\"entities\":[],\"relations\":[]}}}";
    }

    private static String invalidStructuredToolCallFeedback(String submissionTool) {
        return "No executable function call was returned by the model-owned chat tool interface. "
                + "Invoke exactly one declared function. Do not ask for confirmation, explain, "
                + "write a call as text, or say that you will call a tool. "
                + "Finish with " + submissionTool + ".";
    }

    private static String invalidStructuredToolCallFeedback(
            List<String> parserErrors,
            String rawText,
            String submissionTool) {
        StringBuilder feedback = new StringBuilder(
                "No executable function call was returned by the model-owned chat tool interface. "
                        + "Invoke exactly one declared function through that interface. "
                        + "Do not write a call as text or include prose, markdown, planning, or explanation.");
        if (parserErrors != null && !parserErrors.isEmpty()) {
            feedback.append(" Feedback: ").append(String.join("; ", parserErrors));
        }
        if (rawText != null && !rawText.isBlank()) {
            String compact = rawText.length() > 800
                    ? rawText.substring(0, 800) + " ..."
                    : rawText;
            feedback.append(" Raw output: ").append(compact);
        }
        feedback.append(" Finish with ").append(submissionTool).append('.');
        return feedback.toString();
    }

    private static String preferredSubmissionTool(
            List<ExtractionToolBackend.ToolDefinition> tools) {
        if (tools != null && tools.stream().anyMatch(
                tool -> CrawlExtractionToolBackend.SUBMIT_ENTITIES.equals(tool.name()))) {
            return CrawlExtractionToolBackend.SUBMIT_ENTITIES;
        }
        return CrawlExtractionToolBackend.SUBMIT_GRAPH_DELTA;
    }

    private static String undeclaredToolJson(
            ToolRequest request,
            List<ExtractionToolBackend.ToolDefinition> tools) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", false);
        response.put("error", "undeclared_tool");
        response.put("detail", request.name().isBlank()
                ? "A declared function name is required."
                : "Function '" + request.name() + "' was not declared for this request.");
        response.put("receivedTool", request.name());
        response.put("declaredTools", tools.stream()
                .map(ExtractionToolBackend.ToolDefinition::name)
                .toList());
        response.put("argumentsPreserved", true);
        response.put("guidance",
                "Call one of declaredTools exactly. The received arguments were not executed or changed.");
        return json(response);
    }

    private static String invalidToolArgumentsJson(
            ToolRequest request,
            List<String> errors) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", false);
        response.put("error", "invalid_tool_arguments");
        response.put("tool", request.name());
        response.put("errors", errors == null ? List.of() : errors);
        response.put("argumentsPreserved", true);
        response.put("guidance",
                "Correct the same candidate against the declared function schema and SOURCE. "
                        + "No field was renamed, dropped, coerced, or executed.");
        return json(response);
    }

    private static String errorJson(String code, String detail) {
        try {
            return MAPPER.writeValueAsString(java.util.Map.of(
                    "ok", false,
                    "error", code,
                    "detail", detail == null ? "" : detail));
        } catch (Exception ignored) {
            return "{\"ok\":false,\"error\":\"tool_execution_failed\"}";
        }
    }

    private static Result retainedResult(ExtractionToolBackend backend,
                                         int rounds,
                                         int calls,
                                         List<String> toolsUsed,
                                         List<String> notes,
                                         String note) {
        Optional<ExtractionResult> retained = backend.acceptedResult();
        if (retained.isEmpty()) {
            return new Result(null, null, rounds, calls, toolsUsed, notes);
        }
        try {
            notes.add(note);
            return new Result(GraphExtractionValidator.toJson(retained.get()), retained.get(),
                    rounds, calls, toolsUsed, notes);
        } catch (Exception e) {
            notes.add("retained delta could not be serialized: " + message(e));
            return new Result(null, null, rounds, calls, toolsUsed, notes);
        }
    }

    private static String message(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }
}
