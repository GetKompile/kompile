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

/**
 * Defines the possible actions a filter can take after execution.
 * Uses HTTP-like semantics for clear, predictable behavior.
 */
public enum FilterAction {

    /**
     * Continue to the next filter in the chain.
     * The filter may have mutated the context, and those changes
     * will be passed downstream. Equivalent to HTTP 200.
     */
    CONTINUE,

    /**
     * Short-circuit the chain and return a successful response early.
     * Use when the filter can provide a complete response without
     * continuing through the rest of the pipeline.
     * Equivalent to HTTP 200 with early termination.
     */
    TERMINATE_SUCCESS,

    /**
     * Short-circuit the chain due to a user/request error.
     * The request violates a filter's rules or constraints
     * (e.g., content policy violation, invalid input).
     * Equivalent to HTTP 4xx.
     */
    TERMINATE_USER_ERROR,

    /**
     * Short-circuit the chain due to a fatal filter error.
     * An unexpected failure in the filter itself
     * (e.g., crash, misconfiguration, remote service unavailable).
     * Equivalent to HTTP 5xx.
     */
    TERMINATE_FATAL_ERROR
}
