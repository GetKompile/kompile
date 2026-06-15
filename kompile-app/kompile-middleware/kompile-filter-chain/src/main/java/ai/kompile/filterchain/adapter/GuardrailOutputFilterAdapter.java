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

package ai.kompile.filterchain.adapter;

import ai.kompile.core.filter.*;
import ai.kompile.core.guardrails.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adapter that wraps an OutputGuardrail as a Filter.
 * This provides backward compatibility for existing output guardrails
 * within the new filter chain framework.
 */
public class GuardrailOutputFilterAdapter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(GuardrailOutputFilterAdapter.class);

    private final OutputGuardrail guardrail;
    private final String id;
    private boolean enabled;

    public GuardrailOutputFilterAdapter(OutputGuardrail guardrail) {
        this.guardrail = guardrail;
        this.id = "guardrail-output-" + guardrail.getName().toLowerCase().replace(" ", "-");
        this.enabled = guardrail.isEnabled();
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return guardrail.getName();
    }

    @Override
    public String getDescription() {
        return "Output guardrail: " + guardrail.getName();
    }

    @Override
    public Set<FilterPhase> getApplicablePhases() {
        return EnumSet.of(FilterPhase.POST_LLM);
    }

    @Override
    public int getPriority() {
        return guardrail.getPriority();
    }

    @Override
    public boolean isEnabled() {
        return enabled && guardrail.isEnabled();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public FilterType getType() {
        return FilterType.LOCAL;
    }

    @Override
    public FilterResult execute(FilterContext context, FilterPhase phase) {
        log.debug("Executing output guardrail adapter: {}", guardrail.getName());

        // Build guardrail context from filter context
        GuardrailContext guardrailContext = buildGuardrailContext(context);

        // Get the LLM response to validate
        String llmResponse = context.getLlmResponse();
        if (llmResponse == null || llmResponse.isBlank()) {
            log.debug("No LLM response to validate, skipping guardrail");
            return FilterResult.continueWith(context);
        }

        // Get retrieved context for hallucination checks
        List<String> retrievedContext = context.getRetrievedDocuments().stream()
                .map(doc -> doc.getText())
                .toList();

        // Execute the guardrail
        GuardrailResult result = guardrail.validate(
                llmResponse,
                context.getOriginalQuery(),
                retrievedContext,
                guardrailContext
        );

        // Convert guardrail result to filter result
        return convertResult(result, context);
    }

    @Override
    public boolean requiresLlm() {
        return guardrail.requiresLlm();
    }

    @Override
    public String[] getCategories() {
        GuardrailCategory[] categories = guardrail.getCategories();
        if (categories == null) {
            return new String[]{"guardrail", "output"};
        }
        String[] result = new String[categories.length + 2];
        result[0] = "guardrail";
        result[1] = "output";
        for (int i = 0; i < categories.length; i++) {
            result[i + 2] = categories[i].name().toLowerCase();
        }
        return result;
    }

    /**
     * Build a GuardrailContext from FilterContext.
     */
    private GuardrailContext buildGuardrailContext(FilterContext context) {
        return GuardrailContext.builder()
                .conversationHistory(context.getConversationHistory())
                .userId(context.getUserId())
                .sessionId(context.getSessionId())
                .build();
    }

    /**
     * Convert a GuardrailResult to a FilterResult.
     */
    private FilterResult convertResult(GuardrailResult result, FilterContext context) {
        if (result.isPassed()) {
            // Guardrail passed
            if (result.getAction() == GuardrailAction.WARN) {
                context.addTrace(FilterTraceEntry.warning(id,
                        "Guardrail warning: " + result.getFailureReason()));
            }
            return FilterResult.continueWith(context);
        }

        // Guardrail failed
        return switch (result.getAction()) {
            case BLOCK -> FilterResult.terminateUserError(
                    result.getFailureReason() != null ?
                            result.getFailureReason() :
                            "Response blocked by " + guardrail.getName(),
                    400
            );
            case RETRY -> {
                // For output guardrails, RETRY means the LLM should regenerate
                // In filter chain context, we can signal this to the caller
                context.addTrace(FilterTraceEntry.warning(id,
                        "Guardrail requested retry: " + result.getFailureReason()));

                // Add retry metadata to context
                context.getRequestMetadata().put("guardrail_retry_requested", true);
                context.getRequestMetadata().put("guardrail_retry_reason", result.getFailureReason());

                if (guardrail.supportsRetry() && result.getSuggestions() != null) {
                    context.getRequestMetadata().put("guardrail_retry_suggestions", result.getSuggestions());
                }

                yield FilterResult.continueWith(context);
            }
            case WARN -> {
                context.addTrace(FilterTraceEntry.warning(id,
                        "Guardrail warning: " + result.getFailureReason()));
                yield FilterResult.continueWith(context);
            }
            case MODIFY -> {
                // If the guardrail suggests modifications, log them
                context.addTrace(FilterTraceEntry.info(id,
                        "Guardrail suggested modification: " + result.getFailureReason()));
                yield FilterResult.continueWith(context);
            }
            case ESCALATE -> {
                context.addTrace(FilterTraceEntry.warning(id,
                        "Guardrail escalation: " + result.getFailureReason()));
                yield FilterResult.continueWith(context);
            }
            default -> FilterResult.continueWith(context);
        };
    }

    /**
     * Check if this guardrail supports retry.
     */
    public boolean supportsRetry() {
        return guardrail.supportsRetry();
    }

    /**
     * Get maximum retries for this guardrail.
     */
    public int getMaxRetries() {
        return guardrail.getMaxRetries();
    }

    /**
     * Get the underlying guardrail.
     */
    public OutputGuardrail getGuardrail() {
        return guardrail;
    }
}
