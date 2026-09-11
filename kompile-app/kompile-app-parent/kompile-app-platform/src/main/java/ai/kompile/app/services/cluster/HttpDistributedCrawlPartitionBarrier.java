/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.services.cluster;

import ai.kompile.core.crawl.graph.DistributedCrawlPartitionBarrier;
import ai.kompile.core.crawl.graph.DistributedGraphExecution;
import ai.kompile.core.crawl.graph.DistributedGraphRuntimeContext;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Authenticated polling barrier client used by delegated crawl workers. */
@Component
public class HttpDistributedCrawlPartitionBarrier implements DistributedCrawlPartitionBarrier {

    private static final Duration TIMEOUT = Duration.ofMinutes(30);
    private final ObjectMapper mapper;
    private final HttpClient client;

    @Autowired
    public HttpDistributedCrawlPartitionBarrier(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    HttpDistributedCrawlPartitionBarrier(ObjectMapper mapper, HttpClient client) {
        this.mapper = mapper;
        this.client = client;
    }

    @Override
    public Decision await(DistributedGraphExecution execution,
                          DistributedGraphRuntimeContext runtime,
                          UnifiedCrawlJob.ProgressSnapshot snapshot) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("sessionId", execution.sessionId());
                payload.put("workerId", execution.partitionId());
                payload.put("attempt", execution.attempt());
                payload.put("snapshot", snapshot);
                HttpRequest request = HttpRequest.newBuilder(URI.create(
                                runtime.authorityBaseUrl() + "/api/distributed-crawl/barrier"))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + runtime.bearerToken())
                        .header("X-Kompile-Graph-Lease", runtime.writerLease())
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                        .build();
                HttpResponse<String> response = client.send(
                        request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 202) {
                    Thread.sleep(500L);
                    continue;
                }
                if (response.statusCode() / 100 != 2) return Decision.ABORT;
                JsonNode body = mapper.readTree(response.body());
                return Decision.valueOf(body.path("decision").asText("ABORT"));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Decision.ABORT;
            } catch (Exception unavailable) {
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return Decision.ABORT;
                }
            }
        }
        return Decision.ABORT;
    }
}
