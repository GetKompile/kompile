/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.services.graph;

import ai.kompile.app.services.subprocess.GraphMatrixSubprocessLauncher;
import ai.kompile.app.subprocess.GraphMatrixSubprocessMain;
import ai.kompile.knowledgegraph.generation.GraphGenerationContext;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP delegate for {@link MatrixGraphStore} — the main-app client in subprocess mode.
 *
 * <p>Every call is proxied to the persistent {@code GraphMatrixSubprocessMain} over loopback
 * HTTP ({@code POST /invoke}).  The matrix graph NEVER materialises in the main JVM heap:
 * {@link #createGraph}/{@link #loadGraph} return lightweight {@link AdjacencyMatrixGraph}
 * shells (graphId only, empty adjacency), and callers must use the leaf-op methods for
 * node/edge access.  Full-graph materialisation is explicitly unsupported here — see Phase 4.</p>
 *
 * <p><b>Activated only when {@code kompile.graph.subprocess.enabled=true}</b>.  With the flag
 * off (default) the existing in-process {@link ai.kompile.knowledgegraph.matrix.store.VectorStoreMatrixGraphStore}
 * remains primary and this bean is not created.</p>
 *
 * <h3>/invoke wire protocol</h3>
 * <pre>
 * POST /invoke  Content-Type: application/json
 * {"method":"<name>","argTypes":["<fqcn>",...],"args":[<json>,...]}
 * → {"ok":true,"result":<json>} | {"ok":false,"error":"..."}
 * </pre>
 * INDArray encoding: {@code {"__indarray":true,"shape":[n,d],"data":[f0,f1,...]}}<br>
 * Graph ack:         {@code {"__graphack":true,"graphId":"...","nodeCount":N,"edgeCount":M}}<br>
 * Map.Entry:         {@code {"key":...,"value":...}}
 */
@Component
@Primary
// Subprocess isolation is ALWAYS the primary path — the matrix graph never runs in the main JVM.
// No Spring property gate: enable/disable, if ever needed, is kompile JSON managed-config, not a property file.
public class SubprocessMatrixGraphStore implements MatrixGraphStore {

    private static final Logger log = LoggerFactory.getLogger(SubprocessMatrixGraphStore.class);

    /** Timeout for a single /invoke round-trip. Long-running ops (rehydration, large batches) stay internal. */
    private static final Duration INVOKE_TIMEOUT = Duration.ofSeconds(120);

    // ── FQCNs used when building argTypes arrays ──────────────────────────────
    private static final String T_STRING    = "java.lang.String";
    private static final String T_LONG      = "java.lang.Long";
    private static final String T_INT       = "int";
    private static final String T_DOUBLE    = "double";
    private static final String T_BOOLEAN   = "boolean";
    private static final String T_LIST      = "java.util.List";
    private static final String T_INDARRAY  = "org.nd4j.linalg.api.ndarray.INDArray";
    private static final String T_NODE      = "ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode";
    private static final String T_GRAPH     = "ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph";

    @Autowired
    private GraphMatrixSubprocessLauncher launcher;

    @Autowired
    private ObjectMapper objectMapper;

    /** Lazily created — Spring may construct this bean before the subprocess port is ready. */
    private volatile HttpClient httpClient;

    private HttpClient client() {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(10))
                            .build();
                }
            }
        }
        return httpClient;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public AdjacencyMatrixGraph createGraph(String graphId, Long factSheetId) {
        JsonNode ack = invokeRaw("createGraph", List.of(T_STRING, T_LONG), graphId, factSheetId);
        return decodeGraphAck(ack);
    }

    @Override
    public Optional<AdjacencyMatrixGraph> loadGraph(String graphId) {
        JsonNode ack = invokeRaw("loadGraph", List.of(T_STRING), graphId);
        if (ack == null || ack.isNull()) return Optional.empty();
        return Optional.of(decodeGraphAck(ack));
    }

    /**
     * Tells the subprocess to flush-save the graph identified by the shell's graphId.
     * The main app must NOT attempt to serialise the full AdjacencyMatrixGraph (it is a
     * stub shell with empty adjacency); the subprocess looks up the real graph in its cache.
     */
    @Override
    public void saveGraph(AdjacencyMatrixGraph graph) throws IOException {
        String graphId = graph != null ? graph.getGraphId() : null;
        if (graphId == null || graphId.isBlank()) {
            log.warn("[subprocess-graph] saveGraph called with null/blank graphId — skipping");
            return;
        }
        // The server detects __saveGraphId in the first arg and bypasses normal dispatch.
        ObjectNode marker = objectMapper.createObjectNode();
        marker.put("__saveGraphId", graphId);
        invokeRawNode("saveGraph", List.of(T_GRAPH), marker);
    }

    @Override
    public boolean deleteGraph(String graphId) {
        return invokeRaw("deleteGraph", List.of(T_STRING), graphId).asBoolean(false);
    }

    @Override
    public List<String> listGraphs() {
        return parseStringList(invokeRaw("listGraphs", List.of()));
    }

    @Override
    public List<String> listGraphsByFactSheet(Long factSheetId) {
        return parseStringList(invokeRaw("listGraphsByFactSheet", List.of(T_LONG), factSheetId));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public int addNode(String graphId, MatrixGraphNode node) {
        return invokeRaw("addNode", List.of(T_STRING, T_NODE), graphId, node).asInt(0);
    }

    @Override
    public void updateNode(String graphId, MatrixGraphNode node) {
        invokeRaw("updateNode", List.of(T_STRING, T_NODE), graphId, node);
    }

    @Override
    public void updateNodeMetadata(String graphId, MatrixGraphNode node) {
        invokeRaw("updateNodeMetadata", List.of(T_STRING, T_NODE), graphId, node);
    }

    @Override
    public boolean removeNode(String graphId, String nodeId) {
        return invokeRaw("removeNode", List.of(T_STRING, T_STRING), graphId, nodeId).asBoolean(false);
    }

    @Override
    public Optional<MatrixGraphNode> getNode(String graphId, String nodeId) {
        JsonNode result = invokeRaw("getNode", List.of(T_STRING, T_STRING), graphId, nodeId);
        if (result == null || result.isNull()) return Optional.empty();
        return Optional.of(objectMapper.convertValue(result, MatrixGraphNode.class));
    }

    @Override
    public List<MatrixGraphNode> getAllNodes(String graphId) {
        return parseNodeList(invokeRaw("getAllNodes", List.of(T_STRING), graphId));
    }

    @Override
    public ScanPage<MatrixGraphNode> scanNodes(String graphId, int cursor, int pageSize) {
        JsonNode result = invokeRaw("scanNodes", List.of(T_STRING, T_INT, T_INT), graphId, cursor, pageSize);
        return objectMapper.convertValue(result, objectMapper.getTypeFactory()
                .constructParametricType(ScanPage.class, MatrixGraphNode.class));
    }

    @Override
    public ScanPage<StoredEdge> scanEdges(String graphId, int cursor, int pageSize) {
        JsonNode result = invokeRaw("scanEdges", List.of(T_STRING, T_INT, T_INT), graphId, cursor, pageSize);
        return objectMapper.convertValue(result, objectMapper.getTypeFactory()
                .constructParametricType(ScanPage.class, StoredEdge.class));
    }

    @Override
    public List<MatrixGraphNode> searchNodes(String graphId, String query, int limit) {
        return parseNodeList(invokeRaw("searchNodes", List.of(T_STRING, T_STRING, T_INT), graphId, query, limit));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public boolean addEdge(String graphId, String sourceNodeId, String targetNodeId,
                           double weight, String edgeType, boolean bidirectional) {
        return invokeRaw("addEdge",
                List.of(T_STRING, T_STRING, T_STRING, T_DOUBLE, T_STRING, T_BOOLEAN),
                graphId, sourceNodeId, targetNodeId, weight, edgeType, bidirectional)
                .asBoolean(false);
    }

    @Override
    public boolean addEdge(String graphId, String sourceNodeId, String targetNodeId,
                           double weight, String edgeType, boolean bidirectional, String relationType) {
        return invokeRaw("addEdge",
                List.of(T_STRING, T_STRING, T_STRING, T_DOUBLE, T_STRING, T_BOOLEAN, T_STRING),
                graphId, sourceNodeId, targetNodeId, weight, edgeType, bidirectional, relationType)
                .asBoolean(false);
    }

    @Override
    public boolean addEdge(String graphId, String sourceNodeId, String targetNodeId,
                           double weight, String edgeType, boolean bidirectional,
                           String relationType, Double confidence, String description) {
        return invokeRaw("addEdge",
                List.of(T_STRING, T_STRING, T_STRING, T_DOUBLE, T_STRING, T_BOOLEAN,
                        T_STRING, "java.lang.Double", T_STRING),
                graphId, sourceNodeId, targetNodeId, weight, edgeType, bidirectional,
                relationType, confidence, description)
                .asBoolean(false);
    }

    @Override
    public boolean removeEdge(String graphId, String sourceNodeId, String targetNodeId, String edgeType) {
        return invokeRaw("removeEdge", List.of(T_STRING, T_STRING, T_STRING, T_STRING),
                graphId, sourceNodeId, targetNodeId, edgeType).asBoolean(false);
    }

    @Override
    public List<Map.Entry<String, Double>> getEdges(String graphId, String nodeId, String edgeType) {
        return parseEntryList(invokeRaw("getEdges", List.of(T_STRING, T_STRING, T_STRING),
                graphId, nodeId, edgeType));
    }

    @Override
    public boolean hasEdge(String graphId, String sourceNodeId, String targetNodeId, String edgeType) {
        return invokeRaw("hasEdge", List.of(T_STRING, T_STRING, T_STRING, T_STRING),
                graphId, sourceNodeId, targetNodeId, edgeType).asBoolean(false);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EMBEDDING OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void storeNodeEmbeddings(String graphId, List<String> nodeIds, INDArray embeddings) {
        invokeRaw("storeNodeEmbeddings", List.of(T_STRING, T_LIST, T_INDARRAY),
                graphId, nodeIds, embeddings);
    }

    @Override
    public INDArray getNodeEmbeddings(String graphId, List<String> nodeIds) {
        JsonNode result = invokeRaw("getNodeEmbeddings", List.of(T_STRING, T_LIST), graphId, nodeIds);
        if (result == null || result.isNull()) return null;
        return GraphMatrixSubprocessMain.decodeINDArray(result);
    }

    @Override
    public List<Map.Entry<String, Double>> findSimilarNodes(String graphId, INDArray queryEmbedding,
                                                             int k, double threshold) {
        return parseEntryList(invokeRaw("findSimilarNodes",
                List.of(T_STRING, T_INDARRAY, T_INT, T_DOUBLE),
                graphId, queryEmbedding, k, threshold));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BULK OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public int addNodesBatch(String graphId, List<MatrixGraphNode> nodes) {
        return invokeRaw("addNodesBatch", List.of(T_STRING, T_LIST), graphId, nodes).asInt(0);
    }

    @Override
    public int addEdgesBatch(String graphId, List<EdgeDefinition> edges) {
        return invokeRaw("addEdgesBatch", List.of(T_STRING, T_LIST), graphId, edges).asInt(0);
    }

    @Override
    public void flush() {
        invokeRaw("flush", List.of());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public Map<String, Object> getGraphStatistics(String graphId) {
        JsonNode result = invokeRaw("getGraphStatistics", List.of(T_STRING), graphId);
        if (result == null || result.isNull()) return Map.of();
        return objectMapper.convertValue(result, new TypeReference<Map<String, Object>>() {});
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GENERIC INVOKE HELPER
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Core HTTP round-trip — the single place all HTTP plumbing lives.
     *
     * <p>Serialises the request, POSTs to {@code /invoke}, parses the response, checks
     * {@code ok}, and returns the {@code result} node (may be {@link JsonNode#isNull()}).
     * Callers convert the returned node to their expected Java type.</p>
     *
     * @param method    method name on the remote {@link MatrixGraphStore}
     * @param argTypes  list of FQCN or primitive-keyword strings matching the declared param types
     * @param rawArgs   arguments to serialise; {@link INDArray} instances are encoded as
     *                  {@code {"__indarray":true,...}}; everything else via Jackson
     * @throws RuntimeException wrapping any transport or server-side error
     */
    private JsonNode invokeRaw(String method, List<String> argTypes, Object... rawArgs) {
        return invokeRawNode(method, argTypes,
                (Object[]) encodeArgsToNodes(method, argTypes, rawArgs));
    }

    /** Variant that accepts pre-built JsonNode args (used for saveGraph's marker node). */
    private JsonNode invokeRawNode(String method, List<String> argTypes, Object... rawArgs) {
        try {
            // Build request JSON.
            ObjectNode req = objectMapper.createObjectNode();
            req.put("protocolVersion", 2);
            req.put("method", method);
            GraphGenerationContext.current().ifPresent(generation -> {
                req.set("generation", objectMapper.valueToTree(generation.target()));
                req.put("generationOwnerJobId",
                        GraphGenerationContext.ownerJobId().orElse("unowned"));
            });
            ArrayNode argTypesNode = objectMapper.createArrayNode();
            for (String t : argTypes) argTypesNode.add(t);
            req.set("argTypes", argTypesNode);

            ArrayNode argsNode = objectMapper.createArrayNode();
            for (Object arg : rawArgs) {
                if (arg instanceof JsonNode jn) {
                    argsNode.add(jn);
                } else if (arg instanceof INDArray arr) {
                    argsNode.add(GraphMatrixSubprocessMain.encodeINDArray(arr, objectMapper));
                } else {
                    argsNode.add(objectMapper.valueToTree(arg));
                }
            }
            req.set("args", argsNode);

            String body = objectMapper.writeValueAsString(req);
            GraphRpcEndpointResolver.Endpoint endpoint =
                    GraphRpcEndpointResolver.resolve(launcher.baseUrl());
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(
                            URI.create(endpoint.baseUrl() + "/invoke"))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json")
                    .timeout(INVOKE_TIMEOUT);
            endpoint.headers().forEach(requestBuilder::header);
            HttpRequest httpReq = requestBuilder.build();

            HttpResponse<String> resp = client().send(httpReq, HttpResponse.BodyHandlers.ofString());
            JsonNode respNode = objectMapper.readTree(resp.body());

            if (!respNode.path("ok").asBoolean(true)) {
                String errMsg = respNode.path("error").asText("unknown error");
                throw new RuntimeException("[subprocess-graph] " + method + " failed: " + errMsg);
            }
            JsonNode result = respNode.path("result");
            return result.isMissingNode() ? objectMapper.nullNode() : result;

        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("[subprocess-graph] transport error calling " + method + ": " + e.getMessage(), e);
        }
    }

    /**
     * Converts raw Java args to an array suitable for {@link #invokeRawNode}.
     * INDArray instances are encoded; all other values pass through (Jackson serialises them).
     */
    private Object[] encodeArgsToNodes(String method, List<String> argTypes, Object[] rawArgs) {
        Object[] encoded = new Object[rawArgs.length];
        for (int i = 0; i < rawArgs.length; i++) {
            if (rawArgs[i] instanceof INDArray arr) {
                encoded[i] = GraphMatrixSubprocessMain.encodeINDArray(arr, objectMapper);
            } else {
                encoded[i] = rawArgs[i];
            }
        }
        return encoded;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESULT DECODERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Decode a graph ack ({@code {"__graphack":true,"graphId":"...","nodeCount":N,"edgeCount":M}})
     * into an empty shell {@link AdjacencyMatrixGraph}.  The matrix MUST NOT come into main.
     */
    private AdjacencyMatrixGraph decodeGraphAck(JsonNode ack) {
        String graphId = ack.path("graphId").asText(null);
        if (graphId == null || graphId.isBlank()) {
            log.warn("[subprocess-graph] received graph ack with missing graphId: {}", ack);
            graphId = "unknown";
        }
        int nodeCount = ack.path("nodeCount").asInt(0);
        int edgeCount = ack.path("edgeCount").asInt(0);
        log.warn("[subprocess-graph] loadGraph/createGraph returned a shell AdjacencyMatrixGraph " +
                 "(graphId={}, nodes={}, edges={}) — the real matrix lives in the subprocess. " +
                 "Full-graph materialisation is unsupported in subprocess mode; use leaf ops (Phase 4).",
                 graphId, nodeCount, edgeCount);
        AdjacencyMatrixGraph shell = new AdjacencyMatrixGraph(graphId, 0);
        shell.markAsShell(nodeCount, edgeCount); // WP17e: so graph algorithms warn instead of silently empty
        return shell;
    }

    /** Decode a JSON array of strings. */
    private List<String> parseStringList(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode n : node) result.add(n.asText());
        return result;
    }

    /** Decode a JSON array of MatrixGraphNode objects. */
    private List<MatrixGraphNode> parseNodeList(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        List<MatrixGraphNode> result = new ArrayList<>();
        for (JsonNode n : node) result.add(objectMapper.convertValue(n, MatrixGraphNode.class));
        return result;
    }

    /**
     * Decode a JSON array of {@code {"key":...,"value":...}} objects to {@code List<Map.Entry<String,Double>>}.
     */
    private List<Map.Entry<String, Double>> parseEntryList(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        List<Map.Entry<String, Double>> result = new ArrayList<>();
        for (JsonNode n : node) {
            String key   = n.path("key").asText();
            double value = n.path("value").asDouble(0.0);
            result.add(new AbstractMap.SimpleImmutableEntry<>(key, value));
        }
        return result;
    }
}
