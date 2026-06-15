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

package ai.kompile.metrics.controller;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.search.Search;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * REST endpoint that returns a dashboard-friendly summary of all Kompile metrics.
 * This is complementary to the Prometheus scrape endpoint at {@code /actuator/prometheus}
 * and provides a JSON view optimized for the Kompile UI.
 */
@RestController
@RequestMapping("/api/metrics")
@ConditionalOnBean(MeterRegistry.class)
public class MetricsSummaryController {

    private final MeterRegistry registry;

    public MetricsSummaryController(MeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/summary")
    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("uptime_seconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0);

        summary.put("embedding", embeddingSummary());
        summary.put("vectorstore", vectorStoreSummary());
        summary.put("retrieval", retrievalSummary());
        summary.put("llm", llmSummary());
        summary.put("ingest", ingestSummary());
        summary.put("memory", memorySummary());
        summary.put("crawl", crawlSummary());
        summary.put("jobs", jobsSummary());
        summary.put("chat", chatSummary());
        summary.put("graph", graphSummary());
        summary.put("guardrails", guardrailsSummary());
        summary.put("mcp", mcpSummary());

        return summary;
    }

    @GetMapping("/embedding")
    public Map<String, Object> embeddingSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimensions", gaugeValue("kompile.embedding.dimensions"));
        result.put("initialized", gaugeValue("kompile.embedding.initialized") > 0);
        result.put("loading", gaugeValue("kompile.embedding.loading") > 0);
        result.put("optimal_batch_size", gaugeValue("kompile.embedding.optimal_batch_size"));

        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("size", gaugeValue("kompile.embedding.batch.size"));
        batch.put("max_seq_length", gaugeValue("kompile.embedding.batch.max_seq_length"));
        batch.put("total_tokens", gaugeValue("kompile.embedding.batch.total_tokens"));
        batch.put("tokenize_time_ms", gaugeValue("kompile.embedding.batch.tokenize_time_ms"));
        batch.put("forward_pass_time_ms", gaugeValue("kompile.embedding.batch.forward_pass_time_ms"));
        batch.put("total_time_ms", gaugeValue("kompile.embedding.batch.total_time_ms"));
        batch.put("tokens_per_second", gaugeValue("kompile.embedding.batch.tokens_per_second"));
        batch.put("chunks_per_second", gaugeValue("kompile.embedding.batch.chunks_per_second"));
        result.put("last_batch", batch);

        return result;
    }

    @GetMapping("/vectorstore")
    public Map<String, Object> vectorStoreSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document_count", gaugeValue("kompile.vectorstore.document_count"));
        result.put("available", gaugeValue("kompile.vectorstore.available") > 0);
        result.put("fallback_index", gaugeValue("kompile.vectorstore.fallback") > 0);
        result.put("search_count", counterValue("kompile.vectorstore.search.count"));
        result.put("search_hits", counterValue("kompile.vectorstore.search.hits"));
        result.put("search_avg_time_ms", timerMean("kompile.vectorstore.search.time"));
        result.put("add_count", counterValue("kompile.vectorstore.add.count"));
        result.put("add_documents", counterValue("kompile.vectorstore.add.documents"));
        result.put("add_avg_time_ms", timerMean("kompile.vectorstore.add.time"));
        result.put("delete_count", counterValue("kompile.vectorstore.delete.count"));
        return result;
    }

    @GetMapping("/retrieval")
    public Map<String, Object> retrievalSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_count", counterValue("kompile.retrieval.count"));
        result.put("semantic_hits", counterValue("kompile.retrieval.semantic.hits"));
        result.put("keyword_hits", counterValue("kompile.retrieval.keyword.hits"));
        result.put("duplicates_removed", counterValue("kompile.retrieval.duplicates_removed"));
        result.put("embedding_avg_time_ms", timerMean("kompile.retrieval.embedding.time"));
        result.put("semantic_avg_time_ms", timerMean("kompile.retrieval.semantic.time"));
        result.put("keyword_avg_time_ms", timerMean("kompile.retrieval.keyword.time"));
        result.put("total_avg_time_ms", timerMean("kompile.retrieval.total.time"));
        return result;
    }

    @GetMapping("/llm")
    public Map<String, Object> llmSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("request_count", counterValue("kompile.llm.request.count"));
        result.put("error_count", counterValue("kompile.llm.request.errors"));
        result.put("avg_latency_ms", timerMean("kompile.llm.request.time"));
        result.put("input_tokens_total", counterValue("kompile.llm.tokens.input"));
        result.put("output_tokens_total", counterValue("kompile.llm.tokens.output"));
        return result;
    }

    @GetMapping("/ingest")
    public Map<String, Object> ingestSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documents_total", counterValue("kompile.ingest.documents.total"));
        result.put("documents_failed", counterValue("kompile.ingest.documents.failed"));
        result.put("chunks_total", counterValue("kompile.ingest.chunks.total"));
        result.put("bytes_total", counterValue("kompile.ingest.bytes.total"));
        result.put("jobs_active", gaugeValue("kompile.ingest.jobs.active"));
        result.put("jobs_queued", gaugeValue("kompile.ingest.jobs.queued"));
        result.put("avg_document_time_ms", timerMean("kompile.ingest.document.time"));
        result.put("avg_job_time_ms", timerMean("kompile.ingest.job.time"));
        result.put("last_job_duration_ms", gaugeValue("kompile.ingest.last_job_duration_ms"));
        return result;
    }

    @GetMapping("/crawl")
    public Map<String, Object> crawlSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobs_total", counterValue("kompile.crawl.jobs.total"));
        result.put("jobs_completed", counterValue("kompile.crawl.jobs.completed"));
        result.put("jobs_failed", counterValue("kompile.crawl.jobs.failed"));
        result.put("jobs_cancelled", counterValue("kompile.crawl.jobs.cancelled"));
        result.put("jobs_active", gaugeValue("kompile.crawl.jobs.active"));
        result.put("urls_discovered", counterValue("kompile.crawl.urls.discovered"));
        result.put("urls_processed", counterValue("kompile.crawl.urls.processed"));
        result.put("urls_failed", counterValue("kompile.crawl.urls.failed"));
        result.put("urls_skipped", counterValue("kompile.crawl.urls.skipped"));
        result.put("current_depth", gaugeValue("kompile.crawl.current.depth"));
        result.put("last_duration_ms", gaugeValue("kompile.crawl.last_duration_ms"));
        result.put("avg_job_time_ms", timerMean("kompile.crawl.job.time"));
        result.put("avg_url_time_ms", timerMean("kompile.crawl.url.time"));
        return result;
    }

    @GetMapping("/jobs")
    public Map<String, Object> jobsSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("running", gaugeValue("kompile.jobs.running"));
        result.put("completed", counterValue("kompile.jobs.completed"));
        result.put("failed", counterValue("kompile.jobs.failed"));
        result.put("cancelled", counterValue("kompile.jobs.cancelled"));
        result.put("memory_killed", counterValue("kompile.jobs.memory_killed"));
        result.put("restart_attempts", counterValue("kompile.jobs.restart_attempts"));
        result.put("restart_recovered", counterValue("kompile.jobs.restart_recovered"));
        result.put("documents_indexed", counterValue("kompile.jobs.documents_indexed"));
        result.put("chunks_created", counterValue("kompile.jobs.chunks_created"));
        result.put("tokens_processed", counterValue("kompile.jobs.tokens_processed"));
        result.put("events_total", counterValue("kompile.events.total"));
        result.put("events_errors", counterValue("kompile.events.errors"));

        Map<String, Object> lastPhase = new LinkedHashMap<>();
        lastPhase.put("loading_ms", gaugeValue("kompile.jobs.last.loading_ms"));
        lastPhase.put("chunking_ms", gaugeValue("kompile.jobs.last.chunking_ms"));
        lastPhase.put("embedding_ms", gaugeValue("kompile.jobs.last.embedding_ms"));
        lastPhase.put("indexing_ms", gaugeValue("kompile.jobs.last.indexing_ms"));
        result.put("last_phase_durations", lastPhase);

        Map<String, Object> avgPhase = new LinkedHashMap<>();
        avgPhase.put("avg_loading_ms", timerMean("kompile.jobs.loading.time"));
        avgPhase.put("avg_chunking_ms", timerMean("kompile.jobs.chunking.time"));
        avgPhase.put("avg_embedding_ms", timerMean("kompile.jobs.embedding.time"));
        avgPhase.put("avg_indexing_ms", timerMean("kompile.jobs.indexing.time"));
        result.put("avg_phase_durations", avgPhase);

        return result;
    }

    @GetMapping("/chat")
    public Map<String, Object> chatSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessions_created", counterValue("kompile.chat.sessions.created"));
        result.put("sessions_total", gaugeValue("kompile.chat.sessions.total"));
        result.put("messages_total", counterValue("kompile.chat.messages.total"));
        return result;
    }

    @GetMapping("/graph")
    public Map<String, Object> graphSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes_total", gaugeValue("kompile.graph.nodes.total"));
        result.put("edges_total", gaugeValue("kompile.graph.edges.total"));
        result.put("communities_total", gaugeValue("kompile.graph.communities.total"));
        result.put("entities_extracted", counterValue("kompile.graph.entities.extracted"));
        result.put("relationships_extracted", counterValue("kompile.graph.relationships.extracted"));
        result.put("communities_detected", counterValue("kompile.graph.communities.detected"));
        result.put("extraction_jobs_total", counterValue("kompile.graph.extraction.jobs.total"));
        result.put("extraction_jobs_completed", counterValue("kompile.graph.extraction.jobs.completed"));
        result.put("extraction_jobs_failed", counterValue("kompile.graph.extraction.jobs.failed"));
        result.put("queries_total", counterValue("kompile.graph.queries.total"));
        result.put("avg_extraction_time_ms", timerMean("kompile.graph.extraction.time"));
        result.put("avg_query_time_ms", timerMean("kompile.graph.query.time"));
        return result;
    }

    @GetMapping("/guardrails")
    public Map<String, Object> guardrailsSummary() {
        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("total", counterValue("kompile.guardrail.input.total"));
        input.put("passed", counterValue("kompile.guardrail.input.passed"));
        input.put("blocked", counterValue("kompile.guardrail.input.blocked"));
        input.put("warned", counterValue("kompile.guardrail.input.warned"));
        input.put("avg_time_ms", timerMean("kompile.guardrail.input.time"));
        result.put("input", input);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("total", counterValue("kompile.guardrail.output.total"));
        output.put("passed", counterValue("kompile.guardrail.output.passed"));
        output.put("blocked", counterValue("kompile.guardrail.output.blocked"));
        output.put("warned", counterValue("kompile.guardrail.output.warned"));
        output.put("avg_time_ms", timerMean("kompile.guardrail.output.time"));
        result.put("output", output);

        return result;
    }

    @GetMapping("/mcp")
    public Map<String, Object> mcpSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("calls_total", counterValue("kompile.mcp.tool.calls.total"));
        result.put("calls_success", counterValue("kompile.mcp.tool.calls.success"));
        result.put("calls_failed", counterValue("kompile.mcp.tool.calls.failed"));
        result.put("avg_call_time_ms", timerMean("kompile.mcp.tool.time"));
        return result;
    }

    @GetMapping("/memory")
    public Map<String, Object> memorySummary() {
        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("heap_used_bytes", gaugeValue("kompile.memory.jvm.heap.used"));
        jvm.put("heap_max_bytes", gaugeValue("kompile.memory.jvm.heap.max"));
        jvm.put("heap_utilization", gaugeValue("kompile.memory.jvm.heap.utilization"));
        jvm.put("nonheap_used_bytes", gaugeValue("kompile.memory.jvm.nonheap.used"));
        result.put("jvm", jvm);

        Map<String, Object> nd4j = new LinkedHashMap<>();
        nd4j.put("bytes_used", gaugeValue("kompile.memory.nd4j.bytes_used"));
        nd4j.put("bytes_max", gaugeValue("kompile.memory.nd4j.bytes_max"));
        nd4j.put("physical_bytes", gaugeValue("kompile.memory.nd4j.physical_bytes"));
        result.put("nd4j", nd4j);

        Map<String, Object> gpu = new LinkedHashMap<>();
        gpu.put("free_bytes", gaugeValue("kompile.memory.gpu.free_bytes"));
        gpu.put("total_bytes", gaugeValue("kompile.memory.gpu.total_bytes"));
        gpu.put("used_bytes", gaugeValue("kompile.memory.gpu.used_bytes"));
        gpu.put("utilization", gaugeValue("kompile.memory.gpu.utilization"));
        result.put("gpu", gpu);

        return result;
    }

    private double gaugeValue(String name) {
        Gauge gauge = registry.find(name).gauge();
        return gauge != null ? gauge.value() : 0.0;
    }

    private double counterValue(String name) {
        Counter counter = registry.find(name).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private double timerMean(String name) {
        Timer timer = registry.find(name).timer();
        if (timer == null || timer.count() == 0) return 0.0;
        return timer.mean(TimeUnit.MILLISECONDS);
    }
}
