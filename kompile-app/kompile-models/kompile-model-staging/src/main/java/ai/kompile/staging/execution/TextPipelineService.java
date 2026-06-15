/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.staging.execution;

import ai.kompile.staging.web.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory CRUD and execution service for text generation pipelines.
 * A pipeline is a sequence of text generation steps where each step can
 * reference outputs from previous steps via {{input}} and {{stepId_output}} variables.
 */
@Service
public class TextPipelineService {

    private static final Logger log = LoggerFactory.getLogger(TextPipelineService.class);

    private final ConcurrentHashMap<String, TextPipelineDefinition> pipelines = new ConcurrentHashMap<>();
    private final LlmExecutionService llmExecutionService;
    private final ChatTemplateService chatTemplateService;

    public TextPipelineService(LlmExecutionService llmExecutionService, ChatTemplateService chatTemplateService) {
        this.llmExecutionService = llmExecutionService;
        this.chatTemplateService = chatTemplateService;
    }

    public TextPipelineDefinition create(TextPipelineDefinition definition) {
        if (definition.getId() == null || definition.getId().isBlank()) {
            definition.setId(UUID.randomUUID().toString().substring(0, 8));
        }
        // Auto-assign step IDs if not set
        if (definition.getSteps() != null) {
            for (int i = 0; i < definition.getSteps().size(); i++) {
                TextPipelineStep step = definition.getSteps().get(i);
                if (step.getStepId() == null || step.getStepId().isBlank()) {
                    step.setStepId("step" + (i + 1));
                }
                if (step.getName() == null || step.getName().isBlank()) {
                    step.setName("Step " + (i + 1));
                }
            }
        }
        pipelines.put(definition.getId(), definition);
        return definition;
    }

    public TextPipelineDefinition getById(String id) {
        return pipelines.get(id);
    }

    public List<TextPipelineDefinition> listAll() {
        return new ArrayList<>(pipelines.values());
    }

    public boolean delete(String id) {
        return pipelines.remove(id) != null;
    }

    /**
     * Execute a pipeline sequentially. Each step's prompt template is resolved
     * with variables including {{input}} (the initial input) and
     * {{stepId_output}} (the output from a previous step).
     */
    public TextPipelineResult execute(String pipelineId, Map<String, String> inputVariables) {
        TextPipelineDefinition definition = pipelines.get(pipelineId);
        if (definition == null) {
            return TextPipelineResult.builder()
                    .pipelineId(pipelineId)
                    .finishReason("error: pipeline not found")
                    .build();
        }

        long startTime = System.currentTimeMillis();
        List<TextPipelineResult.StepOutput> stepOutputs = new ArrayList<>();
        Map<String, String> resolvedVars = new HashMap<>();

        // Merge pipeline-level variables with input variables
        if (definition.getVariables() != null) {
            resolvedVars.putAll(definition.getVariables());
        }
        if (inputVariables != null) {
            resolvedVars.putAll(inputVariables);
        }

        String lastOutput = "";
        String overallFinishReason = "completed";

        for (TextPipelineStep step : definition.getSteps()) {
            long stepStart = System.currentTimeMillis();

            // Resolve the prompt template with variables
            String prompt = step.getPromptTemplate();
            prompt = chatTemplateService.substituteVariables(prompt, resolvedVars);

            // Generate
            LlmGenerateRequest request = LlmGenerateRequest.builder()
                    .prompt(prompt)
                    .maxTokens(step.getMaxTokens())
                    .presetName(step.getPresetName())
                    .build();

            LlmGenerateResponse response = llmExecutionService.generate(request);

            long stepTime = System.currentTimeMillis() - stepStart;
            String output = response.getGeneratedText() != null ? response.getGeneratedText() : "";

            stepOutputs.add(TextPipelineResult.StepOutput.builder()
                    .stepId(step.getStepId())
                    .stepName(step.getName())
                    .prompt(prompt)
                    .output(output)
                    .timeMs(stepTime)
                    .finishReason(response.getFinishReason())
                    .build());

            // Make this step's output available to subsequent steps
            resolvedVars.put(step.getStepId() + "_output", output);
            lastOutput = output;

            // Stop if error
            if (response.getFinishReason() != null && response.getFinishReason().startsWith("error")) {
                overallFinishReason = "error: step " + step.getStepId() + " failed";
                break;
            }

            log.info("Pipeline {} step {} completed in {}ms", pipelineId, step.getStepId(), stepTime);
        }

        long totalTime = System.currentTimeMillis() - startTime;

        return TextPipelineResult.builder()
                .pipelineId(pipelineId)
                .stepOutputs(stepOutputs)
                .finalOutput(lastOutput)
                .totalTimeMs(totalTime)
                .finishReason(overallFinishReason)
                .build();
    }
}
