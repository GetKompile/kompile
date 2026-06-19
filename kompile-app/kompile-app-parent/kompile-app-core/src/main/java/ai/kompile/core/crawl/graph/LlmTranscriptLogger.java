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
 * Abstraction for persisting LLM call transcripts during graph extraction.
 * Implemented in kompile-app-main where JobLogService is available.
 * Called by CrawlLlmDispatcher after each LLM call to create an auditable record
 * of the prompt sent and response received.
 */
public interface LlmTranscriptLogger {

    /**
     * Persist a single LLM call transcript.
     *
     * @param jobId      the crawl job identifier (used as taskId for log storage)
     * @param backendId  which backend handled the call (e.g. "default", "claude-cli", "openai-api")
     * @param taskType   the processing task type (e.g. "llm", "vlm", "embedding")
     * @param prompt     the full prompt text sent to the LLM
     * @param response   the full response text received (null if call failed)
     * @param latencyMs  wall-clock call duration in milliseconds
     * @param success    whether the call returned a usable response
     * @param errorMessage short error description if call failed (null on success)
     * @param agentSessionId the CLI agent chat session id for this call, or null if none
     */
    void logTranscript(String jobId, String backendId, String taskType,
                       String prompt, String response,
                       long latencyMs, boolean success, String errorMessage,
                       String agentSessionId);
}
