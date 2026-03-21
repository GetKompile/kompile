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

package ai.kompile.core.rag;

import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.core.filter.FilterMutation;
import ai.kompile.core.filter.FilterTraceEntry;
import ai.kompile.core.rag.query.ProcessedQuery;
import ai.kompile.core.rag.retrieval.RetrievalMetrics;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Result of a conversational RAG operation.
 * <p>
 * Contains the LLM-generated answer, retrieved documents, query metadata,
 * and conversation context.
 *
 * @param answer The LLM-generated response
 * @param retrievedDocuments Documents used to generate the response
 * @param formattedContext The context string provided to the LLM
 * @param conversationHistory Updated conversation history after this interaction
 * @param processedQuery Query processing metadata (original, rewritten, intent)
 * @param retrievalMetrics Metrics from the retrieval operation
 * @param generationTimeMs Time spent on LLM generation in milliseconds
 * @param totalTimeMs Total operation time in milliseconds
 * @param filterTraces Trace entries from filter chain execution
 * @param filterMutations Mutations made by filters during processing
 * @param filterTerminated Whether the filter chain terminated early
 * @param filterTerminationStatus HTTP status code if filter terminated early
 * @param error Whether this result represents an error
 * @param errorMessage Error message if error is true
 * @param errorCode HTTP error code if error is true
 */
public record ConversationalRagResult(
    String answer,
    List<ScoredDocument> retrievedDocuments,
    String formattedContext,
    List<Message> conversationHistory,
    ProcessedQuery processedQuery,
    RetrievalMetrics retrievalMetrics,
    long generationTimeMs,
    long totalTimeMs,
    List<FilterTraceEntry> filterTraces,
    List<FilterMutation> filterMutations,
    boolean filterTerminated,
    int filterTerminationStatus,
    boolean error,
    String errorMessage,
    int errorCode
) {

    /**
     * Creates an error result.
     */
    public static ConversationalRagResult error(String errorMessage) {
        return new ConversationalRagResult(
            "Error: " + errorMessage,
            List.of(),
            "",
            List.of(),
            null,
            RetrievalMetrics.empty(),
            0,
            0,
            List.of(),  // filterTraces
            List.of(),  // filterMutations
            false,      // filterTerminated
            0,          // filterTerminationStatus
            true,       // error
            errorMessage, // errorMessage
            500         // errorCode
        );
    }

    /**
     * Creates an empty result.
     */
    public static ConversationalRagResult empty() {
        return new ConversationalRagResult(
            "",
            List.of(),
            "",
            List.of(),
            null,
            RetrievalMetrics.empty(),
            0,
            0,
            List.of(),  // filterTraces
            List.of(),  // filterMutations
            false,      // filterTerminated
            0,          // filterTerminationStatus
            false,      // error
            null,       // errorMessage
            0           // errorCode
        );
    }

    /**
     * Returns true if this result represents an error.
     */
    public boolean isError() {
        return error || (answer != null && answer.startsWith("Error:"));
    }

    /**
     * Returns true if the filter chain terminated early.
     */
    public boolean wasFilterTerminated() {
        return filterTerminated;
    }

    /**
     * Returns true if filters made any mutations.
     */
    public boolean hasFilterMutations() {
        return filterMutations != null && !filterMutations.isEmpty();
    }

    /**
     * Returns true if there are filter traces.
     */
    public boolean hasFilterTraces() {
        return filterTraces != null && !filterTraces.isEmpty();
    }

    /**
     * Returns true if documents were retrieved.
     */
    public boolean hasRetrievedDocuments() {
        return retrievedDocuments != null && !retrievedDocuments.isEmpty();
    }

    /**
     * Returns the number of retrieved documents.
     */
    public int documentCount() {
        return retrievedDocuments != null ? retrievedDocuments.size() : 0;
    }

    /**
     * Returns true if the query was rewritten during processing.
     */
    public boolean wasQueryRewritten() {
        return processedQuery != null && processedQuery.wasRewritten();
    }

    /**
     * Gets the original user query.
     */
    public String getOriginalQuery() {
        return processedQuery != null ? processedQuery.originalQuery() : null;
    }

    /**
     * Gets the rewritten query used for retrieval.
     */
    public String getRewrittenQuery() {
        return processedQuery != null ? processedQuery.rewrittenQuery() : null;
    }

    /**
     * Converts to legacy RagResult for backward compatibility.
     */
    public RagResult toRagResult() {
        return new RagResult(
            answer,
            formattedContext,
            retrievedDocuments != null
                ? retrievedDocuments.stream()
                    .map(sd -> new ai.kompile.core.retrievers.RetrievedDoc(
                        sd.getId(),
                        sd.getText(),
                        sd.getMetadata(),
                        sd.score()))
                    .toList()
                : List.of()
        );
    }

    /**
     * Builder for constructing ConversationalRagResult instances.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String answer = "";
        private List<ScoredDocument> retrievedDocuments = List.of();
        private String formattedContext = "";
        private List<Message> conversationHistory = List.of();
        private ProcessedQuery processedQuery;
        private RetrievalMetrics retrievalMetrics = RetrievalMetrics.empty();
        private long generationTimeMs = 0;
        private long totalTimeMs = 0;
        private List<FilterTraceEntry> filterTraces = List.of();
        private List<FilterMutation> filterMutations = List.of();
        private boolean filterTerminated = false;
        private int filterTerminationStatus = 0;
        private boolean error = false;
        private String errorMessage = null;
        private int errorCode = 0;

        public Builder answer(String answer) {
            this.answer = answer != null ? answer : "";
            return this;
        }

        public Builder retrievedDocuments(List<ScoredDocument> documents) {
            this.retrievedDocuments = documents != null ? documents : List.of();
            return this;
        }

        public Builder formattedContext(String context) {
            this.formattedContext = context != null ? context : "";
            return this;
        }

        public Builder conversationHistory(List<Message> history) {
            this.conversationHistory = history != null ? history : List.of();
            return this;
        }

        public Builder processedQuery(ProcessedQuery query) {
            this.processedQuery = query;
            return this;
        }

        public Builder retrievalMetrics(RetrievalMetrics metrics) {
            this.retrievalMetrics = metrics != null ? metrics : RetrievalMetrics.empty();
            return this;
        }

        public Builder generationTimeMs(long ms) {
            this.generationTimeMs = ms;
            return this;
        }

        public Builder totalTimeMs(long ms) {
            this.totalTimeMs = ms;
            return this;
        }

        public Builder filterTraces(List<FilterTraceEntry> traces) {
            this.filterTraces = traces != null ? traces : List.of();
            return this;
        }

        public Builder filterMutations(List<FilterMutation> mutations) {
            this.filterMutations = mutations != null ? mutations : List.of();
            return this;
        }

        public Builder filterTerminated(boolean terminated) {
            this.filterTerminated = terminated;
            return this;
        }

        public Builder filterTerminationStatus(int status) {
            this.filterTerminationStatus = status;
            return this;
        }

        public Builder error(boolean error) {
            this.error = error;
            return this;
        }

        public Builder errorMessage(String message) {
            this.errorMessage = message;
            return this;
        }

        public Builder errorCode(int code) {
            this.errorCode = code;
            return this;
        }

        public ConversationalRagResult build() {
            return new ConversationalRagResult(
                answer,
                retrievedDocuments,
                formattedContext,
                conversationHistory,
                processedQuery,
                retrievalMetrics,
                generationTimeMs,
                totalTimeMs,
                filterTraces,
                filterMutations,
                filterTerminated,
                filterTerminationStatus,
                error,
                errorMessage,
                errorCode
            );
        }
    }
}
