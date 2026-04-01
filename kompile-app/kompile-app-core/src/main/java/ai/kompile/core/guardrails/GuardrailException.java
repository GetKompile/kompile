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

package ai.kompile.core.guardrails;

import lombok.Getter;

/**
 * Exception thrown when a guardrail blocks a request.
 */
@Getter
public class GuardrailException extends RuntimeException {

    /**
     * The guardrail result that caused this exception.
     */
    private final GuardrailResult result;

    /**
     * Create a new GuardrailException.
     *
     * @param result The guardrail result
     */
    public GuardrailException(GuardrailResult result) {
        super(buildMessage(result));
        this.result = result;
    }

    /**
     * Create a new GuardrailException with a cause.
     *
     * @param result The guardrail result
     * @param cause The underlying cause
     */
    public GuardrailException(GuardrailResult result, Throwable cause) {
        super(buildMessage(result), cause);
        this.result = result;
    }

    private static String buildMessage(GuardrailResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Guardrail '").append(result.getGuardrailName()).append("' blocked request");
        if (result.getCategory() != null) {
            sb.append(" [").append(result.getCategory()).append("]");
        }
        if (result.getFailureReason() != null) {
            sb.append(": ").append(result.getFailureReason());
        }
        return sb.toString();
    }

    /**
     * Get the category of the violation.
     *
     * @return The category, or null if not set
     */
    public GuardrailCategory getCategory() {
        return result != null ? result.getCategory() : null;
    }

    /**
     * Get the guardrail name that blocked the request.
     *
     * @return The guardrail name
     */
    public String getGuardrailName() {
        return result != null ? result.getGuardrailName() : "unknown";
    }
}
