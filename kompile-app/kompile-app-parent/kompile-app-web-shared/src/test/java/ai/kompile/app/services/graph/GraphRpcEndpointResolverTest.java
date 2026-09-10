/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.services.graph;

import ai.kompile.core.crawl.graph.DistributedGraphRuntimeContext;
import ai.kompile.knowledgegraph.generation.GraphGenerationContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphRpcEndpointResolverTest {

    @Test
    void resolvesLocalEndpointWithoutContext() {
        GraphRpcEndpointResolver.Endpoint endpoint =
                GraphRpcEndpointResolver.resolve("http://127.0.0.1:8094/");
        assertEquals("http://127.0.0.1:8094", endpoint.baseUrl());
        assertFalse(endpoint.remote());
        assertTrue(endpoint.headers().isEmpty());
    }

    @Test
    void resolvesRemoteGatewayAndFencingHeadersLexically() {
        DistributedGraphRuntimeContext route = new DistributedGraphRuntimeContext(
                "https://crawl.example/", "secret", "lease", "s1", "p1", 3);
        try (var ignored = GraphGenerationContext.openRemote(null, "distributed:s1", route)) {
            GraphRpcEndpointResolver.Endpoint endpoint =
                    GraphRpcEndpointResolver.resolve("http://127.0.0.1:8094");
            assertEquals("https://crawl.example/api/internal/distributed-graph", endpoint.baseUrl());
            assertTrue(endpoint.remote());
            assertEquals("Bearer secret", endpoint.headers().get("Authorization"));
            assertEquals("lease", endpoint.headers().get("X-Kompile-Graph-Lease"));
            assertEquals("3", endpoint.headers().get("X-Kompile-Attempt"));
        }
        assertFalse(GraphRpcEndpointResolver.resolve("http://127.0.0.1:8094").remote());
    }
}
