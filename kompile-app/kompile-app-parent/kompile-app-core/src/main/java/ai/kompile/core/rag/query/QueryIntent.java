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

package ai.kompile.core.rag.query;

/**
 * Represents the detected intent/type of a user query.
 * Used to adjust retrieval and response strategies.
 */
public enum QueryIntent {
    /**
     * User is asking a factual question.
     */
    QUESTION,

    /**
     * User is asking for clarification about something previously discussed.
     */
    CLARIFICATION,

    /**
     * User is following up on a previous topic (e.g., "tell me more about that").
     */
    FOLLOW_UP,

    /**
     * User wants to compare two or more items.
     */
    COMPARISON,

    /**
     * User wants a summary of information.
     */
    SUMMARIZATION,

    /**
     * User is requesting an action be performed.
     */
    ACTION_REQUEST,

    /**
     * General conversation or greeting.
     */
    CONVERSATIONAL,

    /**
     * Intent could not be determined.
     */
    UNKNOWN
}
