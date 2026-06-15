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

package ai.kompile.core.filter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of filter execution, following HTTP-like semantics.
 * <p>
 * A filter can:
 * <ul>
 *   <li>Continue with optional mutations (200 OK)</li>
 *   <li>Short-circuit with early success (200 OK with response)</li>
 *   <li>Terminate with user error (4xx)</li>
 *   <li>Terminate with fatal error (5xx)</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FilterResult {

    /**
     * The action to take after this filter.
     */
    @Builder.Default
    private FilterAction action = FilterAction.CONTINUE;

    /**
     * The (potentially mutated) context.
     */
    private FilterContext mutatedContext;

    /**
     * Message for the user (especially on termination).
     */
    private String message;

    /**
     * HTTP status code semantics:
     * - 200: Success
     * - 400-499: User error
     * - 500-599: Server/filter error
     */
    @Builder.Default
    private int httpStatusCode = 200;

    /**
     * Trace entries from this filter execution.
     */
    @Builder.Default
    private List<FilterTraceEntry> traces = new ArrayList<>();

    /**
     * Additional metadata about the result.
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * The ID of the filter that produced this result.
     */
    private String filterId;

    /**
     * Execution time in milliseconds.
     */
    private Long executionTimeMs;

    // ─────────────────────────────────────────────────────────────────────────────
    // FACTORY METHODS
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Continue to the next filter with the (optionally mutated) context.
     *
     * @param context The context to pass downstream
     * @return A continuing FilterResult
     */
    public static FilterResult continueWith(FilterContext context) {
        return FilterResult.builder()
                .action(FilterAction.CONTINUE)
                .mutatedContext(context)
                .httpStatusCode(200)
                .build();
    }

    /**
     * Continue with context and traces.
     */
    public static FilterResult continueWith(FilterContext context, List<FilterTraceEntry> traces) {
        return FilterResult.builder()
                .action(FilterAction.CONTINUE)
                .mutatedContext(context)
                .httpStatusCode(200)
                .traces(traces != null ? traces : new ArrayList<>())
                .build();
    }

    /**
     * Short-circuit with a successful response.
     * The chain stops here and returns this response to the caller.
     *
     * @param response The response message
     * @param metadata Additional response metadata
     * @return A terminating success FilterResult
     */
    public static FilterResult terminateSuccess(String response, Map<String, Object> metadata) {
        return FilterResult.builder()
                .action(FilterAction.TERMINATE_SUCCESS)
                .message(response)
                .httpStatusCode(200)
                .metadata(metadata != null ? metadata : new HashMap<>())
                .build();
    }

    /**
     * Short-circuit with a successful response.
     */
    public static FilterResult terminateSuccess(String response) {
        return terminateSuccess(response, null);
    }

    /**
     * Terminate with a user/request error.
     * The request violates filter rules (e.g., policy violation).
     *
     * @param reason The error reason
     * @param statusCode HTTP status code (400-499)
     * @return A user error FilterResult
     */
    public static FilterResult terminateUserError(String reason, int statusCode) {
        if (statusCode < 400 || statusCode >= 500) {
            statusCode = 400; // Default to Bad Request
        }
        return FilterResult.builder()
                .action(FilterAction.TERMINATE_USER_ERROR)
                .message(reason)
                .httpStatusCode(statusCode)
                .build();
    }

    /**
     * Terminate with a 400 Bad Request error.
     */
    public static FilterResult terminateUserError(String reason) {
        return terminateUserError(reason, 400);
    }

    /**
     * Terminate with a 403 Forbidden error.
     */
    public static FilterResult terminateForbidden(String reason) {
        return terminateUserError(reason, 403);
    }

    /**
     * Terminate with a 429 Too Many Requests error.
     */
    public static FilterResult terminateRateLimited(String reason) {
        return terminateUserError(reason, 429);
    }

    /**
     * Terminate with a fatal filter error.
     * An unexpected failure in the filter itself.
     *
     * @param reason The error reason
     * @return A fatal error FilterResult
     */
    public static FilterResult terminateFatalError(String reason) {
        return FilterResult.builder()
                .action(FilterAction.TERMINATE_FATAL_ERROR)
                .message(reason)
                .httpStatusCode(500)
                .build();
    }

    /**
     * Terminate with a specific 5xx status code.
     */
    public static FilterResult terminateFatalError(String reason, int statusCode) {
        if (statusCode < 500 || statusCode >= 600) {
            statusCode = 500;
        }
        return FilterResult.builder()
                .action(FilterAction.TERMINATE_FATAL_ERROR)
                .message(reason)
                .httpStatusCode(statusCode)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // HELPER METHODS
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Check if this result allows continuation.
     */
    public boolean shouldContinue() {
        return action == FilterAction.CONTINUE;
    }

    /**
     * Check if this result terminates the chain.
     */
    public boolean isTerminating() {
        return action != FilterAction.CONTINUE;
    }

    /**
     * Check if this is a user error (4xx).
     */
    public boolean isUserError() {
        return action == FilterAction.TERMINATE_USER_ERROR;
    }

    /**
     * Check if this is a fatal error (5xx).
     */
    public boolean isFatalError() {
        return action == FilterAction.TERMINATE_FATAL_ERROR;
    }

    /**
     * Check if this is any kind of error.
     */
    public boolean isError() {
        return isUserError() || isFatalError();
    }

    /**
     * Add execution metadata.
     */
    public FilterResult withExecutionTime(long timeMs) {
        this.executionTimeMs = timeMs;
        return this;
    }

    /**
     * Add filter ID.
     */
    public FilterResult withFilterId(String filterId) {
        this.filterId = filterId;
        return this;
    }

    /**
     * Add traces.
     */
    public FilterResult withTraces(List<FilterTraceEntry> traces) {
        this.traces = traces != null ? traces : new ArrayList<>();
        return this;
    }
}
