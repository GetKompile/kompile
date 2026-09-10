/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.services.graph;

import ai.kompile.core.crawl.graph.DistributedGraphRuntimeContext;
import ai.kompile.knowledgegraph.generation.GraphGenerationContext;

import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves each graph RPC to the local child or the current distributed authority gateway. */
final class GraphRpcEndpointResolver {

    private GraphRpcEndpointResolver() { }

    record Endpoint(String baseUrl, Map<String, String> headers, boolean remote) { }

    static Endpoint resolve(String localBaseUrl) {
        return GraphGenerationContext.remoteRoute()
                .map(GraphRpcEndpointResolver::remote)
                .orElseGet(() -> new Endpoint(trim(localBaseUrl), Map.of(), false));
    }

    private static Endpoint remote(DistributedGraphRuntimeContext route) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + route.bearerToken());
        headers.put("X-Kompile-Graph-Lease", route.writerLease());
        headers.put("X-Kompile-Session-Id", route.sessionId());
        headers.put("X-Kompile-Partition-Id", route.partitionId());
        headers.put("X-Kompile-Attempt", Integer.toString(route.attempt()));
        return new Endpoint(trim(route.authorityBaseUrl()) + "/api/internal/distributed-graph",
                Map.copyOf(headers), true);
    }

    private static String trim(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
