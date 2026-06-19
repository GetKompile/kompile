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

package ai.kompile.core.crawl.graph;

/**
 * Thread-scoped carrier for the CLI agent chat SESSION ID and crawl JOB ID of the LLM call
 * running on the current thread.
 *
 * <p>CLI coding agents report a chat session id we want to surface alongside the extraction
 * transcript (claude-cli emits a {@code system/init} event with {@code session_id}; opencode emits
 * its own session field). The Spring AI {@code LLMChat} interface, however, only returns the
 * response text — there is no place to return side-band metadata. The agent adapter therefore
 * publishes the session id here on the thread that actually executes the call, and the dispatcher
 * reads it back on that same thread immediately after the call completes, attaching it to the
 * persisted transcript.</p>
 *
 * <p>The job id is published by {@link ai.kompile.crawl.graph.GraphExtractionOrchestrator} on each
 * extraction worker thread so that {@link ai.kompile.knowledgegraph.matrix.service.MatrixGraphConstructor}
 * can attach the correct crawl job to every transcript it logs without requiring an extra method
 * parameter threaded through the {@code GraphConstructor} interface.</p>
 *
 * <p>Because LLM calls run on a shared timeout executor, callers MUST {@link #clear()} after
 * reading so a session id / job id never leaks into the next task that reuses the pooled thread.</p>
 */
public final class AgentCallContext {

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> JOB_ID = new ThreadLocal<>();

    private AgentCallContext() {
    }

    /** Publish the session id for the current thread (a blank/null value clears it). */
    public static void setSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            SESSION_ID.remove();
        } else {
            SESSION_ID.set(sessionId);
        }
    }

    /** The session id published on the current thread, or {@code null} if none. */
    public static String getSessionId() {
        return SESSION_ID.get();
    }

    /**
     * Publish the crawl job id for the current thread (a blank/null value clears it).
     *
     * <p>Called by {@code GraphExtractionOrchestrator} on each worker thread immediately before
     * invoking {@code GraphConstructor.constructGraphFromDocs} so the constructor can attach the
     * job id to every transcript it logs.</p>
     */
    public static void setJobId(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            JOB_ID.remove();
        } else {
            JOB_ID.set(jobId);
        }
    }

    /** The crawl job id published on the current thread, or {@code null} if none. */
    public static String getJobId() {
        return JOB_ID.get();
    }

    /** Remove any session id and job id bound to the current thread. */
    public static void clear() {
        SESSION_ID.remove();
        JOB_ID.remove();
    }
}
