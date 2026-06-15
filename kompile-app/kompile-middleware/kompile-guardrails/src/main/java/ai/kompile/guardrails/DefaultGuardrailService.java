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

package ai.kompile.guardrails;

import ai.kompile.core.guardrails.*;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Default implementation of GuardrailService.
 */
@Slf4j
public class DefaultGuardrailService implements GuardrailService {

    private final List<InputGuardrail> inputGuardrails;
    private final List<OutputGuardrail> outputGuardrails;
    private final GuardrailsProperties properties;

    public DefaultGuardrailService(
            List<InputGuardrail> inputGuardrails,
            List<OutputGuardrail> outputGuardrails,
            GuardrailsProperties properties) {
        // Sort by priority
        this.inputGuardrails = inputGuardrails.stream()
                .filter(InputGuardrail::isEnabled)
                .sorted(Comparator.comparingInt(InputGuardrail::getPriority))
                .collect(Collectors.toList());
        this.outputGuardrails = outputGuardrails.stream()
                .filter(OutputGuardrail::isEnabled)
                .sorted(Comparator.comparingInt(OutputGuardrail::getPriority))
                .collect(Collectors.toList());
        this.properties = properties;

        log.info("Initialized guardrail service with {} input and {} output guardrails",
                this.inputGuardrails.size(), this.outputGuardrails.size());
    }

    @Override
    public GuardrailResult validateInput(String input, GuardrailContext context) {
        if (!properties.isEnabled() || inputGuardrails.isEmpty()) {
            return GuardrailResult.pass("guardrails-disabled");
        }

        List<GuardrailResult> results = new ArrayList<>();

        for (InputGuardrail guardrail : inputGuardrails) {
            try {
                GuardrailResult result = guardrail.validate(input, context);
                results.add(result);

                if (!result.isPassed() && result.getAction() == GuardrailAction.BLOCK) {
                    log.warn("Input blocked by guardrail '{}': {}",
                            guardrail.getName(), result.getFailureReason());
                    throw new GuardrailException(result);
                }
            } catch (GuardrailException e) {
                throw e; // Re-throw GuardrailException
            } catch (Exception e) {
                log.error("Error in guardrail '{}': {}", guardrail.getName(), e.getMessage(), e);
                // Continue with other guardrails on error
            }
        }

        // Aggregate results
        return aggregateResults("input-validation", results);
    }

    @Override
    public GuardrailResult validateOutput(String output, String originalQuery,
                                          List<String> retrievedContext, GuardrailContext context) {
        if (!properties.isEnabled() || outputGuardrails.isEmpty()) {
            return GuardrailResult.pass("guardrails-disabled");
        }

        List<GuardrailResult> results = new ArrayList<>();

        for (OutputGuardrail guardrail : outputGuardrails) {
            try {
                GuardrailResult result = guardrail.validate(output, originalQuery, retrievedContext, context);
                results.add(result);

                if (!result.isPassed()) {
                    log.warn("Output failed guardrail '{}': {}",
                            guardrail.getName(), result.getFailureReason());
                }
            } catch (Exception e) {
                log.error("Error in guardrail '{}': {}", guardrail.getName(), e.getMessage(), e);
            }
        }

        return aggregateResults("output-validation", results);
    }

    private GuardrailResult aggregateResults(String name, List<GuardrailResult> results) {
        if (results.isEmpty()) {
            return GuardrailResult.pass(name);
        }

        boolean allPassed = results.stream().allMatch(GuardrailResult::isPassed);
        List<GuardrailResult.Violation> allViolations = results.stream()
                .flatMap(r -> r.getViolations().stream())
                .collect(Collectors.toList());

        // Determine action based on results
        GuardrailAction action = GuardrailAction.CONTINUE;
        if (!allPassed) {
            // Check if any result requires blocking
            boolean hasBlock = results.stream()
                    .anyMatch(r -> r.getAction() == GuardrailAction.BLOCK);
            boolean hasRetry = results.stream()
                    .anyMatch(r -> r.getAction() == GuardrailAction.RETRY);

            if (hasBlock) {
                action = GuardrailAction.BLOCK;
            } else if (hasRetry) {
                action = GuardrailAction.RETRY;
            } else {
                action = GuardrailAction.WARN;
            }
        }

        String failureReason = results.stream()
                .filter(r -> !r.isPassed())
                .map(GuardrailResult::getFailureReason)
                .collect(Collectors.joining("; "));

        return GuardrailResult.builder()
                .guardrailName(name)
                .passed(allPassed)
                .action(action)
                .failureReason(failureReason.isEmpty() ? null : failureReason)
                .violations(allViolations)
                .build();
    }

    @Override
    public List<InputGuardrail> getInputGuardrails() {
        return new ArrayList<>(inputGuardrails);
    }

    @Override
    public List<OutputGuardrail> getOutputGuardrails() {
        return new ArrayList<>(outputGuardrails);
    }

    @Override
    public void registerInputGuardrail(InputGuardrail guardrail) {
        inputGuardrails.add(guardrail);
        inputGuardrails.sort(Comparator.comparingInt(InputGuardrail::getPriority));
    }

    @Override
    public void registerOutputGuardrail(OutputGuardrail guardrail) {
        outputGuardrails.add(guardrail);
        outputGuardrails.sort(Comparator.comparingInt(OutputGuardrail::getPriority));
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }
}
