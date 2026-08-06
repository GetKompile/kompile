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
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.core.graphrag.GraphRagService;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.springframework.beans.factory.ObjectProvider;
import ai.kompile.core.graphrag.maintenance.model.GraphPruneResult;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.event.attribution.llm.AttributionLlmService;
import ai.kompile.event.attribution.service.BayesianNetworkService;
import ai.kompile.event.attribution.service.EventAttributionService;
import ai.kompile.event.attribution.service.PslReasoningService;
import ai.kompile.graph.reasoning.domain.AttributionQuery;
import ai.kompile.graph.reasoning.domain.AttributionResult;
import ai.kompile.graph.reasoning.domain.BayesianInferenceResult;
import ai.kompile.graph.reasoning.domain.MpeResult;
import ai.kompile.graph.reasoning.domain.PslInferenceResult;
import ai.kompile.graph.reasoning.domain.SensitivityResult;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.type.TypeHierarchy;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.EntityMention;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.service.MatrixGraphConstructor;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Produces {@code @Primary} concrete-class beans for graph services that delegate store/reasoning calls
 * to the persistent {@link GraphMatrixSubprocessMain} via {@code POST /invoke}. Graph extraction itself
 * stays in the main app so configured CLI/LFM agents are available.
 *
 * <p>Activated only when {@code kompile.graph.subprocess.enabled=true}. With the flag off (default)
 * the existing in-process service implementations remain primary and these beans are not created.</p>
 *
 * <h3>Wire protocol</h3>
 * <pre>
 * Request:  {"service":"&lt;interfaceFQCN&gt;","method":"&lt;name&gt;","args":[...]}
 * Response: {"ok":true,"result":&lt;json&gt;} | {"ok":false,"error":"&lt;msg&gt;"}
 * </pre>
 *
 * <h3>Special serialisation</h3>
 * <ul>
 *   <li>{@link INDArray} args/returns → {@code {"__indarray":true,"shape":[...],"data":[...]}}</li>
 *   <li>{@code Map<String,INDArray>} args/returns → {@code {"__indarray_map":true,"entries":{...}}}</li>
 *   <li>{@link AdjacencyMatrixGraph} returns → shell from {@code {"__graphack":true,...}}</li>
 *   <li>Functional-interface args (e.g. ProgressListener) → JSON null; server substitutes no-op</li>
 * </ul>
 *
 * <p><strong>No reflection</strong>: replaces the former JDK-Proxy + InvocationHandler approach
 * with concrete implementing classes. Native-image safe.</p>
 */
@Configuration(proxyBeanMethods = false)
public class GraphServiceSubprocessClients {

    // ── Bean factory methods ─────────────────────────────────────────────────

    @Bean
    @Primary
    public GraphConstructor graphConstructorMainProcess(MatrixGraphConstructor constructor) {
        return constructor;
    }

    @Bean
    @Primary
    public GraphRagService graphRagServiceSubprocessProxy(GraphMatrixSubprocessLauncher launcher,
                                                          ObjectMapper mapper,
                                                          ObjectProvider<EmbeddingModel> embeddingProvider) {
        return new SubprocessGraphRagServiceClient(launcher, mapper, embeddingProvider);
    }

    @Bean
    @Primary
    public KnowledgeGraphService knowledgeGraphServiceSubprocessProxy(GraphMatrixSubprocessLauncher launcher,
                                                                      ObjectMapper mapper) {
        return new SubprocessKnowledgeGraphServiceClient(launcher, mapper);
    }

    @Bean
    @Primary
    public BayesianNetworkService bayesianNetworkServiceSubprocessProxy(
            GraphMatrixSubprocessLauncher launcher,
            ObjectMapper mapper,
            KnowledgeGraphService graphService) {
        return new SubprocessBayesianNetworkServiceClient(launcher, mapper, graphService);
    }

    @Bean
    @Primary
    public PslReasoningService pslReasoningServiceSubprocessProxy(
            GraphMatrixSubprocessLauncher launcher,
            ObjectMapper mapper,
            KnowledgeGraphService graphService) {
        return new SubprocessPslReasoningServiceClient(launcher, mapper, graphService);
    }

    @Bean
    @Primary
    public EventAttributionService eventAttributionServiceSubprocessProxy(
            GraphMatrixSubprocessLauncher launcher,
            ObjectMapper mapper,
            KnowledgeGraphService graphService,
            AttributionLlmService llmService) {
        return new SubprocessEventAttributionServiceClient(launcher, mapper, graphService, llmService);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Shared RPC base
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Abstract base providing the shared HTTP-over-JSON RPC mechanics.
     * One shared {@link HttpClient} per instance (one per interface), never recreated.
     */
    static abstract class SubprocessRpcBase {

        private static final Logger log = LoggerFactory.getLogger(SubprocessRpcBase.class);
        private static final Duration INVOKE_TIMEOUT = Duration.ofSeconds(120);
        private static final long DEFAULT_MAX_REQUEST_BYTES = 8L * 1024 * 1024;
        private static final long DEFAULT_MAX_RESPONSE_BYTES = 16L * 1024 * 1024;

        protected final String serviceFqcn;
        protected final GraphMatrixSubprocessLauncher launcher;
        protected final ObjectMapper mapper;
        private volatile HttpClient httpClient;

        protected SubprocessRpcBase(String serviceFqcn,
                                    GraphMatrixSubprocessLauncher launcher,
                                    ObjectMapper mapper) {
            this.serviceFqcn = serviceFqcn;
            this.launcher    = launcher;
            this.mapper      = mapper;
        }

        // ── Core raw RPC ──────────────────────────────────────────────────────

        /** POST /invoke and return the parsed {@code result} JsonNode (never missing). */
        private JsonNode rpcRaw(String method, Object[] args) {
            try {
                ObjectNode req = mapper.createObjectNode();
                req.put("service", serviceFqcn);
                req.put("method", method);

                ArrayNode argsNode = mapper.createArrayNode();
                if (args != null) {
                    for (Object arg : args) {
                        argsNode.add(encodeArg(arg));
                    }
                }
                req.set("args", argsNode);

                byte[] reqBody = mapper.writeValueAsBytes(req);
                long maxRequestBytes = positiveLongProperty(
                        "kompile.graph.subprocess.max-request-bytes", DEFAULT_MAX_REQUEST_BYTES);
                if (reqBody.length > maxRequestBytes) {
                    throw new IllegalArgumentException("[subprocess-graph-rpc] request for "
                            + serviceFqcn + "." + method + " is " + reqBody.length
                            + " bytes; limit is " + maxRequestBytes + ". Split the batch.");
                }
                HttpRequest http = HttpRequest.newBuilder(URI.create(launcher.baseUrl() + "/invoke"))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(reqBody))
                        .header("Content-Type", "application/json")
                        .timeout(INVOKE_TIMEOUT)
                        .build();

                HttpResponse<InputStream> resp = client().send(http, HttpResponse.BodyHandlers.ofInputStream());
                long maxResponseBytes = positiveLongProperty(
                        "kompile.graph.subprocess.max-response-bytes", DEFAULT_MAX_RESPONSE_BYTES);
                long responseLength = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
                if (responseLength > maxResponseBytes) {
                    resp.body().close();
                    throw new IllegalStateException("[subprocess-graph-rpc] response for "
                            + serviceFqcn + "." + method + " exceeds " + maxResponseBytes
                            + " bytes; use a paged query");
                }
                JsonNode respNode;
                try (InputStream body = new LimitedInputStream(resp.body(), maxResponseBytes)) {
                    respNode = mapper.readTree(body);
                }

                if (!respNode.path("ok").asBoolean(true)) {
                    throw new RuntimeException("[subprocess-graph-rpc] " + serviceFqcn + "." + method
                            + " failed: " + respNode.path("error").asText("unknown error"));
                }

                JsonNode resultNode = respNode.path("result");
                return resultNode.isMissingNode() ? mapper.nullNode() : resultNode;

            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("[subprocess-graph-rpc] " + serviceFqcn + "." + method
                        + " failed: " + e, e);
            }
        }

        // ── Typed RPC helpers ─────────────────────────────────────────────────

        /** Generic RPC — deserializes result via the supplied {@link JavaType}. */
        @SuppressWarnings("unchecked")
        protected <T> T rpc(String method, Object[] args, JavaType resultType) {
            JsonNode node = rpcRaw(method, args);
            if (node == null || node.isNull()) {
                return (T) defaultForType(resultType);
            }
            if (node.path("__graphack").asBoolean(false)) {
                return (T) new AdjacencyMatrixGraph(node.path("graphId").asText("unknown"), 0);
            }
            if (node.path("__indarray").asBoolean(false)) {
                return (T) GraphMatrixSubprocessMain.decodeINDArray(node);
            }
            if (node.path("__indarray_map").asBoolean(false)) {
                return (T) GraphMatrixSubprocessMain.decodeINDArrayMap(node);
            }
            try {
                return mapper.convertValue(node, resultType);
            } catch (Exception e) {
                throw new RuntimeException("[subprocess-graph-rpc] deserialize failed for "
                        + serviceFqcn + "." + method + ": " + e, e);
            }
        }

        /** RPC for {@code void} methods — ignores result. */
        protected void rpcVoid(String method, Object[] args) {
            rpcRaw(method, args);
        }

        /** RPC for {@code Optional<T>} returns. */
        @SuppressWarnings("unchecked")
        protected <T> Optional<T> rpcOptional(String method, Object[] args, Class<T> innerClass) {
            JsonNode node = rpcRaw(method, args);
            if (node == null || node.isNull()) return Optional.empty();
            if (node.path("__graphack").asBoolean(false)) {
                return Optional.of((T) new AdjacencyMatrixGraph(node.path("graphId").asText("unknown"), 0));
            }
            T val = mapper.convertValue(node, innerClass);
            return Optional.ofNullable(val);
        }

        // ── Arg encoding ──────────────────────────────────────────────────────

        private JsonNode encodeArg(Object arg) {
            if (arg == null) return mapper.nullNode();
            if (arg instanceof INDArray arr) {
                return GraphMatrixSubprocessMain.encodeINDArray(arr, mapper);
            }
            if (arg instanceof Map<?, ?> m && !m.isEmpty()
                    && m.values().iterator().next() instanceof INDArray) {
                @SuppressWarnings("unchecked")
                Map<String, INDArray> indMap = (Map<String, INDArray>) m;
                return GraphMatrixSubprocessMain.encodeINDArrayMap(indMap, mapper);
            }
            return mapper.valueToTree(arg);
        }

        // ── Null/zero defaults for primitive boxed types ──────────────────────

        private static Object defaultForType(JavaType type) {
            Class<?> raw = type.getRawClass();
            if (raw == int.class     || raw == Integer.class)  return 0;
            if (raw == long.class    || raw == Long.class)     return 0L;
            if (raw == double.class  || raw == Double.class)   return 0.0;
            if (raw == float.class   || raw == Float.class)    return 0.0f;
            if (raw == boolean.class || raw == Boolean.class)  return false;
            if (raw == Optional.class) return Optional.empty();
            return null;
        }

        private static long positiveLongProperty(String name, long defaultValue) {
            String value = System.getProperty(name);
            if (value == null || value.isBlank()) return defaultValue;
            try {
                long parsed = Long.parseLong(value);
                return parsed > 0 ? parsed : defaultValue;
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }

        private static final class LimitedInputStream extends InputStream {
            private final InputStream delegate;
            private final long limit;
            private long read;

            private LimitedInputStream(InputStream delegate, long limit) {
                this.delegate = delegate;
                this.limit = limit;
            }

            @Override
            public int read() throws java.io.IOException {
                int value = delegate.read();
                if (value >= 0 && ++read > limit) {
                    throw new java.io.IOException("graph RPC response exceeds " + limit + " bytes");
                }
                return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                int allowed = (int) Math.min(length, Math.max(1L, limit - read + 1L));
                int count = delegate.read(bytes, offset, allowed);
                if (count > 0 && (read += count) > limit) {
                    throw new java.io.IOException("graph RPC response exceeds " + limit + " bytes");
                }
                return count;
            }

            @Override
            public void close() throws java.io.IOException {
                delegate.close();
            }
        }

        // ── Shared HTTP client (one per instance, lazily created) ─────────────

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
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GraphRagService client
    // ══════════════════════════════════════════════════════════════════════════

    static final class SubprocessGraphRagServiceClient extends SubprocessRpcBase
            implements GraphRagService {

        private static final Logger ragLog = LoggerFactory.getLogger(SubprocessGraphRagServiceClient.class);

        SubprocessGraphRagServiceClient(GraphMatrixSubprocessLauncher launcher, ObjectMapper mapper,
                                        ObjectProvider<EmbeddingModel> embeddingProvider) {
            super(GraphRagService.class.getName(), launcher, mapper);
        }

        @Override
        public GraphRagResult answerQuery(GraphRagQuery query) {
            return rpc("answerQuery", new Object[]{query},
                    mapper.getTypeFactory().constructType(GraphRagResult.class));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Reasoning service clients
    // ══════════════════════════════════════════════════════════════════════════

    static final class SubprocessBayesianNetworkServiceClient extends BayesianNetworkService {
        private final SubprocessRpcBase rpc;
        private final ObjectMapper mapper;
        private final JavaType typeBayesianInferenceResult;
        private final JavaType typeMpeResult;
        private final JavaType typeSensitivityResult;
        private final JavaType typeMapStringObject;
        private final JavaType typeMTheory;

        SubprocessBayesianNetworkServiceClient(GraphMatrixSubprocessLauncher launcher,
                                               ObjectMapper mapper,
                                               KnowledgeGraphService graphService) {
            super(graphService);
            this.mapper = mapper;
            this.rpc = new SubprocessRpcBase(BayesianNetworkService.class.getName(), launcher, mapper) {};
            var tf = mapper.getTypeFactory();
            this.typeBayesianInferenceResult = tf.constructType(BayesianInferenceResult.class);
            this.typeMpeResult = tf.constructType(MpeResult.class);
            this.typeSensitivityResult = tf.constructType(SensitivityResult.class);
            this.typeMapStringObject = tf.constructMapType(Map.class, String.class, Object.class);
            this.typeMTheory = tf.constructType(MTheory.class);
        }

        @Override
        public BayesianInferenceResult queryMebnFromKg(Collection<String> seedNodeIds,
                                                       Map<String, Integer> evidence,
                                                       int maxDepth, int maxNodes) {
            return rpc.rpc("queryMebnFromKg",
                    new Object[]{seedNodeIds, evidence, maxDepth, maxNodes}, typeBayesianInferenceResult);
        }

        @Override
        public BayesianInferenceResult queryMebnFromKg(Collection<String> seedNodeIds,
                                                       Map<String, Integer> evidence,
                                                       int maxDepth, int maxNodes,
                                                       TypeHierarchy typeHierarchy) {
            return rpc.rpc("queryMebnFromKg",
                    new Object[]{seedNodeIds, evidence, maxDepth, maxNodes, typeHierarchy},
                    typeBayesianInferenceResult);
        }

        @Override
        public BayesianInferenceResult queryAllPosteriors(Collection<String> seedNodeIds,
                                                          Map<String, Integer> evidence,
                                                          int maxDepth, int maxNodes) {
            return rpc.rpc("queryAllPosteriors",
                    new Object[]{seedNodeIds, evidence, maxDepth, maxNodes}, typeBayesianInferenceResult);
        }

        @Override
        public BayesianInferenceResult queryPosterior(Collection<String> seedNodeIds,
                                                      String queryNodeId,
                                                      Map<String, Integer> evidence,
                                                      int maxDepth, int maxNodes) {
            return rpc.rpc("queryPosterior",
                    new Object[]{seedNodeIds, queryNodeId, evidence, maxDepth, maxNodes},
                    typeBayesianInferenceResult);
        }

        @Override
        public MpeResult mostProbableExplanation(Collection<String> seedNodeIds,
                                                 Map<String, Integer> evidence,
                                                 int maxDepth, int maxNodes) {
            return rpc.rpc("mostProbableExplanation",
                    new Object[]{seedNodeIds, evidence, maxDepth, maxNodes}, typeMpeResult);
        }

        @Override
        public SensitivityResult sensitivityAnalysis(Collection<String> seedNodeIds,
                                                     String queryNodeId,
                                                     Map<String, Integer> evidence,
                                                     double epsilon,
                                                     int maxDepth, int maxNodes) {
            return rpc.rpc("sensitivityAnalysis",
                    new Object[]{seedNodeIds, queryNodeId, evidence, epsilon, maxDepth, maxNodes},
                    typeSensitivityResult);
        }

        @Override
        public Map<String, Object> getMebnStatistics(Collection<String> seedNodeIds,
                                                     int maxDepth, int maxNodes) {
            return rpc.rpc("getMebnStatistics", new Object[]{seedNodeIds, maxDepth, maxNodes},
                    typeMapStringObject);
        }

        @Override
        public MTheory buildMebnTheory(Collection<String> seedNodeIds, int maxDepth, int maxNodes) {
            return rpc.rpc("buildMebnTheory", new Object[]{seedNodeIds, maxDepth, maxNodes}, typeMTheory);
        }
    }

    static final class SubprocessPslReasoningServiceClient extends PslReasoningService {
        private final SubprocessRpcBase rpc;
        private final JavaType typePslInferenceResult;
        private final JavaType typeMapStringObject;

        SubprocessPslReasoningServiceClient(GraphMatrixSubprocessLauncher launcher,
                                            ObjectMapper mapper,
                                            KnowledgeGraphService graphService) {
            super(graphService);
            this.rpc = new SubprocessRpcBase(PslReasoningService.class.getName(), launcher, mapper) {};
            var tf = mapper.getTypeFactory();
            this.typePslInferenceResult = tf.constructType(PslInferenceResult.class);
            this.typeMapStringObject = tf.constructMapType(Map.class, String.class, Object.class);
        }

        @Override
        public PslInferenceResult infer(Collection<String> seedNodeIds,
                                        Map<String, Double> evidence,
                                        int maxDepth, int maxNodes) {
            return rpc.rpc("infer", new Object[]{seedNodeIds, evidence, maxDepth, maxNodes},
                    typePslInferenceResult);
        }

        @Override
        public PslInferenceResult inferWithRules(Collection<String> seedNodeIds,
                                                 List<String> ruleStrings,
                                                 Map<String, Double> evidence,
                                                 int maxDepth, int maxNodes) {
            return rpc.rpc("inferWithRules",
                    new Object[]{seedNodeIds, ruleStrings, evidence, maxDepth, maxNodes},
                    typePslInferenceResult);
        }

        @Override
        public Map<String, Object> programStatistics(Collection<String> seedNodeIds,
                                                     int maxDepth, int maxNodes) {
            return rpc.rpc("programStatistics", new Object[]{seedNodeIds, maxDepth, maxNodes},
                    typeMapStringObject);
        }
    }

    static final class SubprocessEventAttributionServiceClient extends EventAttributionService {
        private final SubprocessRpcBase rpc;
        private final JavaType typeAttributionResult;

        SubprocessEventAttributionServiceClient(GraphMatrixSubprocessLauncher launcher,
                                                ObjectMapper mapper,
                                                KnowledgeGraphService graphService,
                                                AttributionLlmService llmService) {
            super(graphService, llmService);
            this.rpc = new SubprocessRpcBase(EventAttributionService.class.getName(), launcher, mapper) {};
            this.typeAttributionResult = mapper.getTypeFactory().constructType(AttributionResult.class);
        }

        @Override
        public AttributionResult explain(AttributionQuery query) {
            return rpc.rpc("explain", new Object[]{query}, typeAttributionResult);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // KnowledgeGraphService client
    // ══════════════════════════════════════════════════════════════════════════

    static final class SubprocessKnowledgeGraphServiceClient extends SubprocessRpcBase
            implements KnowledgeGraphService {

        /** Items per page for whole-fact-sheet reads assembled over the paged RPCs.
         *  A single-response fetch is forbidden: one ~1GB getEdgesInFactSheet response
         *  OOM'd the subprocess (Jackson TextBuffer dies past 1GB). */
        private static final int WIRE_PAGE_SIZE = 1_000;

        // Cached JavaType instances (built once, reused on every call)
        private final JavaType typeGraphNode;
        private final JavaType typeListGraphNode;
        private final JavaType typeListGraphEdge;
        private final JavaType typeGraphNodePage;
        private final JavaType typeGraphEdgePage;
        private final JavaType typeListEntityMention;
        private final JavaType typeListString;
        private final JavaType typeListObjectArray;
        private final JavaType typeMapStringObject;
        private final JavaType typeMapStringDouble;
        private final JavaType typeMapStringINDArray;
        private final JavaType typeSetLong;
        private final JavaType typeSetNodeLevel;
        private final JavaType typeGraphEdge;
        private final JavaType typeEntityMention;
        private final JavaType typeGraphPruneResult;
        private final JavaType typeLong;
        private final JavaType typeInt;
        private final JavaType typeBoolean;
        private final JavaType typeKGEmbeddingAlgorithm;
        private final JavaType typeINDArray;
        private final JavaType typeListNodeSpec;
        private final JavaType typeListSnippetSpec;
        private final JavaType typeListNodeMetadataUpdate;
        private final JavaType typeListNodeUpdate;
        private final JavaType typeListEdgeSpec;
        private final JavaType typeListEdgeMetadataUpdate;

        SubprocessKnowledgeGraphServiceClient(GraphMatrixSubprocessLauncher launcher,
                                              ObjectMapper mapper) {
            super(KnowledgeGraphService.class.getName(), launcher, mapper);
            var tf = mapper.getTypeFactory();
            typeGraphNode           = tf.constructType(GraphNode.class);
            typeListGraphNode       = tf.constructCollectionType(List.class, GraphNode.class);
            typeListGraphEdge       = tf.constructCollectionType(List.class, GraphEdge.class);
            typeGraphNodePage        = tf.constructParametricType(KnowledgeGraphService.GraphPage.class, GraphNode.class);
            typeGraphEdgePage        = tf.constructParametricType(KnowledgeGraphService.GraphPage.class, GraphEdge.class);
            typeListEntityMention   = tf.constructCollectionType(List.class, EntityMention.class);
            typeListString          = tf.constructCollectionType(List.class, String.class);
            typeListObjectArray     = tf.constructCollectionType(List.class, Object[].class);
            typeMapStringObject     = tf.constructMapType(Map.class, String.class, Object.class);
            typeMapStringDouble     = tf.constructMapType(Map.class, String.class, Double.class);
            typeMapStringINDArray   = tf.constructMapType(Map.class, String.class, INDArray.class);
            typeSetLong             = tf.constructCollectionType(Set.class, Long.class);
            typeSetNodeLevel        = tf.constructCollectionType(Set.class, NodeLevel.class);
            typeGraphEdge           = tf.constructType(GraphEdge.class);
            typeEntityMention       = tf.constructType(EntityMention.class);
            typeGraphPruneResult    = tf.constructType(GraphPruneResult.class);
            typeLong                = tf.constructType(Long.class);
            typeInt                 = tf.constructType(Integer.class);
            typeBoolean             = tf.constructType(Boolean.class);
            typeKGEmbeddingAlgorithm = tf.constructType(KGEmbeddingAlgorithm.class);
            typeINDArray            = tf.constructType(INDArray.class);
            typeListNodeSpec        = tf.constructCollectionType(List.class, KnowledgeGraphService.NodeSpec.class);
            typeListSnippetSpec     = tf.constructCollectionType(List.class, KnowledgeGraphService.SnippetSpec.class);
            typeListNodeMetadataUpdate = tf.constructCollectionType(List.class,
                    KnowledgeGraphService.NodeMetadataUpdate.class);
            typeListNodeUpdate      = tf.constructCollectionType(List.class,
                    KnowledgeGraphService.NodeUpdate.class);
            typeListEdgeSpec        = tf.constructCollectionType(List.class, KnowledgeGraphService.EdgeSpec.class);
            typeListEdgeMetadataUpdate = tf.constructCollectionType(List.class,
                    KnowledgeGraphService.EdgeMetadataUpdate.class);
        }

        // ── Node management ───────────────────────────────────────────────────

        @Override
        public GraphNode createOrUpdateSourceNode(String externalId, String title, String sourceType,
                                                   String pathOrUrl, Map<String, Object> metadata) {
            return rpc("createOrUpdateSourceNode",
                    new Object[]{externalId, title, sourceType, pathOrUrl, metadata}, typeGraphNode);
        }

        @Override
        public GraphNode createDocumentNode(GraphNode sourceNode, String docId, String title,
                                             Map<String, Object> metadata) {
            return rpc("createDocumentNode", new Object[]{sourceNode, docId, title, metadata}, typeGraphNode);
        }

        @Override
        public GraphNode createSnippetNode(GraphNode documentNode, String snippetId, String content,
                                            int chunkIndex) {
            return rpc("createSnippetNode", new Object[]{documentNode, snippetId, content, chunkIndex},
                    typeGraphNode);
        }

        @Override
        public GraphNode createSnippetNode(GraphNode documentNode, String snippetId, String content,
                                            int chunkIndex, Map<String, Object> metadata) {
            return rpc("createSnippetNode",
                    new Object[]{documentNode, snippetId, content, chunkIndex, metadata}, typeGraphNode);
        }

        @Override
        public List<GraphNode> createSnippetNodesBatch(List<KnowledgeGraphService.SnippetSpec> specs) {
            ArrayNode payload = GraphMatrixSubprocessMain.encodeKnowledgeGraphRecordList(specs, mapper);
            return rpc("createSnippetNodesBatch", new Object[]{payload}, typeListGraphNode);
        }

        @Override
        public GraphNode createTableNode(String parentNodeId, String externalId, String tableTitle,
                                          int rowCount, int columnCount, List<String> headers,
                                          String contentPreview, Map<String, Object> metadata) {
            return rpc("createTableNode",
                    new Object[]{parentNodeId, externalId, tableTitle, rowCount, columnCount,
                                 headers, contentPreview, metadata},
                    typeGraphNode);
        }

        @Override
        public GraphNode createNode(NodeLevel nodeType, String externalId, String title,
                                     String description, Map<String, Object> metadata) {
            return rpc("createNode", new Object[]{nodeType, externalId, title, description, metadata},
                    typeGraphNode);
        }

        @Override
        public GraphNode createNode(NodeLevel nodeType, String externalId, String title,
                                     String description, Map<String, Object> metadata, Long factSheetId) {
            return rpc("createNode",
                    new Object[]{nodeType, externalId, title, description, metadata, factSheetId},
                    typeGraphNode);
        }

        @Override
        public List<GraphNode> createNodesBatch(List<NodeSpec> specs, Long factSheetId) {
            ArrayNode payload = GraphMatrixSubprocessMain.encodeKnowledgeGraphRecordList(specs, mapper);
            return rpc("createNodesBatch", new Object[]{payload, factSheetId}, typeListGraphNode);
        }

        @Override
        public Optional<GraphNode> getNode(String nodeId) {
            return rpcOptional("getNode", new Object[]{nodeId}, GraphNode.class);
        }

        @Override
        public Optional<GraphNode> getNodeByExternalId(String externalId, NodeLevel nodeType) {
            return rpcOptional("getNodeByExternalId", new Object[]{externalId, nodeType}, GraphNode.class);
        }

        @Override
        public Optional<GraphNode> getNodeByExternalId(String externalId, NodeLevel nodeType, Long factSheetId) {
            return rpcOptional("getNodeByExternalId",
                    new Object[]{externalId, nodeType, factSheetId}, GraphNode.class);
        }

        @Override
        public List<GraphNode> getNodesByExternalIds(List<KnowledgeGraphService.ExternalNodeLookup> lookups) {
            ArrayNode lookupPayload = GraphMatrixSubprocessMain.encodeExternalNodeLookups(lookups, mapper);
            return rpc("getNodesByExternalIds", new Object[]{lookupPayload}, typeListGraphNode);
        }

        @Override
        public List<GraphNode> getChildren(String parentNodeId) {
            return rpc("getChildren", new Object[]{parentNodeId}, typeListGraphNode);
        }

        @Override
        public GraphNode updateNode(String nodeId, String title, String description,
                                     Map<String, Object> metadata) {
            return rpc("updateNode", new Object[]{nodeId, title, description, metadata}, typeGraphNode);
        }

        @Override
        public void awaitPendingEmbeddings() {
            rpcVoid("awaitPendingEmbeddings", new Object[]{});
        }

        @Override
        public int updateNodeKgeMetadataBatch(List<KnowledgeGraphService.NodeMetadataUpdate> updates) {
            ArrayNode payload = GraphMatrixSubprocessMain.encodeKnowledgeGraphRecordList(updates, mapper);
            Integer result = rpc("updateNodeKgeMetadataBatch", new Object[]{payload}, typeInt);
            return result != null ? result : 0;
        }

        @Override
        public int updateNodesBatch(List<KnowledgeGraphService.NodeUpdate> updates) {
            ArrayNode payload = GraphMatrixSubprocessMain.encodeKnowledgeGraphRecordList(updates, mapper);
            Integer result = rpc("updateNodesBatch", new Object[]{payload}, typeInt);
            return result != null ? result : 0;
        }

        @Override
        public void deleteNode(String nodeId) {
            rpcVoid("deleteNode", new Object[]{nodeId});
        }

        @Override
        public List<GraphNode> getAllSources() {
            return rpc("getAllSources", new Object[]{}, typeListGraphNode);
        }

        @Override
        public Set<Long> findFactSheetIds() {
            return rpc("findFactSheetIds", new Object[]{}, typeSetLong);
        }

        @Override
        public List<GraphNode> getAllNodes(int limit) {
            return rpc("getAllNodes", new Object[]{limit}, typeListGraphNode);
        }

        @Override
        public List<GraphNode> searchNodes(String query, NodeLevel type, int limit) {
            return rpc("searchNodes", new Object[]{query, type, limit}, typeListGraphNode);
        }

        // ── Edge management ───────────────────────────────────────────────────

        @Override
        public GraphEdge createEdge(String sourceNodeId, String targetNodeId, EdgeType edgeType,
                                     Double weight, String description) {
            return rpc("createEdge",
                    new Object[]{sourceNodeId, targetNodeId, edgeType, weight, description},
                    typeGraphEdge);
        }

        @Override
        public GraphEdge createEdge(String sourceNodeId, String targetNodeId, EdgeType edgeType,
                                    String relationType, Double weight, String description) {
            return rpc("createEdge",
                    new Object[]{sourceNodeId, targetNodeId, edgeType, relationType, weight, description},
                    typeGraphEdge);
        }

        @Override
        public int createEdgesBatch(List<KnowledgeGraphService.EdgeSpec> specs) {
            ArrayNode payload = GraphMatrixSubprocessMain.encodeKnowledgeGraphRecordList(specs, mapper);
            Integer result = rpc("createEdgesBatch", new Object[]{payload}, typeInt);
            return result != null ? result : 0;
        }

        @Override
        public int updateEdgeMetadataBatch(List<KnowledgeGraphService.EdgeMetadataUpdate> updates) {
            ArrayNode payload = GraphMatrixSubprocessMain.encodeKnowledgeGraphRecordList(updates, mapper);
            Integer result = rpc("updateEdgeMetadataBatch", new Object[]{payload}, typeInt);
            return result != null ? result : 0;
        }

        @Override
        public Optional<GraphEdge> getEdge(String edgeId) {
            return rpcOptional("getEdge", new Object[]{edgeId}, GraphEdge.class);
        }

        @Override
        public List<GraphEdge> getEdgesForNode(String nodeId) {
            return rpc("getEdgesForNode", new Object[]{nodeId}, typeListGraphEdge);
        }

        @Override
        public List<GraphEdge> getEdgesByType(String nodeId, EdgeType edgeType) {
            return rpc("getEdgesByType", new Object[]{nodeId, edgeType}, typeListGraphEdge);
        }

        @Override
        public GraphEdge updateEdge(String edgeId, Double weight, String description) {
            return rpc("updateEdge", new Object[]{edgeId, weight, description}, typeGraphEdge);
        }

        @Override
        public void deleteEdge(String edgeId) {
            rpcVoid("deleteEdge", new Object[]{edgeId});
        }

        @Override
        public boolean edgeExists(String sourceNodeId, String targetNodeId) {
            return rpc("edgeExists", new Object[]{sourceNodeId, targetNodeId}, typeBoolean);
        }

        @Override
        public boolean edgeExists(String sourceNodeId, String targetNodeId,
                                   EdgeType edgeType, String label, Long factSheetId) {
            return rpc("edgeExists",
                    new Object[]{sourceNodeId, targetNodeId, edgeType, label, factSheetId}, typeBoolean);
        }

        @Override
        public void deleteEdgesBulk(List<String> edgeIds) {
            rpcVoid("deleteEdgesBulk", new Object[]{edgeIds});
        }

        @Override
        public GraphEdge createEdgeWithMetadata(String sourceNodeId, String targetNodeId,
                                                 EdgeType edgeType, Double weight,
                                                 String label, String description,
                                                 String metaJson, EdgeProvenance provenance,
                                                 Long factSheetId) {
            return rpc("createEdgeWithMetadata",
                    new Object[]{sourceNodeId, targetNodeId, edgeType, weight, label,
                                 description, metaJson, provenance, factSheetId},
                    typeGraphEdge);
        }

        @Override
        public GraphNode addDocument(String sourceExternalId, String jobId, String sourceType,
                                      String sourcePath, String fileName,
                                      String contentPreview, Map<String, Object> docMeta,
                                      Long factSheetId) {
            return rpc("addDocument",
                    new Object[]{sourceExternalId, jobId, sourceType, sourcePath, fileName,
                                 contentPreview, docMeta, factSheetId},
                    typeGraphNode);
        }

        @Override
        public List<GraphEdge> searchEdges(String query, EdgeType edgeType, int limit) {
            return rpc("searchEdges", new Object[]{query, edgeType, limit}, typeListGraphEdge);
        }

        // ── Graph traversal ───────────────────────────────────────────────────

        @Override
        public List<GraphNode> getConnectedNodes(String nodeId, int depth) {
            return rpc("getConnectedNodes", new Object[]{nodeId, depth}, typeListGraphNode);
        }

        @Override
        public List<GraphNode> findRelatedNodes(String nodeId, int maxResults) {
            return rpc("findRelatedNodes", new Object[]{nodeId, maxResults}, typeListGraphNode);
        }

        @Override
        public List<GraphNode> findShortestPath(String fromNodeId, String toNodeId, int maxDepth) {
            return rpc("findShortestPath", new Object[]{fromNodeId, toNodeId, maxDepth}, typeListGraphNode);
        }

        @Override
        public Map<String, Double> computeNodeRelevance(String queryNodeId, List<String> candidateNodeIds) {
            return rpc("computeNodeRelevance", new Object[]{queryNodeId, candidateNodeIds},
                    typeMapStringDouble);
        }

        @Override
        public List<GraphNode> getNodesByType(NodeLevel type, int limit) {
            return rpc("getNodesByType", new Object[]{type, limit}, typeListGraphNode);
        }

        @Override
        public List<GraphNode> getNodesByType(NodeLevel type) {
            return rpc("getNodesByType", new Object[]{type}, typeListGraphNode);
        }

        @Override
        public List<GraphNode> getNodesByIds(List<String> nodeIds) {
            return rpc("getNodesByIds", new Object[]{nodeIds}, typeListGraphNode);
        }

        // ── Fact-sheet-scoped node queries ────────────────────────────────────

        @Override
        public List<GraphNode> getNodesByTypeInFactSheet(Long factSheetId, NodeLevel type) {
            return rpc("getNodesByTypeInFactSheet", new Object[]{factSheetId, type}, typeListGraphNode);
        }

        @Override
        public List<GraphNode> getNodesInFactSheet(Long factSheetId) {
            // Assemble from bounded pages — never fetch a whole fact sheet in one response.
            List<GraphNode> all = new java.util.ArrayList<>();
            int cursor = 0;
            KnowledgeGraphService.GraphPage<GraphNode> page;
            do {
                page = getNodesInFactSheetPage(factSheetId, cursor, WIRE_PAGE_SIZE);
                all.addAll(page.items());
                if (page.hasMore() && page.nextCursor() <= cursor) {
                    throw new IllegalStateException("getNodesInFactSheetPage cursor did not advance: "
                            + cursor + " -> " + page.nextCursor());
                }
                cursor = page.nextCursor();
            } while (page.hasMore());
            return all;
        }

        @Override
        public KnowledgeGraphService.GraphPage<GraphNode> getNodesInFactSheetPage(Long factSheetId, int cursor, int pageSize) {
            return rpc("getNodesInFactSheetPage", new Object[]{factSheetId, cursor, pageSize}, typeGraphNodePage);
        }

        @Override
        public List<GraphNode> getSourcesInFactSheet(Long factSheetId) {
            return rpc("getSourcesInFactSheet", new Object[]{factSheetId}, typeListGraphNode);
        }

        @Override
        public Optional<GraphNode> getNodeByExternalIdInFactSheet(String externalId, NodeLevel type,
                                                                   Long factSheetId) {
            return rpcOptional("getNodeByExternalIdInFactSheet",
                    new Object[]{externalId, type, factSheetId}, GraphNode.class);
        }

        @Override
        public List<GraphNode> searchNodesInFactSheet(Long factSheetId, String query, int limit) {
            return rpc("searchNodesInFactSheet", new Object[]{factSheetId, query, limit}, typeListGraphNode);
        }

        @Override
        public List<GraphNode> searchNodesInFactSheetByType(Long factSheetId, String query,
                                                             NodeLevel type, int limit) {
            return rpc("searchNodesInFactSheetByType",
                    new Object[]{factSheetId, query, type, limit}, typeListGraphNode);
        }

        @Override
        public List<GraphNode> searchNodesGlobal(String query, NodeLevel type, int limit) {
            return rpc("searchNodesGlobal", new Object[]{query, type, limit}, typeListGraphNode);
        }

        // ── Fact-sheet-scoped edge queries ────────────────────────────────────

        @Override
        public List<GraphEdge> getEdgesForNodeInFactSheet(String nodeId, Long factSheetId) {
            return rpc("getEdgesForNodeInFactSheet", new Object[]{nodeId, factSheetId}, typeListGraphEdge);
        }

        @Override
        public boolean edgeExistsInFactSheet(String sourceNodeId, String targetNodeId, Long factSheetId) {
            return rpc("edgeExistsInFactSheet",
                    new Object[]{sourceNodeId, targetNodeId, factSheetId}, typeBoolean);
        }

        @Override
        public List<GraphEdge> getEdgesInFactSheet(Long factSheetId) {
            // Assemble from bounded pages — the single-response variant of this exact
            // call produced a ~1GB payload that OOM'd the graph subprocess.
            List<GraphEdge> all = new java.util.ArrayList<>();
            int cursor = 0;
            KnowledgeGraphService.GraphPage<GraphEdge> page;
            do {
                page = getEdgesInFactSheetPage(factSheetId, cursor, WIRE_PAGE_SIZE);
                all.addAll(page.items());
                if (page.hasMore() && page.nextCursor() <= cursor) {
                    throw new IllegalStateException("getEdgesInFactSheetPage cursor did not advance: "
                            + cursor + " -> " + page.nextCursor());
                }
                cursor = page.nextCursor();
            } while (page.hasMore());
            return all;
        }

        @Override
        public KnowledgeGraphService.GraphPage<GraphEdge> getEdgesInFactSheetPage(Long factSheetId, int cursor, int pageSize) {
            return rpc("getEdgesInFactSheetPage", new Object[]{factSheetId, cursor, pageSize}, typeGraphEdgePage);
        }

        @Override
        public List<GraphEdge> getEdgesByTypeInFactSheet(Long factSheetId, EdgeType edgeType) {
            return rpc("getEdgesByTypeInFactSheet", new Object[]{factSheetId, edgeType}, typeListGraphEdge);
        }

        @Override
        public GraphEdge findEdgeBetweenNodes(String sourceNodeId, String targetNodeId) {
            return rpc("findEdgeBetweenNodes", new Object[]{sourceNodeId, targetNodeId}, typeGraphEdge);
        }

        @Override
        public Optional<GraphEdge> findEdgeBetweenNodesBidirectional(String nodeId1, String nodeId2) {
            return rpcOptional("findEdgeBetweenNodesBidirectional",
                    new Object[]{nodeId1, nodeId2}, GraphEdge.class);
        }

        @Override
        public List<GraphEdge> getStrongEdgesByTypeInFactSheet(Long factSheetId, EdgeType edgeType,
                                                                Double minWeight, int limit) {
            return rpc("getStrongEdgesByTypeInFactSheet",
                    new Object[]{factSheetId, edgeType, minWeight, limit}, typeListGraphEdge);
        }

        @Override
        public List<GraphEdge> getStrongEdgesByType(EdgeType edgeType, Double minWeight, int limit) {
            return rpc("getStrongEdgesByType",
                    new Object[]{edgeType, minWeight, limit}, typeListGraphEdge);
        }

        // ── Entity mention operations ─────────────────────────────────────────

        @Override
        public List<EntityMention> getEntityMentionsForNode(GraphNode node) {
            return rpc("getEntityMentionsForNode", new Object[]{node}, typeListEntityMention);
        }

        @Override
        public List<EntityMention> getEntityMentionsForNode(String nodeId) {
            return rpc("getEntityMentionsForNode", new Object[]{nodeId}, typeListEntityMention);
        }

        @Override
        public Optional<EntityMention> findEntityMention(GraphNode node, String entityName) {
            return rpcOptional("findEntityMention", new Object[]{node, entityName}, EntityMention.class);
        }

        @Override
        public Optional<EntityMention> findEntityMentionInFactSheet(GraphNode node, String entityName,
                                                                     Long factSheetId) {
            return rpcOptional("findEntityMentionInFactSheet",
                    new Object[]{node, entityName, factSheetId}, EntityMention.class);
        }

        @Override
        public EntityMention saveEntityMention(EntityMention mention) {
            return rpc("saveEntityMention", new Object[]{mention}, typeEntityMention);
        }

        @Override
        public List<Object[]> findNodePairsWithSharedEntities(int minShared) {
            return rpc("findNodePairsWithSharedEntities", new Object[]{minShared}, typeListObjectArray);
        }

        @Override
        public List<Object[]> findNodePairsWithSharedEntitiesInFactSheet(Long factSheetId, int minShared) {
            return rpc("findNodePairsWithSharedEntitiesInFactSheet",
                    new Object[]{factSheetId, minShared}, typeListObjectArray);
        }

        @Override
        public List<String> getEntityNamesForNode(String nodeId) {
            return rpc("getEntityNamesForNode", new Object[]{nodeId}, typeListString);
        }

        @Override
        public List<GraphNode> getNodesWithEntity(String entityName) {
            return rpc("getNodesWithEntity", new Object[]{entityName}, typeListGraphNode);
        }

        // ── Node embeddings ───────────────────────────────────────────────────

        @Override
        public Map<String, INDArray> exportNodeEmbeddings(Long factSheetId) {
            return rpc("exportNodeEmbeddings", new Object[]{factSheetId}, typeMapStringINDArray);
        }

        @Override
        public int applyNodeEmbeddings(Map<String, INDArray> embeddingsByNodeId) {
            return rpc("applyNodeEmbeddings", new Object[]{embeddingsByNodeId}, typeInt);
        }

        // ── KGE embedding storage ─────────────────────────────────────────────

        @Override
        public void storeNodeKgEmbedding(String nodeId, INDArray embedding,
                                          KGEmbeddingAlgorithm algorithm, Long version, Instant updatedAt) {
            rpcVoid("storeNodeKgEmbedding", new Object[]{nodeId, embedding, algorithm, version, updatedAt});
        }

        @Override
        public INDArray getNodeKgEmbedding(String nodeId) {
            return rpc("getNodeKgEmbedding", new Object[]{nodeId}, typeINDArray);
        }

        @Override
        public List<GraphNode> findNodesWithKgEmbedding(Long factSheetId) {
            return rpc("findNodesWithKgEmbedding", new Object[]{factSheetId}, typeListGraphNode);
        }

        @Override
        public void storeEdgeTypeKgEmbedding(String edgeTypeName, INDArray embedding,
                                              KGEmbeddingAlgorithm algorithm, Long version, Long factSheetId) {
            rpcVoid("storeEdgeTypeKgEmbedding",
                    new Object[]{edgeTypeName, embedding, algorithm, version, factSheetId});
        }

        @Override
        public Map<String, INDArray> getEdgeTypeKgEmbeddings(Long factSheetId) {
            return rpc("getEdgeTypeKgEmbeddings", new Object[]{factSheetId}, typeMapStringINDArray);
        }

        @Override
        public KGEmbeddingAlgorithm getStoredKgAlgorithm(Long factSheetId) {
            return rpc("getStoredKgAlgorithm", new Object[]{factSheetId}, typeKGEmbeddingAlgorithm);
        }

        @Override
        public void clearKgEmbeddings(Long factSheetId) {
            rpcVoid("clearKgEmbeddings", new Object[]{factSheetId});
        }

        // ── Count / statistics ────────────────────────────────────────────────

        @Override
        public long countNodesByType(NodeLevel type) {
            return rpc("countNodesByType", new Object[]{type}, typeLong);
        }

        @Override
        public long countNodesByTypeInFactSheet(Long factSheetId, NodeLevel type) {
            return rpc("countNodesByTypeInFactSheet", new Object[]{factSheetId, type}, typeLong);
        }

        @Override
        public long countActiveNodes(Long factSheetId) {
            return rpc("countActiveNodes", new Object[]{factSheetId}, typeLong);
        }

        @Override
        public Map<String, Object> getGraphStatistics() {
            return rpc("getGraphStatistics", new Object[]{}, typeMapStringObject);
        }

        @Override
        public Map<String, Object> getVisualizationData(String rootNodeId, int depth, int maxNodes) {
            return rpc("getVisualizationData", new Object[]{rootNodeId, depth, maxNodes},
                    typeMapStringObject);
        }

        @Override
        public Map<String, Object> getVisualizationDataInTimeRange(LocalDateTime from, LocalDateTime to,
                                                                    int maxNodes) {
            return rpc("getVisualizationDataInTimeRange", new Object[]{from, to, maxNodes},
                    typeMapStringObject);
        }

        @Override
        public List<GraphEdge> searchEdgesByTimeRange(LocalDateTime from, LocalDateTime to, int limit) {
            return rpc("searchEdgesByTimeRange", new Object[]{from, to, limit}, typeListGraphEdge);
        }

        @Override
        public Map<String, Object> getTemporalBounds() {
            return rpc("getTemporalBounds", new Object[]{}, typeMapStringObject);
        }

        // ── Maintenance ───────────────────────────────────────────────────────

        @Override
        public void flushPendingNodes() {
            rpcVoid("flushPendingNodes", new Object[]{});
        }

        @Override
        public void deleteByFactSheetId(Long factSheetId) {
            rpcVoid("deleteByFactSheetId", new Object[]{factSheetId});
        }

        @Override
        public GraphNode saveNode(GraphNode node) {
            return rpc("saveNode", new Object[]{node}, typeGraphNode);
        }

        @Override
        public GraphEdge saveEdge(GraphEdge edge) {
            return rpc("saveEdge", new Object[]{edge}, typeGraphEdge);
        }

        @Override
        public Optional<GraphNode> findNodeById(String nodeId) {
            return rpcOptional("findNodeById", new Object[]{nodeId}, GraphNode.class);
        }

        // ── Pruning ───────────────────────────────────────────────────────────

        @Override
        public List<String> findOrphanNodeIds(Long factSheetId) {
            return rpc("findOrphanNodeIds", new Object[]{factSheetId}, typeListString);
        }

        @Override
        public List<String> findOrphanNodeIds(Long factSheetId, Set<NodeLevel> levels) {
            return rpc("findOrphanNodeIds", new Object[]{factSheetId, levels}, typeListString);
        }

        @Override
        public List<String> findLowConfidenceNodeIds(Long factSheetId, double minConfidence) {
            return rpc("findLowConfidenceNodeIds", new Object[]{factSheetId, minConfidence},
                    typeListString);
        }

        @Override
        public List<String> findLowConfidenceEdgeIds(Long factSheetId, double minConfidence) {
            return rpc("findLowConfidenceEdgeIds", new Object[]{factSheetId, minConfidence},
                    typeListString);
        }

        @Override
        public List<String> findActiveEdgeIds(Long factSheetId) {
            return rpc("findActiveEdgeIds", new Object[]{factSheetId}, typeListString);
        }

        @Override
        public GraphPruneResult pruneNodes(Collection<String> nodeIds, boolean softDelete,
                                            Duration grace, boolean dryRun) {
            return rpc("pruneNodes", new Object[]{nodeIds, softDelete, grace, dryRun},
                    typeGraphPruneResult);
        }

        @Override
        public GraphPruneResult pruneEdges(Collection<String> edgeIds, boolean softDelete,
                                            boolean dryRun) {
            return rpc("pruneEdges", new Object[]{edgeIds, softDelete, dryRun}, typeGraphPruneResult);
        }

        @Override
        public GraphPruneResult hardDeleteStaleNodes(Long factSheetId, Duration grace) {
            return rpc("hardDeleteStaleNodes", new Object[]{factSheetId, grace}, typeGraphPruneResult);
        }
    }
}
