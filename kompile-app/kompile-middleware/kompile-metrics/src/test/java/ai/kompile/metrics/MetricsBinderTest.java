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

package ai.kompile.metrics;

import ai.kompile.core.rag.retrieval.RetrievalMetrics;
import ai.kompile.metrics.binder.ChatMetrics;
import ai.kompile.metrics.binder.CrawlMetrics;
import ai.kompile.metrics.binder.GuardrailMetrics;
import ai.kompile.metrics.binder.IngestMetrics;
import ai.kompile.metrics.binder.JobHistoryMetrics;
import ai.kompile.metrics.binder.KnowledgeGraphMetrics;
import ai.kompile.metrics.binder.LlmMetrics;
import ai.kompile.metrics.binder.McpToolMetrics;
import ai.kompile.metrics.binder.Nd4jMemoryMetrics;
import ai.kompile.metrics.binder.RetrievalObservabilityMetrics;
import ai.kompile.metrics.binder.VectorStoreMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for kompile metric binders using a SimpleMeterRegistry
 * (no Spring context or CUDA required).
 */
class MetricsBinderTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setup() {
        registry = new SimpleMeterRegistry();
    }

    @Test
    void ingestMetrics_recordDocumentIngested() {
        IngestMetrics metrics = new IngestMetrics(registry);
        metrics.bindMetrics();

        metrics.recordDocumentIngested("pdf", 150, 10, 50_000);
        metrics.recordDocumentIngested("txt", 30, 3, 2_000);

        Counter docCounter = registry.find("kompile.ingest.documents.total").counter();
        assertNotNull(docCounter);
        assertEquals(2.0, docCounter.count());

        Counter chunksCounter = registry.find("kompile.ingest.chunks.total").counter();
        assertNotNull(chunksCounter);
        assertEquals(13.0, chunksCounter.count());

        Counter bytesCounter = registry.find("kompile.ingest.bytes.total").counter();
        assertNotNull(bytesCounter);
        assertEquals(52_000.0, bytesCounter.count());
    }

    @Test
    void ingestMetrics_jobTracking() {
        IngestMetrics metrics = new IngestMetrics(registry);
        metrics.bindMetrics();

        metrics.recordJobStarted();
        metrics.recordJobStarted();
        Gauge activeGauge = registry.find("kompile.ingest.jobs.active").gauge();
        assertNotNull(activeGauge);
        assertEquals(2.0, activeGauge.value());

        metrics.recordJobCompleted(5000);
        assertEquals(1.0, activeGauge.value());

        Gauge lastDuration = registry.find("kompile.ingest.last_job_duration_ms").gauge();
        assertNotNull(lastDuration);
        assertEquals(5000.0, lastDuration.value());
    }

    @Test
    void llmMetrics_recordRequest() {
        LlmMetrics metrics = new LlmMetrics(registry);
        metrics.bindMetrics();

        metrics.recordRequest("openai", "gpt-4", 500, 100, 200);
        metrics.recordRequest("openai", "gpt-4", 300, 80, 150);

        Counter requestCounter = registry.find("kompile.llm.request.count")
                .tag("provider", "openai").tag("model", "gpt-4").counter();
        assertNotNull(requestCounter);
        assertEquals(2.0, requestCounter.count());

        Counter inputTokens = registry.find("kompile.llm.tokens.input")
                .tag("provider", "openai").counter();
        assertNotNull(inputTokens);
        assertEquals(180.0, inputTokens.count());

        Counter outputTokens = registry.find("kompile.llm.tokens.output")
                .tag("provider", "openai").counter();
        assertNotNull(outputTokens);
        assertEquals(350.0, outputTokens.count());
    }

    @Test
    void llmMetrics_recordError() {
        LlmMetrics metrics = new LlmMetrics(registry);
        metrics.bindMetrics();

        metrics.recordError("anthropic", "claude-3", "TimeoutException");

        Counter errorCounter = registry.find("kompile.llm.request.errors")
                .tag("error", "TimeoutException").counter();
        assertNotNull(errorCounter);
        assertEquals(1.0, errorCounter.count());
    }

    @Test
    void retrievalMetrics_recordRetrieval() {
        RetrievalObservabilityMetrics metrics = new RetrievalObservabilityMetrics(registry);
        metrics.bindMetrics();

        RetrievalMetrics rm = new RetrievalMetrics(5, 3, 1, 1_000_000, 2_000_000, 500_000, 4_000_000);
        metrics.recordRetrieval(rm);

        Counter count = registry.find("kompile.retrieval.count").counter();
        assertNotNull(count);
        assertEquals(1.0, count.count());

        Counter semanticHits = registry.find("kompile.retrieval.semantic.hits").counter();
        assertNotNull(semanticHits);
        assertEquals(5.0, semanticHits.count());

        Counter keywordHits = registry.find("kompile.retrieval.keyword.hits").counter();
        assertNotNull(keywordHits);
        assertEquals(3.0, keywordHits.count());

        Timer totalTimer = registry.find("kompile.retrieval.total.time").timer();
        assertNotNull(totalTimer);
        assertEquals(1, totalTimer.count());
    }

    @Test
    void crawlMetrics_recordCrawlLifecycle() {
        CrawlMetrics metrics = new CrawlMetrics(registry);
        metrics.bindMetrics();

        metrics.recordCrawlStarted("crawler-1");
        metrics.recordCrawlStarted("crawler-2");

        Gauge activeGauge = registry.find("kompile.crawl.jobs.active").gauge();
        assertNotNull(activeGauge);
        assertEquals(2.0, activeGauge.value());

        metrics.recordCrawlCompleted("crawler-1", 5000, 100, 90, 5, 5);
        assertEquals(1.0, activeGauge.value());

        Counter jobsCompleted = registry.find("kompile.crawl.jobs.completed").counter();
        assertNotNull(jobsCompleted);
        assertEquals(1.0, jobsCompleted.count());

        Counter urlsProcessed = registry.find("kompile.crawl.urls.processed").counter();
        assertNotNull(urlsProcessed);
        assertEquals(90.0, urlsProcessed.count());

        Counter urlsSkipped = registry.find("kompile.crawl.urls.skipped").counter();
        assertNotNull(urlsSkipped);
        assertEquals(5.0, urlsSkipped.count());

        Gauge lastDuration = registry.find("kompile.crawl.last_duration_ms").gauge();
        assertNotNull(lastDuration);
        assertEquals(5000.0, lastDuration.value());
    }

    @Test
    void jobHistoryMetrics_recordJobLifecycle() {
        JobHistoryMetrics metrics = new JobHistoryMetrics(registry);
        metrics.bindMetrics();

        metrics.recordJobStarted();
        metrics.recordJobStarted();

        Gauge running = registry.find("kompile.jobs.running").gauge();
        assertNotNull(running);
        assertEquals(2.0, running.value());

        metrics.recordJobCompleted(50, 200, 10000, 100, 300, 500, 200);
        assertEquals(1.0, running.value());

        Counter completed = registry.find("kompile.jobs.completed").counter();
        assertNotNull(completed);
        assertEquals(1.0, completed.count());

        Counter docsIndexed = registry.find("kompile.jobs.documents_indexed").counter();
        assertNotNull(docsIndexed);
        assertEquals(50.0, docsIndexed.count());

        Counter chunks = registry.find("kompile.jobs.chunks_created").counter();
        assertNotNull(chunks);
        assertEquals(200.0, chunks.count());

        Gauge lastLoading = registry.find("kompile.jobs.last.loading_ms").gauge();
        assertNotNull(lastLoading);
        assertEquals(100.0, lastLoading.value());

        metrics.recordJobFailed("OutOfMemory");
        assertEquals(0.0, running.value());

        Counter failed = registry.find("kompile.jobs.failed").counter();
        assertNotNull(failed);
        assertEquals(1.0, failed.count());
    }

    @Test
    void jobHistoryMetrics_restartTracking() {
        JobHistoryMetrics metrics = new JobHistoryMetrics(registry);
        metrics.bindMetrics();

        metrics.recordRestartAttempt(true);
        metrics.recordRestartAttempt(false);
        metrics.recordRestartAttempt(true);

        Counter attempts = registry.find("kompile.jobs.restart_attempts").counter();
        assertNotNull(attempts);
        assertEquals(3.0, attempts.count());

        Counter recovered = registry.find("kompile.jobs.restart_recovered").counter();
        assertNotNull(recovered);
        assertEquals(2.0, recovered.count());
    }

    @Test
    void chatMetrics_recordSessionsAndMessages() {
        ChatMetrics metrics = new ChatMetrics(registry);
        metrics.bindMetrics();

        metrics.recordSessionCreated();
        metrics.recordSessionCreated();

        Counter sessionsCreated = registry.find("kompile.chat.sessions.created").counter();
        assertNotNull(sessionsCreated);
        assertEquals(2.0, sessionsCreated.count());

        Gauge totalSessions = registry.find("kompile.chat.sessions.total").gauge();
        assertNotNull(totalSessions);
        assertEquals(2.0, totalSessions.value());

        metrics.recordMessageSent("user");
        metrics.recordMessageSent("user");
        metrics.recordMessageSent("assistant");

        Counter messages = registry.find("kompile.chat.messages.total").counter();
        assertNotNull(messages);
        assertEquals(3.0, messages.count());

        Counter userMessages = registry.find("kompile.chat.messages.total.by_source")
                .tag("source", "user").counter();
        assertNotNull(userMessages);
        assertEquals(2.0, userMessages.count());
    }

    @Test
    void knowledgeGraphMetrics_extractionAndQuery() {
        KnowledgeGraphMetrics metrics = new KnowledgeGraphMetrics(registry);
        metrics.bindMetrics();

        metrics.recordExtractionStarted();
        metrics.recordExtractionCompleted(2000, 15, 8);

        Counter jobsTotal = registry.find("kompile.graph.extraction.jobs.total").counter();
        assertNotNull(jobsTotal);
        assertEquals(1.0, jobsTotal.count());

        Counter completed = registry.find("kompile.graph.extraction.jobs.completed").counter();
        assertNotNull(completed);
        assertEquals(1.0, completed.count());

        Counter entities = registry.find("kompile.graph.entities.extracted").counter();
        assertNotNull(entities);
        assertEquals(15.0, entities.count());

        Counter relationships = registry.find("kompile.graph.relationships.extracted").counter();
        assertNotNull(relationships);
        assertEquals(8.0, relationships.count());

        Timer extractionTimer = registry.find("kompile.graph.extraction.time").timer();
        assertNotNull(extractionTimer);
        assertEquals(1, extractionTimer.count());

        metrics.setTotalNodes(100);
        metrics.setTotalEdges(250);

        Gauge nodes = registry.find("kompile.graph.nodes.total").gauge();
        assertNotNull(nodes);
        assertEquals(100.0, nodes.value());

        Gauge edges = registry.find("kompile.graph.edges.total").gauge();
        assertNotNull(edges);
        assertEquals(250.0, edges.value());

        metrics.recordQuery(50);
        Counter queries = registry.find("kompile.graph.queries.total").counter();
        assertNotNull(queries);
        assertEquals(1.0, queries.count());
    }

    @Test
    void guardrailMetrics_inputOutputValidation() {
        GuardrailMetrics metrics = new GuardrailMetrics(registry);
        metrics.bindMetrics();

        metrics.recordInputValidation("passed", 10);
        metrics.recordInputValidation("blocked", 15);
        metrics.recordInputValidation("warned", 5);
        metrics.recordOutputValidation("passed", 20);

        Counter inputTotal = registry.find("kompile.guardrail.input.total").counter();
        assertNotNull(inputTotal);
        assertEquals(3.0, inputTotal.count());

        Counter inputBlocked = registry.find("kompile.guardrail.input.blocked").counter();
        assertNotNull(inputBlocked);
        assertEquals(1.0, inputBlocked.count());

        Counter inputWarned = registry.find("kompile.guardrail.input.warned").counter();
        assertNotNull(inputWarned);
        assertEquals(1.0, inputWarned.count());

        Counter outputTotal = registry.find("kompile.guardrail.output.total").counter();
        assertNotNull(outputTotal);
        assertEquals(1.0, outputTotal.count());

        Counter outputPassed = registry.find("kompile.guardrail.output.passed").counter();
        assertNotNull(outputPassed);
        assertEquals(1.0, outputPassed.count());
    }

    @Test
    void guardrailMetrics_byTypeTagging() {
        GuardrailMetrics metrics = new GuardrailMetrics(registry);
        metrics.bindMetrics();

        metrics.recordInputValidation("toxicity", "blocked", 25);
        metrics.recordInputValidation("pii", "warned", 10);

        Counter toxicityBlocked = registry.find("kompile.guardrail.input.by_type")
                .tag("type", "toxicity").tag("result", "blocked").counter();
        assertNotNull(toxicityBlocked);
        assertEquals(1.0, toxicityBlocked.count());

        // Total counters should also be updated
        Counter inputTotal = registry.find("kompile.guardrail.input.total").counter();
        assertNotNull(inputTotal);
        assertEquals(2.0, inputTotal.count());
    }

    @Test
    void mcpToolMetrics_recordToolCalls() {
        McpToolMetrics metrics = new McpToolMetrics(registry);
        metrics.bindMetrics();

        metrics.recordToolCall("rag_search", true, 100);
        metrics.recordToolCall("rag_search", false, 200);
        metrics.recordToolCall("file_read", true, 50);

        Counter total = registry.find("kompile.mcp.tool.calls.total").counter();
        assertNotNull(total);
        assertEquals(3.0, total.count());

        Counter success = registry.find("kompile.mcp.tool.calls.success").counter();
        assertNotNull(success);
        assertEquals(2.0, success.count());

        Counter failed = registry.find("kompile.mcp.tool.calls.failed").counter();
        assertNotNull(failed);
        assertEquals(1.0, failed.count());

        Counter ragSearchSuccess = registry.find("kompile.mcp.tool.calls.by_tool")
                .tag("tool", "rag_search").tag("result", "success").counter();
        assertNotNull(ragSearchSuccess);
        assertEquals(1.0, ragSearchSuccess.count());

        Counter ragSearchFailure = registry.find("kompile.mcp.tool.calls.by_tool")
                .tag("tool", "rag_search").tag("result", "failure").counter();
        assertNotNull(ragSearchFailure);
        assertEquals(1.0, ragSearchFailure.count());

        Timer timer = registry.find("kompile.mcp.tool.time").timer();
        assertNotNull(timer);
        assertEquals(3, timer.count());
    }

    @Test
    void mcpToolMetrics_byActionTagging() {
        McpToolMetrics metrics = new McpToolMetrics(registry);
        metrics.bindMetrics();

        metrics.recordToolCall("filesystem", "read", true, 30);
        metrics.recordToolCall("filesystem", "write", true, 50);

        Counter readAction = registry.find("kompile.mcp.tool.calls.by_action")
                .tag("tool", "filesystem").tag("action", "read").counter();
        assertNotNull(readAction);
        assertEquals(1.0, readAction.count());

        Counter writeAction = registry.find("kompile.mcp.tool.calls.by_action")
                .tag("tool", "filesystem").tag("action", "write").counter();
        assertNotNull(writeAction);
        assertEquals(1.0, writeAction.count());
    }

    @Test
    void nd4jMemoryMetrics_jvmGaugesRegistered() {
        Nd4jMemoryMetrics metrics = new Nd4jMemoryMetrics(registry);
        metrics.bindMetrics();

        Gauge heapUsed = registry.find("kompile.memory.jvm.heap.used").gauge();
        assertNotNull(heapUsed);
        assertTrue(heapUsed.value() > 0, "JVM heap used should be > 0");

        Gauge heapMax = registry.find("kompile.memory.jvm.heap.max").gauge();
        assertNotNull(heapMax);
        assertTrue(heapMax.value() > 0, "JVM heap max should be > 0");

        Gauge heapUtil = registry.find("kompile.memory.jvm.heap.utilization").gauge();
        assertNotNull(heapUtil);
        assertTrue(heapUtil.value() > 0.0 && heapUtil.value() <= 1.0,
                "Heap utilization should be 0..1, got: " + heapUtil.value());

        Gauge nonHeap = registry.find("kompile.memory.jvm.nonheap.used").gauge();
        assertNotNull(nonHeap);
        assertTrue(nonHeap.value() > 0, "Non-heap used should be > 0");
    }
}
