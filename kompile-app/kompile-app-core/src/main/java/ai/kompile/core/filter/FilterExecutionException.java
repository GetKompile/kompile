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
 * Exception thrown when a filter execution fails.
 * This can be used when a filter terminates the request due to policy violations
 * or when an unexpected error occurs during filter execution.
 */
public class FilterExecutionException extends RuntimeException {

    private final String filterId;
    private final FilterAction action;
    private final int httpStatusCode;
    private final FilterResult filterResult;

    /**
     * Create an exception from a filter result.
     *
     * @param result The filter result that caused the exception
     */
    public FilterExecutionException(FilterResult result) {
        super(result.getMessage());
        this.filterId = result.getFilterId();
        this.action = result.getAction();
        this.httpStatusCode = result.getHttpStatusCode();
        this.filterResult = result;
    }

    /**
     * Create an exception with a message and filter ID.
     *
     * @param filterId The filter that caused the exception
     * @param message The error message
     * @param httpStatusCode The HTTP status code
     */
    public FilterExecutionException(String filterId, String message, int httpStatusCode) {
        super(message);
        this.filterId = filterId;
        this.action = httpStatusCode >= 500 ? FilterAction.TERMINATE_FATAL_ERROR : FilterAction.TERMINATE_USER_ERROR;
        this.httpStatusCode = httpStatusCode;
        this.filterResult = null;
    }

    /**
     * Create a user error exception (4xx).
     *
     * @param filterId The filter that caused the exception
     * @param message The error message
     */
    public static FilterExecutionException userError(String filterId, String message) {
        return new FilterExecutionException(filterId, message, 400);
    }

    /**
     * Create a forbidden exception (403).
     *
     * @param filterId The filter that caused the exception
     * @param message The error message
     */
    public static FilterExecutionException forbidden(String filterId, String message) {
        return new FilterExecutionException(filterId, message, 403);
    }

    /**
     * Create a fatal error exception (500).
     *
     * @param filterId The filter that caused the exception
     * @param message The error message
     */
    public static FilterExecutionException fatalError(String filterId, String message) {
        return new FilterExecutionException(filterId, message, 500);
    }

    /**
     * Create a fatal error exception with cause.
     *
     * @param filterId The filter that caused the exception
     * @param message The error message
     * @param cause The underlying cause
     */
    public static FilterExecutionException fatalError(String filterId, String message, Throwable cause) {
        FilterExecutionException ex = new FilterExecutionException(filterId, message, 500);
        ex.initCause(cause);
        return ex;
    }

    public String getFilterId() {
        return filterId;
    }

    public FilterAction getAction() {
        return action;
    }

    public int getHttpStatusCode() {
        return httpStatusCode;
    }

    public FilterResult getFilterResult() {
        return filterResult;
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
     * Convert to a FilterResult.
     */
    public FilterResult toFilterResult() {
        if (filterResult != null) {
            return filterResult;
        }
        return isFatalError()
                ? FilterResult.terminateFatalError(getMessage())
                : FilterResult.terminateUserError(getMessage(), httpStatusCode);
    }
}
