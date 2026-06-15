/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.react.component.impl;

import ai.kompile.react.api.Observer;
import ai.kompile.react.context.AgentContext;
import ai.kompile.react.model.ReActMessage;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Default implementation of the Observer interface.
 * Processes tool execution results and prepares observations for the next reasoning step.
 */
@Slf4j
public class DefaultObserver implements Observer {

    private final String id;
    private final String name;
    private final boolean summarizeResults;
    private final int maxResultLength;

    @Builder
    public DefaultObserver(
            String id,
            String name,
            Boolean summarizeResults,
            Integer maxResultLength
    ) {
        this.id = id != null ? id : "default-observer";
        this.name = name != null ? name : "Default Observer";
        this.summarizeResults = summarizeResults != null ? summarizeResults : true;
        this.maxResultLength = maxResultLength != null ? maxResultLength : 2000;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public CompletableFuture<Optional<ReActMessage>> observe(AgentContext context, List<ReActMessage> actionResults) {
        return CompletableFuture.supplyAsync(() -> observeSync(context, actionResults));
    }

    @Override
    public Optional<ReActMessage> observeSync(AgentContext context, List<ReActMessage> actionResults) {
        if (actionResults == null || actionResults.isEmpty()) {
            log.debug("No action results to observe");
            return Optional.empty();
        }

        log.debug("Observing {} action results", actionResults.size());

        // Check for any errors
        List<ReActMessage> errors = actionResults.stream()
                .filter(r -> r.getToolSuccess() != null && !r.getToolSuccess())
                .collect(Collectors.toList());

        if (!errors.isEmpty()) {
            log.warn("Observed {} tool errors", errors.size());
            // Errors are already in the results, no need for additional observation
        }

        // Check for patterns or special conditions
        boolean allSuccessful = actionResults.stream()
                .allMatch(r -> r.getToolSuccess() == null || r.getToolSuccess());

        // Optionally create a summary observation
        if (summarizeResults && actionResults.size() > 1) {
            String summary = createSummary(actionResults, allSuccessful);
            return Optional.of(ReActMessage.builder()
                    .role(ReActMessage.Role.SYSTEM)
                    .content(summary)
                    .build());
        }

        // No additional observation needed
        return Optional.empty();
    }

    private String createSummary(List<ReActMessage> results, boolean allSuccessful) {
        StringBuilder summary = new StringBuilder();
        summary.append("Observation: ");

        if (allSuccessful) {
            summary.append("All ").append(results.size()).append(" tool calls completed successfully.");
        } else {
            long successCount = results.stream()
                    .filter(r -> r.getToolSuccess() == null || r.getToolSuccess())
                    .count();
            summary.append(successCount).append(" of ").append(results.size())
                    .append(" tool calls succeeded.");
        }

        // Add brief summary of each result
        for (ReActMessage result : results) {
            summary.append("\n- ").append(result.getToolName()).append(": ");

            if (result.getToolSuccess() != null && !result.getToolSuccess()) {
                summary.append("FAILED - ").append(result.getToolError());
            } else {
                String content = result.getContent();
                if (content != null && content.length() > 100) {
                    summary.append(content.substring(0, 100)).append("...");
                } else {
                    summary.append(content);
                }
            }
        }

        return summary.toString();
    }
}
