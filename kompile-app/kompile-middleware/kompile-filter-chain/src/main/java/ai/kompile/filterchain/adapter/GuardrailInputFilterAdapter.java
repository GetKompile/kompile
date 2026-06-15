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

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Adapter that wraps an InputGuardrail as a Filter.
 * This provides backward compatibility for existing guardrails
 * within the new filter chain framework.
 */
public class GuardrailInputFilterAdapter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(GuardrailInputFilterAdapter.class);

    private final InputGuardrail guardrail;
    private final String id;
    private boolean enabled;

    public GuardrailInputFilterAdapter(InputGuardrail guardrail) {
        this.guardrail = guardrail;
        this.id = "guardrail-input-" + guardrail.getName().toLowerCase().replace(" ", "-");
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
        return "Input guardrail: " + guardrail.getName();
    }

    @Override
    public Set<FilterPhase> getApplicablePhases() {
        return EnumSet.of(FilterPhase.PRE_RETRIEVAL);
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
        log.debug("Executing input guardrail adapter: {}", guardrail.getName());

        // Build guardrail context from filter context
        GuardrailContext guardrailContext = buildGuardrailContext(context);

        // Execute the guardrail
        GuardrailResult result = guardrail.validate(context.getUserMessage(), guardrailContext);

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
            return new String[]{"guardrail", "input"};
        }
        String[] result = new String[categories.length + 2];
        result[0] = "guardrail";
        result[1] = "input";
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
                // Add warning trace but continue
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
                            "Request blocked by " + guardrail.getName(),
                    400
            );
            case WARN -> {
                context.addTrace(FilterTraceEntry.warning(id,
                        "Guardrail warning: " + result.getFailureReason()));
                yield FilterResult.continueWith(context);
            }
            case MODIFY -> {
                // Input guardrails typically don't modify, but handle if they do
                context.addTrace(FilterTraceEntry.info(id,
                        "Guardrail requested modification: " + result.getFailureReason()));
                yield FilterResult.continueWith(context);
            }
            case ESCALATE -> {
                // Escalate treated as warning for now
                context.addTrace(FilterTraceEntry.warning(id,
                        "Guardrail escalation: " + result.getFailureReason()));
                yield FilterResult.continueWith(context);
            }
            default -> FilterResult.continueWith(context);
        };
    }

    /**
     * Get the underlying guardrail.
     */
    public InputGuardrail getGuardrail() {
        return guardrail;
    }
}
