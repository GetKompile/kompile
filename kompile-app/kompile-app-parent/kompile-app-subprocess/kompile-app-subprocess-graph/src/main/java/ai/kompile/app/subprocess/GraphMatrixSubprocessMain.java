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
package ai.kompile.app.subprocess;

import ai.kompile.app.config.NativeLibraryResolver;
import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.core.graphrag.GraphRagService;
import ai.kompile.core.graphrag.maintenance.model.GraphPruneResult;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.event.attribution.service.BayesianNetworkService;
import ai.kompile.event.attribution.service.EventAttributionService;
import ai.kompile.event.attribution.service.PslReasoningService;
import ai.kompile.graph.reasoning.domain.AttributionQuery;
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
import ai.kompile.knowledgegraph.generation.GraphGeneration;
import ai.kompile.knowledgegraph.generation.GraphGenerationContext;
import ai.kompile.knowledgegraph.generation.GraphGenerationCoordinator;
import ai.kompile.knowledgegraph.generation.GraphGenerationJournal;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import ai.kompile.knowledgegraph.matrix.store.VectorStoreMatrixGraphStore;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.nd4j.imports.converters.DifferentialFunctionClassHolder;
import org.nd4j.linalg.api.ndarray.INDArray;
import ai.kompile.knowledgegraph.matrix.serde.FlatArrayCodec;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.factory.Nd4jBackend;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persistent subprocess that OWNS the matrix graph subsystem.
 *
 * <p>Owns {@link VectorStoreMatrixGraphStore}, the in-heap {@link AdjacencyMatrixGraph}
 * cache, and startup rehydration. Serves graph operations over a loopback
 * {@link HttpServer} via {@code POST /invoke} so the main app never materialises
 * the matrix in its own heap.</p>
 *
 * <p>Launched by {@link ai.kompile.app.services.subprocess.GraphMatrixSubprocessLauncher}
 * with {@code --port=N} (default 8094). Main-app clients call
 * {@link ai.kompile.app.services.graph.SubprocessMatrixGraphStore} which delegates
 * to this server over HTTP.</p>
 *
 * <h3>/invoke protocol</h3>
 * <pre>
 * Request:  POST /invoke  Content-Type: application/json
 *           {"method":"<name>","argTypes":["<fqcn>",...],"args":[<json>,...]}
 * Response: {"ok":true,"result":<json>}
 *         | {"ok":false,"error":"<message>"}
 * </pre>
 *
 * <h3>Special serialisation rules</h3>
 * <ul>
 *   <li>{@code INDArray} (storeNodeEmbeddings arg / getNodeEmbeddings + findSimilarNodes return):
 *       {@code {"__indarray":true,"shape":[n,d],"data":[f0,f1,...]}}</li>
 *   <li>{@code loadGraph} / {@code createGraph} return an ack instead of the full matrix:
 *       {@code {"__graphack":true,"graphId":"...","nodeCount":N,"edgeCount":M}}</li>
 *   <li>{@code Optional<T>} → {@code null} (empty) or the inner value.</li>
 *   <li>{@code Map.Entry<K,V>} → {@code {"key":...,"value":...}}</li>
 *   <li>{@code saveGraph} argument is interpreted as {@code {"__saveGraphId":"<id>"}} —
 *       the subprocess looks up the REAL in-memory graph by id and persists it.</li>
 * </ul>
 */
public class GraphMatrixSubprocessMain {

    private static final Logger logger = LoggerFactory.getLogger(GraphMatrixSubprocessMain.class);
    static final long DEFAULT_MAX_REQUEST_BYTES = 8L * 1024 * 1024;
    static final long DEFAULT_MAX_RESPONSE_BYTES = 16L * 1024 * 1024;
    static final int DEFAULT_RPC_THREADS = 8;
    static final int DEFAULT_RPC_QUEUE_CAPACITY = 32;

    /** Signals that rehydrateGraphsOnStartup() has returned successfully. */
    private static volatile boolean rehydrationDone = false;

    /** Static reference to the Spring context so HTTP handlers can look up service beans. */
    private static volatile AnnotationConfigApplicationContext springContext;

    /** Cache of already-resolved service beans, keyed by interface FQCN. */
    private static final ConcurrentHashMap<String, Object> serviceCache = new ConcurrentHashMap<>();

    public static void main(String[] args) throws InterruptedException {
        NativeLibraryResolver.bootstrapOrThrow();
        int port = 8094;
        for (String arg : args) {
            if (arg.startsWith("--port=")) {
                try {
                    port = Integer.parseInt(arg.substring("--port=".length()).trim());
                } catch (NumberFormatException e) {
                    logger.warn("[graph-matrix] invalid --port value '{}', using default {}", arg, port);
                }
            }
        }

        logger.info("[graph-matrix] subprocess starting — port={}", port);

        // 1. ND4J bootstrap (CPU, mirrors GraphSubprocessMain).
        initializeNd4j();

        // 2. Boot the matrix Spring context (VectorStore + ObjectMapper + MatrixGraphStore beans).
        AnnotationConfigApplicationContext context = createContext();
        springContext = context; // expose to static HTTP handlers for service-bean dispatch

        // 3. Keep persisted graphs in Lucene and load only the graph addressed by an RPC.
        VectorStoreMatrixGraphStore store = context.getBean(VectorStoreMatrixGraphStore.class);
        MatrixGraphStore matrixStore = store;
        boolean eagerRehydration = Boolean.parseBoolean(
                System.getProperty("kompile.graph.eager-rehydration-enabled", "false"));
        if (eagerRehydration) {
            logger.warn("[graph-matrix] eager graph rehydration explicitly enabled");
            try {
                store.rehydrateGraphsOnStartup();
            } catch (Exception e) {
                logger.error("[graph-matrix] eager rehydration failed; falling back to lazy loads", e);
            }
        } else {
            logger.info("[graph-matrix] lazy graph loading enabled; Lucene remains authoritative");
        }
        rehydrationDone = true;

        // 4. Start JDK HttpServer on loopback only.
        ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
        final int finalPort = port;
        HttpServer httpServer;
        try {
            InetSocketAddress addr = new InetSocketAddress(InetAddress.getLoopbackAddress(), finalPort);
            httpServer = HttpServer.create(addr, 128);
        } catch (IOException e) {
            logger.error("[graph-matrix] failed to bind HttpServer on port {}: {}", port, e.getMessage(), e);
            context.close();
            System.exit(1);
            return;
        }

        long maxRequestBytes = positiveLongProperty("kompile.graph.subprocess.max-request-bytes", DEFAULT_MAX_REQUEST_BYTES);
        long maxResponseBytes = positiveLongProperty("kompile.graph.subprocess.max-response-bytes", DEFAULT_MAX_RESPONSE_BYTES);
        responseByteCap = maxResponseBytes;
        int rpcThreads = (int) positiveLongProperty("kompile.graph.subprocess.rpc-threads", DEFAULT_RPC_THREADS);
        int rpcQueueCapacity = (int) positiveLongProperty("kompile.graph.subprocess.rpc-queue-capacity", DEFAULT_RPC_QUEUE_CAPACITY);
        ThreadPoolExecutor rpcExecutor = new ThreadPoolExecutor(
                rpcThreads, rpcThreads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(rpcQueueCapacity),
                new ThreadPoolExecutor.AbortPolicy());

        httpServer.createContext("/health", exchange -> handleHealth(exchange));
        httpServer.createContext("/capabilities", exchange -> handleCapabilities(exchange, objectMapper));
        httpServer.createContext("/invoke", exchange -> handleInvoke(
                exchange, matrixStore, store, objectMapper, maxRequestBytes, maxResponseBytes));
        httpServer.setExecutor(rpcExecutor);
        httpServer.start();
        logger.info("[graph-matrix] HTTP server listening on 127.0.0.1:{}", finalPort);

        // 5. Shutdown hook.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("[graph-matrix] shutting down...");
            httpServer.stop(2);
            rpcExecutor.shutdownNow();
            context.close();
        }, "graph-matrix-shutdown"));

        // 6. Block forever — this is a persistent server process.
        Thread.currentThread().join();
    }

    // ── HTTP handlers ─────────────────────────────────────────────────────────

    private static void handleHealth(HttpExchange exchange) throws IOException {
        String body = rehydrationDone ? "ok" : "loading";
        int code = rehydrationDone ? 200 : 503;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(code, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void handleCapabilities(HttpExchange exchange, ObjectMapper mapper) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"ok\":false,\"error\":\"only GET /capabilities is supported\"}");
            return;
        }
        boolean authority = springContext != null
                && !springContext.getBeansOfType(GraphGenerationCoordinator.class).isEmpty();
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", 2);
        result.put("generationLifecycle", authority);
        result.put("generationRouting", authority);
        result.put("generationAuthority", authority);
        sendJson(exchange, 200, mapper.writeValueAsString(result));
    }

    static void handleInvoke(HttpExchange exchange,
                             MatrixGraphStore matrixStore,
                             VectorStoreMatrixGraphStore store,
                             ObjectMapper mapper,
                             long maxRequestBytes,
                             long maxResponseBytes) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"ok\":false,\"error\":\"only POST /invoke is supported\"}");
            return;
        }
        long contentLength = parseContentLength(exchange);
        if (contentLength > maxRequestBytes) {
            sendJson(exchange, 413, "{\"ok\":false,\"error\":\"graph RPC request exceeds byte limit\"}");
            return;
        }

        String responseJson;
        try (InputStream request = new BoundedInputStream(exchange.getRequestBody(), maxRequestBytes)) {
            JsonNode requestNode = mapper.readTree(request);
            responseJson = dispatch(requestNode, matrixStore, store, mapper);
        } catch (PayloadTooLargeException e) {
            sendJson(exchange, 413, "{\"ok\":false,\"error\":\"graph RPC request exceeds byte limit\"}");
            return;
        } catch (ResponseTooLargeException e) {
            // The serializer aborted before materializing a huge string — a ~1GB
            // getEdgesInFactSheet response previously OOM'd this whole process.
            logger.warn("[graph-matrix] refusing oversized RPC response: {}", e.getMessage());
            sendJson(exchange, 507, "{\"ok\":false,\"error\":\"graph RPC response exceeds byte limit; use a paged query\"}");
            return;
        } catch (Exception e) {
            logger.error("[graph-matrix] invoke error: {}", e.getMessage(), e);
            ObjectNode err = mapper.createObjectNode();
            err.put("ok", false);
            err.put("protocolVersion", 2);
            err.put("code", generationErrorCode(e));
            err.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            responseJson = mapper.writeValueAsString(err);
        }
        if (utf8LengthExceeds(responseJson, maxResponseBytes)) {
            logger.warn("[graph-matrix] refusing oversized RPC response (method={}, limit={} bytes)",
                    responseMethod(responseJson), maxResponseBytes);
            sendJson(exchange, 507, "{\"ok\":false,\"error\":\"graph RPC response exceeds byte limit; use a paged query\"}");
            return;
        }
        sendJson(exchange, 200, responseJson);
    }

    private static long parseContentLength(HttpExchange exchange) {
        String value = exchange.getRequestHeaders().getFirst("Content-Length");
        if (value == null || value.isBlank()) return -1L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    static boolean utf8LengthExceeds(String value, long limit) {
        if (value == null) return false;
        if (value.length() > limit) return true;
        return value.getBytes(StandardCharsets.UTF_8).length > limit;
    }

    private static String responseMethod(String response) {
        return response == null ? "unknown" : "serialized";
    }

    private static String generationErrorCode(Exception error) {
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase();
        if (message.contains("conflict") || message.contains("stale")) return "STALE_REVISION";
        if (message.contains("unsupported") || message.contains("not supported")) return "UNSUPPORTED";
        if (message.contains("generation") || message.contains("journal")) return "INVALID_GENERATION";
        return "INTERNAL_ERROR";
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

    static final class PayloadTooLargeException extends IOException {
        PayloadTooLargeException(long limit) {
            super("payload exceeds " + limit + " bytes");
        }
    }

    /** Response-side twin of {@link PayloadTooLargeException}; unchecked so the abort
     *  propagates out of Jackson's serializer without signature changes. */
    static final class ResponseTooLargeException extends RuntimeException {
        ResponseTooLargeException(long limit) {
            super("serialized response exceeds " + limit + " bytes");
        }
    }

    /** Response byte cap applied at serialization time; set once in main(). */
    static volatile long responseByteCap = DEFAULT_MAX_RESPONSE_BYTES;

    /**
     * Serialize into a capped buffer and abort as soon as the cap is crossed. The
     * post-hoc length check in handleInvoke cannot help when the result is huge:
     * building the intermediate string is itself the OOM (Jackson's TextBuffer dies
     * past 1GB and the allocations killed this process). Never buffers more than
     * {@link #responseByteCap} bytes.
     */
    static String writeCapped(ObjectMapper mapper, Object value) throws JsonProcessingException {
        CappedByteArrayOutputStream out = new CappedByteArrayOutputStream(responseByteCap);
        try {
            mapper.writeValue(out, value);
        } catch (ResponseTooLargeException e) {
            throw e;
        } catch (IOException e) {
            if (out.exceeded()) {
                throw new ResponseTooLargeException(responseByteCap);
            }
            if (e instanceof JsonProcessingException jpe) {
                throw jpe;
            }
            throw new IllegalStateException("response serialization failed: " + e.getMessage(), e);
        }
        if (out.exceeded()) {
            throw new ResponseTooLargeException(responseByteCap);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** ByteArrayOutputStream that throws on the first write crossing the cap and
     *  swallows later writes (so Jackson's flush/close on abort cannot re-throw). */
    static final class CappedByteArrayOutputStream extends java.io.ByteArrayOutputStream {
        private final long limit;
        private boolean exceeded;

        CappedByteArrayOutputStream(long limit) {
            this.limit = limit;
        }

        boolean exceeded() {
            return exceeded;
        }

        @Override
        public synchronized void write(int b) {
            if (exceeded) return;
            if (count + 1L > limit) {
                exceeded = true;
                throw new ResponseTooLargeException(limit);
            }
            super.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            if (exceeded) return;
            if (count + (long) len > limit) {
                exceeded = true;
                throw new ResponseTooLargeException(limit);
            }
            super.write(b, off, len);
        }
    }

    static final class BoundedInputStream extends InputStream {
        private final InputStream delegate;
        private final long limit;
        private long read;

        BoundedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0 && ++read > limit) throw new PayloadTooLargeException(limit);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int allowed = (int) Math.min(length, Math.max(1L, limit - read + 1L));
            int count = delegate.read(bytes, offset, allowed);
            if (count > 0 && (read += count) > limit) throw new PayloadTooLargeException(limit);
            return count;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static void sendJson(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ── Reflective dispatch ───────────────────────────────────────────────────

    /**
     * Core dispatch: parse the invoke request, find the method, deserialize args,
     * invoke on the real bean, serialise the result with special-case encoding.
     */
    private static String dispatch(JsonNode root,
                                   MatrixGraphStore matrixStore,
                                   VectorStoreMatrixGraphStore store,
                                   ObjectMapper mapper) throws Exception {
        JsonNode targetNode = root.path("generation");
        if (!targetNode.isMissingNode() && !targetNode.isNull()) {
            GraphGeneration.Target target = mapper.treeToValue(targetNode, GraphGeneration.Target.class);
            String ownerJobId = root.path("generationOwnerJobId").asText("unowned");
            GraphGenerationCoordinator coordinator = generationCoordinator();
            GraphGenerationJournal.Entry entry = coordinator.acquire(target, ownerJobId);
            try (GraphGenerationContext.Scope ignored =
                         GraphGenerationContext.open(entry.generation(), ownerJobId)) {
                return dispatchScoped(root, matrixStore, store, mapper, target);
            } finally {
                coordinator.release(target, ownerJobId);
            }
        }
        return dispatchScoped(root, matrixStore, store, mapper, null);
    }

    private static String dispatchScoped(JsonNode root,
                                         MatrixGraphStore matrixStore,
                                         VectorStoreMatrixGraphStore store,
                                         ObjectMapper mapper,
                                         GraphGeneration.Target generationTarget) throws Exception {
        String methodName = root.path("method").asText();
        JsonNode argTypesNode = root.path("argTypes");
        JsonNode argsNode = root.path("args");

        List<String> argTypeNames = new ArrayList<>();
        if (argTypesNode.isArray()) {
            for (JsonNode n : argTypesNode) argTypeNames.add(n.asText());
        }
        List<JsonNode> argJsons = new ArrayList<>();
        if (argsNode.isArray()) {
            for (JsonNode n : argsNode) argJsons.add(n);
        }

        // --- Service dispatch (GraphRagService / KnowledgeGraphService / reasoning services) ---
        if (root.has("service")) {
            String serviceFqcn = root.path("service").asText();
            return dispatchToService(serviceFqcn, methodName, argJsons, mapper);
        }

        if (generationTarget == null && firstArgumentTargetsPhysicalGeneration(methodName, argJsons)) {
            throw new IllegalStateException("A physical generation graph requires a validated generation envelope");
        }
        if (generationTarget != null) {
            bindRawGraphArgumentToGeneration(methodName, argJsons, generationTarget, mapper);
        }

        // --- Special case: saveGraph ---
        // Client sends argTypes=["saveGraph"] with args=[{"__saveGraphId":"<id>"}]
        if ("saveGraph".equals(methodName) && argTypeNames.size() == 1
                && argJsons.size() == 1 && argJsons.get(0).has("__saveGraphId")) {
            String graphId = argJsons.get(0).get("__saveGraphId").asText();
            handleSaveGraph(graphId, store, mapper);
            ObjectNode ok = mapper.createObjectNode();
            ok.put("ok", true);
            ok.putNull("result");
            return mapper.writeValueAsString(ok);
        }

        // --- Resolve method by name + arg types ---
        Class<?>[] paramTypes = resolveParamTypes(argTypeNames);
        Method method = findMethod(matrixStore.getClass(), methodName, paramTypes);

        // --- Deserialize arguments ---
        Object[] invokeArgs = deserializeArgs(method, argJsons, mapper);

        // --- Invoke ---
        Object rawResult = method.invoke(matrixStore, invokeArgs);

        // --- Serialize result with special cases ---
        return serializeResult(methodName, rawResult, mapper);
    }

    private static boolean firstArgumentTargetsPhysicalGeneration(
            String methodName, List<JsonNode> args) {
        if (args.isEmpty()) return false;
        JsonNode first = args.get(0);
        if (first.isTextual()) return first.asText().contains("~gen~");
        return "saveGraph".equals(methodName)
                && first.path("__saveGraphId").asText("").contains("~gen~");
    }

    private static void bindRawGraphArgumentToGeneration(
            String methodName, List<JsonNode> args, GraphGeneration.Target target, ObjectMapper mapper) {
        if (args.isEmpty()) return;
        JsonNode first = args.get(0);
        if (first.isTextual()) {
            String graphId = first.asText();
            if (graphId.equals(target.logicalGraphId())) {
                args.set(0, mapper.getNodeFactory().textNode(target.physicalGraphId()));
            } else if (!graphId.equals(target.physicalGraphId())) {
                throw new IllegalStateException(
                        "Generation-scoped store RPC cannot access graph " + graphId);
            }
        } else if ("saveGraph".equals(methodName) && first.isObject()) {
            String graphId = first.path("__saveGraphId").asText("");
            if (graphId.equals(target.logicalGraphId())) {
                ((ObjectNode) first).put("__saveGraphId", target.physicalGraphId());
            } else if (!graphId.equals(target.physicalGraphId())) {
                throw new IllegalStateException(
                        "Generation-scoped save cannot access graph " + graphId);
            }
        }
    }

    private static GraphGenerationCoordinator generationCoordinator() {
        if (springContext == null) throw new IllegalStateException("Graph generation authority is unavailable");
        Map<String, GraphGenerationCoordinator> coordinators =
                springContext.getBeansOfType(GraphGenerationCoordinator.class);
        if (coordinators.size() != 1) {
            throw new IllegalStateException("Graph generation authority is unavailable");
        }
        return coordinators.values().iterator().next();
    }

    private static boolean isGenerationLifecycleMethod(String method) {
        return "supportsGraphGenerations".equals(method)
                || "beginFactSheetGeneration".equals(method)
                || "validateFactSheetGeneration".equals(method)
                || "activateFactSheetGeneration".equals(method)
                || "abortFactSheetGeneration".equals(method)
                || "rollbackFactSheetGeneration".equals(method)
                || "getFactSheetGenerationStatus".equals(method);
    }

    /**
     * Explicit (reflection-free) dispatch to service beans.
     * Switches on the interface FQCN, then on the method name.
     * Args are deserialized with concrete compile-time types; beans are looked up once and cached.
     */
    private static String dispatchToService(String serviceFqcn, String methodName,
                                             List<JsonNode> argJsons,
                                             ObjectMapper mapper) throws Exception {
        if (springContext == null) {
            throw new IllegalStateException("[graph-matrix] Spring context not yet ready for service dispatch");
        }
        return switch (serviceFqcn) {
            case "ai.kompile.knowledgegraph.service.KnowledgeGraphService" -> {
                KnowledgeGraphService svc = (KnowledgeGraphService) serviceCache.computeIfAbsent(
                        serviceFqcn, k -> springContext.getBean(KnowledgeGraphService.class));
                yield dispatchKnowledgeGraphService(
                        svc, isGenerationLifecycleMethod(methodName) ? generationCoordinator() : null,
                        methodName, argJsons, mapper);
            }
            case "ai.kompile.core.graphrag.GraphRagService" -> {
                GraphRagService svc = (GraphRagService) serviceCache.computeIfAbsent(
                        serviceFqcn, k -> springContext.getBean(GraphRagService.class));
                yield dispatchGraphRagService(svc, methodName, argJsons, mapper);
            }
            case "ai.kompile.event.attribution.service.BayesianNetworkService" -> {
                BayesianNetworkService svc = (BayesianNetworkService) serviceCache.computeIfAbsent(
                        serviceFqcn, k -> springContext.getBean(BayesianNetworkService.class));
                yield dispatchBayesianNetworkService(svc, methodName, argJsons, mapper);
            }
            case "ai.kompile.event.attribution.service.PslReasoningService" -> {
                PslReasoningService svc = (PslReasoningService) serviceCache.computeIfAbsent(
                        serviceFqcn, k -> springContext.getBean(PslReasoningService.class));
                yield dispatchPslReasoningService(svc, methodName, argJsons, mapper);
            }
            case "ai.kompile.event.attribution.service.EventAttributionService" -> {
                EventAttributionService svc = (EventAttributionService) serviceCache.computeIfAbsent(
                        serviceFqcn, k -> springContext.getBean(EventAttributionService.class));
                yield dispatchEventAttributionService(svc, methodName, argJsons, mapper);
            }
            default -> throw new IllegalArgumentException(
                    "[graph-matrix] unknown service FQCN: " + serviceFqcn);
        };
    }

    // ── Per-arg helper (safe null-aware JSON→Java coercion) ──────────────────

    private static JsonNode arg(List<JsonNode> args, int idx) {
        return (idx < args.size() && args.get(idx) != null) ? args.get(idx) : null;
    }
    private static boolean isNullArg(List<JsonNode> args, int idx) {
        JsonNode n = arg(args, idx);
        return n == null || n.isNull();
    }
    private static Long argLong(List<JsonNode> args, int idx) {
        return isNullArg(args, idx) ? null : args.get(idx).asLong();
    }
    private static String argStr(List<JsonNode> args, int idx) {
        return isNullArg(args, idx) ? null : args.get(idx).asText();
    }
    private static int argInt(List<JsonNode> args, int idx) {
        return isNullArg(args, idx) ? 0 : args.get(idx).asInt();
    }
    private static double argDouble(List<JsonNode> args, int idx) {
        return isNullArg(args, idx) ? 0.0 : args.get(idx).asDouble();
    }
    private static boolean argBool(List<JsonNode> args, int idx) {
        return isNullArg(args, idx) ? false : args.get(idx).asBoolean();
    }
    private static <T> T argObj(List<JsonNode> args, int idx, Class<T> cls, ObjectMapper m) throws Exception {
        return isNullArg(args, idx) ? null : m.convertValue(args.get(idx), cls);
    }
    private static <T> List<T> argList(List<JsonNode> args, int idx, Class<T> elemClass,
                                       ObjectMapper m) throws Exception {
        if (isNullArg(args, idx)) return List.of();
        JavaType t = m.getTypeFactory().constructCollectionType(List.class, elemClass);
        return m.convertValue(args.get(idx), t);
    }
    private static <T> Set<T> argSet(List<JsonNode> args, int idx, Class<T> elemClass,
                                     ObjectMapper m) throws Exception {
        if (isNullArg(args, idx)) return Set.of();
        JavaType t = m.getTypeFactory().constructCollectionType(Set.class, elemClass);
        return m.convertValue(args.get(idx), t);
    }
    private static Map<String, Object> argMapStrObj(List<JsonNode> args, int idx,
                                                     ObjectMapper m) throws Exception {
        if (isNullArg(args, idx)) return Map.of();
        JavaType t = m.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
        return m.convertValue(args.get(idx), t);
    }
    private static Map<String, Integer> argMapStrInt(List<JsonNode> args, int idx,
                                                     ObjectMapper m) throws Exception {
        if (isNullArg(args, idx)) return Map.of();
        JavaType t = m.getTypeFactory().constructMapType(Map.class, String.class, Integer.class);
        return m.convertValue(args.get(idx), t);
    }
    private static Map<String, Double> argMapStrDouble(List<JsonNode> args, int idx,
                                                       ObjectMapper m) throws Exception {
        if (isNullArg(args, idx)) return Map.of();
        JavaType t = m.getTypeFactory().constructMapType(Map.class, String.class, Double.class);
        return m.convertValue(args.get(idx), t);
    }
    private static INDArray argINDArray(List<JsonNode> args, int idx) {
        return isNullArg(args, idx) ? null : decodeINDArray(args.get(idx));
    }
    private static Map<String, INDArray> argINDArrayMap(List<JsonNode> args, int idx) {
        if (isNullArg(args, idx)) return Map.of();
        return decodeINDArrayMap(args.get(idx));
    }

    // ── GraphRagService dispatcher ────────────────────────────────────────────

    private static String dispatchGraphRagService(GraphRagService svc, String method,
                                                   List<JsonNode> args,
                                                   ObjectMapper mapper) throws Exception {
        Object rawResult = switch (method) {

            case "answerQuery" -> svc.answerQuery(argObj(args, 0, GraphRagQuery.class, mapper));
            default -> throw new IllegalArgumentException(
                    "[graph-matrix] GraphRagService: unknown method: " + method);
        };
        return serializeResult(method, rawResult, mapper);
    }

    // ── Reasoning service dispatchers ─────────────────────────────────────────

    private static String dispatchBayesianNetworkService(BayesianNetworkService svc, String method,
                                                         List<JsonNode> args,
                                                         ObjectMapper mapper) throws Exception {
        Object rawResult = switch (method) {
            case "queryMebnFromKg" -> {
                Collection<String> seeds = argList(args, 0, String.class, mapper);
                Map<String, Integer> evidence = argMapStrInt(args, 1, mapper);
                int maxDepth = argInt(args, 2);
                int maxNodes = argInt(args, 3);
                if (args.size() <= 4 || isNullArg(args, 4)) {
                    yield svc.queryMebnFromKg(seeds, evidence, maxDepth, maxNodes);
                }
                TypeHierarchy hierarchy = argObj(args, 4, TypeHierarchy.class, mapper);
                yield svc.queryMebnFromKg(seeds, evidence, maxDepth, maxNodes, hierarchy);
            }
            case "queryAllPosteriors" -> svc.queryAllPosteriors(
                    argList(args, 0, String.class, mapper),
                    argMapStrInt(args, 1, mapper),
                    argInt(args, 2), argInt(args, 3));
            case "queryPosterior" -> svc.queryPosterior(
                    argList(args, 0, String.class, mapper), argStr(args, 1),
                    argMapStrInt(args, 2, mapper), argInt(args, 3), argInt(args, 4));
            case "mostProbableExplanation" -> svc.mostProbableExplanation(
                    argList(args, 0, String.class, mapper),
                    argMapStrInt(args, 1, mapper), argInt(args, 2), argInt(args, 3));
            case "sensitivityAnalysis" -> svc.sensitivityAnalysis(
                    argList(args, 0, String.class, mapper), argStr(args, 1),
                    argMapStrInt(args, 2, mapper), argDouble(args, 3), argInt(args, 4), argInt(args, 5));
            case "getMebnStatistics" -> svc.getMebnStatistics(
                    argList(args, 0, String.class, mapper), argInt(args, 1), argInt(args, 2));
            case "buildMebnTheory" -> svc.buildMebnTheory(
                    argList(args, 0, String.class, mapper), argInt(args, 1), argInt(args, 2));
            default -> throw new IllegalArgumentException(
                    "[graph-matrix] BayesianNetworkService: unknown method: " + method);
        };
        return serializeResult(method, rawResult, mapper);
    }

    private static String dispatchPslReasoningService(PslReasoningService svc, String method,
                                                       List<JsonNode> args,
                                                       ObjectMapper mapper) throws Exception {
        Object rawResult = switch (method) {
            case "infer" -> svc.infer(
                    argList(args, 0, String.class, mapper), argMapStrDouble(args, 1, mapper),
                    argInt(args, 2), argInt(args, 3));
            case "inferWithRules" -> svc.inferWithRules(
                    argList(args, 0, String.class, mapper), argList(args, 1, String.class, mapper),
                    argMapStrDouble(args, 2, mapper), argInt(args, 3), argInt(args, 4));
            case "programStatistics" -> svc.programStatistics(
                    argList(args, 0, String.class, mapper), argInt(args, 1), argInt(args, 2));
            default -> throw new IllegalArgumentException(
                    "[graph-matrix] PslReasoningService: unknown method: " + method);
        };
        return serializeResult(method, rawResult, mapper);
    }

    private static String dispatchEventAttributionService(EventAttributionService svc, String method,
                                                          List<JsonNode> args,
                                                          ObjectMapper mapper) throws Exception {
        Object rawResult = switch (method) {
            case "explain" -> svc.explain(argObj(args, 0, AttributionQuery.class, mapper));
            default -> throw new IllegalArgumentException(
                    "[graph-matrix] EventAttributionService: unknown method: " + method);
        };
        return serializeResult(method, rawResult, mapper);
    }

    // ── Native-safe ExternalNodeLookup wire codec ─────────────────────────────

    /**
     * Encode interface-owned record lists as Jackson tree nodes made only from built-in JSON values.
     * The main native image must never ask Jackson to reflect over these records.
     */
    public static ArrayNode encodeKnowledgeGraphRecordList(List<?> records, ObjectMapper mapper) {
        ArrayNode encoded = mapper.createArrayNode();
        if (records == null) {
            return encoded;
        }
        for (Object record : records) {
            if (record == null) {
                encoded.addNull();
                continue;
            }
            ObjectNode row = mapper.createObjectNode();
            if (record instanceof KnowledgeGraphService.SnippetSpec spec) {
                putNullableText(row, "parentExternalId", spec.parentExternalId());
                putNullableLong(row, "parentFactSheetId", spec.parentFactSheetId());
                putNullableText(row, "snippetId", spec.snippetId());
                putNullableText(row, "content", spec.content());
                row.put("chunkIndex", spec.chunkIndex());
            } else if (record instanceof KnowledgeGraphService.NodeSpec spec) {
                putNullableEnum(row, "nodeType", spec.nodeType());
                putNullableText(row, "externalId", spec.externalId());
                putNullableText(row, "title", spec.title());
                putNullableText(row, "description", spec.description());
                putNullableMap(row, "metadata", spec.metadata(), mapper);
            } else if (record instanceof KnowledgeGraphService.ExternalNodeLookup lookup) {
                putNullableText(row, "externalId", lookup.externalId());
                putNullableEnum(row, "nodeType", lookup.nodeType());
                putNullableLong(row, "factSheetId", lookup.factSheetId());
            } else if (record instanceof KnowledgeGraphService.EdgeSpec spec) {
                putNullableText(row, "sourceNodeId", spec.sourceNodeId());
                putNullableText(row, "targetNodeId", spec.targetNodeId());
                putNullableEnum(row, "edgeType", spec.edgeType());
                putNullableDouble(row, "weight", spec.weight());
                putNullableText(row, "description", spec.description());
                putNullableText(row, "label", spec.label());
                putNullableText(row, "metaJson", spec.metaJson());
                putNullableEnum(row, "provenance", spec.provenance());
                putNullableLong(row, "factSheetId", spec.factSheetId());
            } else if (record instanceof KnowledgeGraphService.NodeUpdate update) {
                putNullableText(row, "nodeId", update.nodeId());
                putNullableText(row, "title", update.title());
                putNullableText(row, "description", update.description());
                putNullableMap(row, "additionalMetadata", update.additionalMetadata(), mapper);
            } else if (record instanceof KnowledgeGraphService.NodeMetadataUpdate update) {
                putNullableText(row, "nodeId", update.nodeId());
                putNullableMap(row, "additionalMetadata", update.additionalMetadata(), mapper);
            } else if (record instanceof KnowledgeGraphService.EdgeMetadataUpdate update) {
                putNullableText(row, "edgeId", update.edgeId());
                putNullableMap(row, "additionalMetadata", update.additionalMetadata(), mapper);
            } else {
                throw new IllegalArgumentException(
                        "[graph-matrix] unsupported native record payload: " + record.getClass().getName());
            }
            encoded.add(row);
        }
        return encoded;
    }

    public static ArrayNode encodeExternalNodeLookups(
            List<KnowledgeGraphService.ExternalNodeLookup> lookups,
            ObjectMapper mapper) {
        return encodeKnowledgeGraphRecordList(lookups, mapper);
    }

    private static void putNullableText(ObjectNode row, String field, String value) {
        if (value == null) row.putNull(field); else row.put(field, value);
    }

    private static void putNullableLong(ObjectNode row, String field, Long value) {
        if (value == null) row.putNull(field); else row.put(field, value);
    }

    private static void putNullableDouble(ObjectNode row, String field, Double value) {
        if (value == null) row.putNull(field); else row.put(field, value);
    }

    private static void putNullableEnum(ObjectNode row, String field, Enum<?> value) {
        if (value == null) row.putNull(field); else row.put(field, value.name());
    }

    private static void putNullableMap(ObjectNode row, String field, Map<String, Object> value,
                                       ObjectMapper mapper) {
        row.set(field, encodeNativeJsonValue(value, mapper));
    }

    /**
     * Encodes only JSON-native values so graph batch metadata never falls back to Jackson bean
     * discovery in a native image. Unsupported application objects fail at the RPC boundary with
     * a concrete error instead of requiring reflective serialization metadata.
     */
    static JsonNode encodeNativeJsonValue(Object value, ObjectMapper mapper) {
        if (value == null) {
            return mapper.getNodeFactory().nullNode();
        }
        if (value instanceof JsonNode node) {
            return copyNativeJsonNode(node, mapper);
        }
        if (value instanceof Enum<?> enumValue) {
            return mapper.getNodeFactory().textNode(enumValue.name());
        }
        if (value instanceof CharSequence || value instanceof Character) {
            return mapper.getNodeFactory().textNode(value.toString());
        }
        if (value instanceof Boolean booleanValue) {
            return mapper.getNodeFactory().booleanNode(booleanValue);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return mapper.getNodeFactory().numberNode(((Number) value).intValue());
        }
        if (value instanceof Long longValue) {
            return mapper.getNodeFactory().numberNode(longValue);
        }
        if (value instanceof BigInteger bigInteger) {
            return mapper.getNodeFactory().numberNode(bigInteger);
        }
        if (value instanceof Float floatValue) {
            return mapper.getNodeFactory().numberNode(floatValue);
        }
        if (value instanceof Double doubleValue) {
            return mapper.getNodeFactory().numberNode(doubleValue);
        }
        if (value instanceof BigDecimal bigDecimal) {
            return mapper.getNodeFactory().numberNode(bigDecimal);
        }
        if (value instanceof Map<?, ?> mapValue) {
            ObjectNode encoded = mapper.createObjectNode();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(
                            "[graph-matrix] native JSON metadata map keys must be strings: "
                                    + entry.getKey());
                }
                encoded.set(key, encodeNativeJsonValue(entry.getValue(), mapper));
            }
            return encoded;
        }
        if (value instanceof Collection<?> collectionValue) {
            ArrayNode encoded = mapper.createArrayNode();
            for (Object item : collectionValue) {
                encoded.add(encodeNativeJsonValue(item, mapper));
            }
            return encoded;
        }
        throw new IllegalArgumentException(
                "[graph-matrix] unsupported native JSON metadata value: "
                        + value.getClass().getName());
    }

    private static JsonNode copyNativeJsonNode(JsonNode node, ObjectMapper mapper) {
        if (node == null || node.isNull()) {
            return mapper.getNodeFactory().nullNode();
        }
        if (node.isPojo()) {
            throw new IllegalArgumentException(
                    "[graph-matrix] unsupported native JSON metadata node: "
                            + node.getClass().getName());
        }
        if (node.isObject()) {
            ObjectNode encoded = mapper.createObjectNode();
            node.fields().forEachRemaining(entry ->
                    encoded.set(entry.getKey(), copyNativeJsonNode(entry.getValue(), mapper)));
            return encoded;
        }
        if (node.isArray()) {
            ArrayNode encoded = mapper.createArrayNode();
            for (JsonNode item : node) {
                encoded.add(copyNativeJsonNode(item, mapper));
            }
            return encoded;
        }
        if (node.isValueNode()) {
            return node.deepCopy();
        }
        throw new IllegalArgumentException(
                "[graph-matrix] unsupported native JSON metadata node: "
                        + node.getClass().getName());
    }

    private static void requireRecordArray(JsonNode payload, String operation) {
        if (payload != null && !payload.isNull() && !payload.isArray()) {
            throw new IllegalArgumentException(
                    "[graph-matrix] " + operation + " expects an array payload");
        }
    }

    private static ObjectNode requireRecordObject(JsonNode row, String operation) {
        if (!row.isObject()) {
            throw new IllegalArgumentException(
                    "[graph-matrix] " + operation + " rows must be JSON objects");
        }
        return (ObjectNode) row;
    }

    private static String nullableText(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long nullableLong(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private static Double nullableDouble(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asDouble();
    }

    private static int integerValue(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? 0 : value.asInt();
    }

    private static <E extends Enum<E>> E nullableEnum(JsonNode row, String field, Class<E> type) {
        String value = nullableText(row, field);
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static Object decodeBuiltInJson(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isObject()) {
            Map<String, Object> decoded = new LinkedHashMap<>();
            value.fields().forEachRemaining(entry ->
                    decoded.put(entry.getKey(), decodeBuiltInJson(entry.getValue())));
            return decoded;
        }
        if (value.isArray()) {
            List<Object> decoded = new ArrayList<>(value.size());
            value.forEach(item -> decoded.add(decodeBuiltInJson(item)));
            return decoded;
        }
        if (value.isTextual()) {
            return value.textValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            return value.numberValue();
        }
        throw new IllegalArgumentException(
                "[graph-matrix] unsupported built-in JSON value: " + value.getNodeType());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nullableMap(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException(
                    "[graph-matrix] field '" + field + "' must be a JSON object");
        }
        return (Map<String, Object>) decodeBuiltInJson(value);
    }

    static List<KnowledgeGraphService.ExternalNodeLookup> decodeExternalNodeLookups(JsonNode payload) {
        requireRecordArray(payload, "getNodesByExternalIds");
        if (payload == null || payload.isNull()) {
            return List.of();
        }
        List<KnowledgeGraphService.ExternalNodeLookup> decoded = new ArrayList<>(payload.size());
        for (JsonNode row : payload) {
            if (row == null || row.isNull()) {
                decoded.add(null);
                continue;
            }
            ObjectNode record = requireRecordObject(row, "getNodesByExternalIds");
            decoded.add(new KnowledgeGraphService.ExternalNodeLookup(
                    nullableText(record, "externalId"),
                    nullableEnum(record, "nodeType", NodeLevel.class),
                    nullableLong(record, "factSheetId")));
        }
        return decoded;
    }

    static List<KnowledgeGraphService.NodeSpec> decodeNodeSpecs(JsonNode payload) {
        requireRecordArray(payload, "createNodesBatch");
        if (payload == null || payload.isNull()) {
            return List.of();
        }
        List<KnowledgeGraphService.NodeSpec> decoded = new ArrayList<>(payload.size());
        for (JsonNode row : payload) {
            if (row == null || row.isNull()) {
                decoded.add(null);
                continue;
            }
            ObjectNode record = requireRecordObject(row, "createNodesBatch");
            decoded.add(new KnowledgeGraphService.NodeSpec(
                    nullableEnum(record, "nodeType", NodeLevel.class),
                    nullableText(record, "externalId"),
                    nullableText(record, "title"),
                    nullableText(record, "description"),
                    nullableMap(record, "metadata")));
        }
        return decoded;
    }

    static List<KnowledgeGraphService.NodeMetadataUpdate> decodeNodeMetadataUpdates(JsonNode payload) {
        requireRecordArray(payload, "updateNodeKgeMetadataBatch");
        if (payload == null || payload.isNull()) {
            return List.of();
        }
        List<KnowledgeGraphService.NodeMetadataUpdate> decoded = new ArrayList<>(payload.size());
        for (JsonNode row : payload) {
            if (row == null || row.isNull()) {
                decoded.add(null);
                continue;
            }
            ObjectNode record = requireRecordObject(row, "updateNodeKgeMetadataBatch");
            decoded.add(new KnowledgeGraphService.NodeMetadataUpdate(
                    nullableText(record, "nodeId"),
                    nullableMap(record, "additionalMetadata")));
        }
        return decoded;
    }

    static List<KnowledgeGraphService.NodeUpdate> decodeNodeUpdates(JsonNode payload) {
        requireRecordArray(payload, "updateNodesBatch");
        if (payload == null || payload.isNull()) {
            return List.of();
        }
        List<KnowledgeGraphService.NodeUpdate> decoded = new ArrayList<>(payload.size());
        for (JsonNode row : payload) {
            if (row == null || row.isNull()) {
                decoded.add(null);
                continue;
            }
            ObjectNode record = requireRecordObject(row, "updateNodesBatch");
            decoded.add(new KnowledgeGraphService.NodeUpdate(
                    nullableText(record, "nodeId"),
                    nullableText(record, "title"),
                    nullableText(record, "description"),
                    nullableMap(record, "additionalMetadata")));
        }
        return decoded;
    }

    static List<KnowledgeGraphService.SnippetSpec> decodeSnippetSpecs(JsonNode payload) {
        requireRecordArray(payload, "createSnippetNodesBatch");
        if (payload == null || payload.isNull()) {
            return List.of();
        }
        List<KnowledgeGraphService.SnippetSpec> decoded = new ArrayList<>(payload.size());
        for (JsonNode row : payload) {
            if (row == null || row.isNull()) {
                decoded.add(null);
                continue;
            }
            ObjectNode record = requireRecordObject(row, "createSnippetNodesBatch");
            decoded.add(new KnowledgeGraphService.SnippetSpec(
                    nullableText(record, "parentExternalId"),
                    nullableLong(record, "parentFactSheetId"),
                    nullableText(record, "snippetId"),
                    nullableText(record, "content"),
                    integerValue(record, "chunkIndex")));
        }
        return decoded;
    }

    static List<KnowledgeGraphService.EdgeSpec> decodeEdgeSpecs(JsonNode payload) {
        requireRecordArray(payload, "createEdgesBatch");
        if (payload == null || payload.isNull()) {
            return List.of();
        }
        List<KnowledgeGraphService.EdgeSpec> decoded = new ArrayList<>(payload.size());
        for (JsonNode row : payload) {
            if (row == null || row.isNull()) {
                decoded.add(null);
                continue;
            }
            ObjectNode record = requireRecordObject(row, "createEdgesBatch");
            decoded.add(new KnowledgeGraphService.EdgeSpec(
                    nullableText(record, "sourceNodeId"),
                    nullableText(record, "targetNodeId"),
                    nullableEnum(record, "edgeType", EdgeType.class),
                    nullableDouble(record, "weight"),
                    nullableText(record, "description"),
                    nullableText(record, "label"),
                    nullableText(record, "metaJson"),
                    nullableEnum(record, "provenance", EdgeProvenance.class),
                    nullableLong(record, "factSheetId")));
        }
        return decoded;
    }

    static List<KnowledgeGraphService.EdgeMetadataUpdate> decodeEdgeMetadataUpdates(JsonNode payload) {
        requireRecordArray(payload, "updateEdgeMetadataBatch");
        if (payload == null || payload.isNull()) {
            return List.of();
        }
        List<KnowledgeGraphService.EdgeMetadataUpdate> decoded = new ArrayList<>(payload.size());
        for (JsonNode row : payload) {
            if (row == null || row.isNull()) {
                decoded.add(null);
                continue;
            }
            ObjectNode record = requireRecordObject(row, "updateEdgeMetadataBatch");
            decoded.add(new KnowledgeGraphService.EdgeMetadataUpdate(
                    nullableText(record, "edgeId"),
                    nullableMap(record, "additionalMetadata")));
        }
        return decoded;
    }

    // ── KnowledgeGraphService dispatcher ─────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    static String dispatchKnowledgeGraphService(KnowledgeGraphService svc,
                                                GraphGenerationCoordinator coordinator,
                                                String method, List<JsonNode> args,
                                                ObjectMapper mapper) throws Exception {
        Object rawResult = switch (method) {

            // ── Authoritative graph-generation lifecycle ───────────────────────

            case "supportsGraphGenerations" -> true;
            case "beginFactSheetGeneration" -> coordinator.begin(
                    argLong(args, 0), "factsheet_" + argLong(args, 0), argStr(args, 1),
                    args.size() > 2 ? argStr(args, 2) : "unowned");
            case "validateFactSheetGeneration" -> coordinator.validate(
                    argObj(args, 0, GraphGeneration.Ref.class, mapper));
            case "activateFactSheetGeneration" -> coordinator.activate(
                    argObj(args, 0, GraphGeneration.Ref.class, mapper), argStr(args, 1));
            case "abortFactSheetGeneration" -> {
                String failure = args.size() > 1 && !isNullArg(args, 1) ? argStr(args, 1) : null;
                coordinator.abort(
                        argObj(args, 0, GraphGeneration.Ref.class, mapper),
                        failure == null ? null : new IllegalStateException(failure));
                yield null;
            }
            case "rollbackFactSheetGeneration" -> coordinator.rollback(
                    argLong(args, 0), "factsheet_" + argLong(args, 0), argLong(args, 1), argStr(args, 2));
            case "getFactSheetGenerationStatus" -> coordinator.status(
                    "factsheet_" + argLong(args, 0));

            // ── Node management ───────────────────────────────────────────────

            case "createOrUpdateSourceNode" -> svc.createOrUpdateSourceNode(
                    argStr(args, 0), argStr(args, 1), argStr(args, 2),
                    argStr(args, 3), argMapStrObj(args, 4, mapper));

            case "createDocumentNode" -> svc.createDocumentNode(
                    argObj(args, 0, GraphNode.class, mapper),
                    argStr(args, 1), argStr(args, 2),
                    argMapStrObj(args, 3, mapper));

            case "createSnippetNode" -> {
                GraphNode doc = argObj(args, 0, GraphNode.class, mapper);
                String snippetId = argStr(args, 1);
                String content   = argStr(args, 2);
                int    chunkIdx  = argInt(args, 3);
                if (args.size() <= 4) {
                    yield svc.createSnippetNode(doc, snippetId, content, chunkIdx);
                } else {
                    yield svc.createSnippetNode(doc, snippetId, content, chunkIdx,
                            argMapStrObj(args, 4, mapper));
                }
            }

            case "createTableNode" -> svc.createTableNode(
                    argStr(args, 0), argStr(args, 1), argStr(args, 2),
                    argInt(args, 3), argInt(args, 4),
                    argList(args, 5, String.class, mapper),
                    argStr(args, 6), argMapStrObj(args, 7, mapper));

            case "createNode" -> {
                NodeLevel nodeType  = argObj(args, 0, NodeLevel.class, mapper);
                String externalId   = argStr(args, 1);
                String title        = argStr(args, 2);
                String description  = argStr(args, 3);
                Map<String, Object> meta = argMapStrObj(args, 4, mapper);
                if (args.size() <= 5) {
                    yield svc.createNode(nodeType, externalId, title, description, meta);
                } else {
                    yield svc.createNode(nodeType, externalId, title, description, meta, argLong(args, 5));
                }
            }

            case "createNodesBatch" ->
                    svc.createNodesBatch(decodeNodeSpecs(arg(args, 0)), argLong(args, 1));

            case "updateNodeKgeMetadataBatch" ->
                    svc.updateNodeKgeMetadataBatch(decodeNodeMetadataUpdates(arg(args, 0)));

            case "updateNodesBatch" ->
                    svc.updateNodesBatch(decodeNodeUpdates(arg(args, 0)));

            case "createSnippetNodesBatch" ->
                    svc.createSnippetNodesBatch(decodeSnippetSpecs(arg(args, 0)));

            case "getNode" -> svc.getNode(argStr(args, 0));

            case "getNodeByExternalId" -> {
                String extId   = argStr(args, 0);
                NodeLevel type = argObj(args, 1, NodeLevel.class, mapper);
                if (args.size() <= 2) {
                    yield svc.getNodeByExternalId(extId, type);
                } else {
                    yield svc.getNodeByExternalId(extId, type, argLong(args, 2));
                }
            }

            case "getNodesByExternalIds" ->
                    svc.getNodesByExternalIds(decodeExternalNodeLookups(arg(args, 0)));

            case "getChildren" -> svc.getChildren(argStr(args, 0));

            case "updateNode" -> svc.updateNode(argStr(args, 0), argStr(args, 1),
                    argStr(args, 2), argMapStrObj(args, 3, mapper));

            case "deleteNode" -> {
                svc.deleteNode(argStr(args, 0));
                yield null;
            }

            case "getAllSources" -> svc.getAllSources();

            case "findFactSheetIds" -> svc.findFactSheetIds();

            case "getAllNodes" -> svc.getAllNodes(argInt(args, 0));

            case "searchNodes" -> svc.searchNodes(argStr(args, 0),
                    argObj(args, 1, NodeLevel.class, mapper), argInt(args, 2));

            case "getNodesByType" -> {
                NodeLevel type = argObj(args, 0, NodeLevel.class, mapper);
                if (args.size() <= 1) {
                    yield svc.getNodesByType(type);
                } else {
                    yield svc.getNodesByType(type, argInt(args, 1));
                }
            }

            case "getNodesByIds" -> svc.getNodesByIds(argList(args, 0, String.class, mapper));

            case "saveNode" -> svc.saveNode(argObj(args, 0, GraphNode.class, mapper));

            case "findNodeById" -> svc.findNodeById(argStr(args, 0));

            // ── Fact-sheet-scoped node queries ────────────────────────────────

            case "getNodesByTypeInFactSheet" -> svc.getNodesByTypeInFactSheet(
                    argLong(args, 0), argObj(args, 1, NodeLevel.class, mapper));

            case "getNodesInFactSheet" -> svc.getNodesInFactSheet(argLong(args, 0));

            case "getNodesInFactSheetPage" -> svc.getNodesInFactSheetPage(
                    argLong(args, 0), argInt(args, 1), argInt(args, 2));

            case "getSourcesInFactSheet" -> svc.getSourcesInFactSheet(argLong(args, 0));

            case "getNodeByExternalIdInFactSheet" -> svc.getNodeByExternalIdInFactSheet(
                    argStr(args, 0), argObj(args, 1, NodeLevel.class, mapper), argLong(args, 2));

            case "searchNodesInFactSheet" -> svc.searchNodesInFactSheet(
                    argLong(args, 0), argStr(args, 1), argInt(args, 2));

            case "searchNodesInFactSheetByType" -> svc.searchNodesInFactSheetByType(
                    argLong(args, 0), argStr(args, 1),
                    argObj(args, 2, NodeLevel.class, mapper), argInt(args, 3));

            case "searchNodesGlobal" -> svc.searchNodesGlobal(
                    argStr(args, 0), argObj(args, 1, NodeLevel.class, mapper), argInt(args, 2));

            // ── Edge management ───────────────────────────────────────────────

            case "createEdge" -> {
                // 5-arg: (sourceId, targetId, edgeType, weight, description)
                // 6-arg: (sourceId, targetId, edgeType, relationType, weight, description)
                String src  = argStr(args, 0);
                String tgt  = argStr(args, 1);
                EdgeType et = argObj(args, 2, EdgeType.class, mapper);
                if (args.size() <= 5) {
                    Double weight = isNullArg(args, 3) ? null : args.get(3).asDouble();
                    yield svc.createEdge(src, tgt, et, weight, argStr(args, 4));
                } else {
                    String relationType = argStr(args, 3);
                    Double weight       = isNullArg(args, 4) ? null : args.get(4).asDouble();
                    yield svc.createEdge(src, tgt, et, relationType, weight, argStr(args, 5));
                }
            }

            case "createEdgeWithMetadata" -> {
                Double weight = isNullArg(args, 3) ? null : args.get(3).asDouble();
                yield svc.createEdgeWithMetadata(
                        argStr(args, 0), argStr(args, 1),
                        argObj(args, 2, EdgeType.class, mapper),
                        weight, argStr(args, 4), argStr(args, 5), argStr(args, 6),
                        argObj(args, 7, EdgeProvenance.class, mapper), argLong(args, 8));
            }

            case "createEdgesBatch" ->
                    svc.createEdgesBatch(decodeEdgeSpecs(arg(args, 0)));

            case "updateEdgeMetadataBatch" ->
                    svc.updateEdgeMetadataBatch(decodeEdgeMetadataUpdates(arg(args, 0)));

            case "addDocument" -> svc.addDocument(
                    argStr(args, 0), argStr(args, 1), argStr(args, 2),
                    argStr(args, 3), argStr(args, 4), argStr(args, 5),
                    argMapStrObj(args, 6, mapper), argLong(args, 7));

            case "getEdge" -> svc.getEdge(argStr(args, 0));

            case "getEdgesForNode" -> svc.getEdgesForNode(argStr(args, 0));

            case "getEdgesByType" -> svc.getEdgesByType(
                    argStr(args, 0), argObj(args, 1, EdgeType.class, mapper));

            case "updateEdge" -> {
                Double w = isNullArg(args, 1) ? null : args.get(1).asDouble();
                yield svc.updateEdge(argStr(args, 0), w, argStr(args, 2));
            }

            case "deleteEdge" -> {
                svc.deleteEdge(argStr(args, 0));
                yield null;
            }

            case "deleteEdgesBulk" -> {
                svc.deleteEdgesBulk(argList(args, 0, String.class, mapper));
                yield null;
            }

            case "edgeExists" -> {
                // 2-arg: (sourceId, targetId)
                // 5-arg: (sourceId, targetId, edgeType, label, factSheetId)
                if (args.size() <= 2) {
                    yield svc.edgeExists(argStr(args, 0), argStr(args, 1));
                } else {
                    yield svc.edgeExists(argStr(args, 0), argStr(args, 1),
                            argObj(args, 2, EdgeType.class, mapper),
                            argStr(args, 3), argLong(args, 4));
                }
            }

            case "searchEdges" -> svc.searchEdges(
                    argStr(args, 0), argObj(args, 1, EdgeType.class, mapper), argInt(args, 2));

            case "saveEdge" -> svc.saveEdge(argObj(args, 0, GraphEdge.class, mapper));

            // ── Fact-sheet-scoped edge queries ────────────────────────────────

            case "getEdgesForNodeInFactSheet" -> svc.getEdgesForNodeInFactSheet(
                    argStr(args, 0), argLong(args, 1));

            case "edgeExistsInFactSheet" -> svc.edgeExistsInFactSheet(
                    argStr(args, 0), argStr(args, 1), argLong(args, 2));

            case "getEdgesInFactSheet" -> svc.getEdgesInFactSheet(argLong(args, 0));

            case "getEdgesInFactSheetPage" -> svc.getEdgesInFactSheetPage(
                    argLong(args, 0), argInt(args, 1), argInt(args, 2));

            case "getEdgesByTypeInFactSheet" -> svc.getEdgesByTypeInFactSheet(
                    argLong(args, 0), argObj(args, 1, EdgeType.class, mapper));

            case "findEdgeBetweenNodes" -> svc.findEdgeBetweenNodes(argStr(args, 0), argStr(args, 1));

            case "findEdgeBetweenNodesBidirectional" -> svc.findEdgeBetweenNodesBidirectional(
                    argStr(args, 0), argStr(args, 1));

            case "getStrongEdgesByTypeInFactSheet" -> {
                Double minWeight = isNullArg(args, 2) ? null : args.get(2).asDouble();
                yield svc.getStrongEdgesByTypeInFactSheet(argLong(args, 0),
                        argObj(args, 1, EdgeType.class, mapper), minWeight, argInt(args, 3));
            }

            case "getStrongEdgesByType" -> {
                Double minWeight = isNullArg(args, 1) ? null : args.get(1).asDouble();
                yield svc.getStrongEdgesByType(
                        argObj(args, 0, EdgeType.class, mapper), minWeight, argInt(args, 2));
            }

            // ── Graph traversal ───────────────────────────────────────────────

            case "getConnectedNodes" -> svc.getConnectedNodes(argStr(args, 0), argInt(args, 1));

            case "findRelatedNodes" -> svc.findRelatedNodes(argStr(args, 0), argInt(args, 1));

            case "findShortestPath" -> svc.findShortestPath(
                    argStr(args, 0), argStr(args, 1), argInt(args, 2));

            case "computeNodeRelevance" -> svc.computeNodeRelevance(
                    argStr(args, 0), argList(args, 1, String.class, mapper));

            // ── Entity mentions ───────────────────────────────────────────────

            case "getEntityMentionsForNode" -> {
                // Distinguish GraphNode arg (object) from String arg (primitive)
                JsonNode a0 = arg(args, 0);
                if (a0 != null && !a0.isNull() && a0.isObject()) {
                    yield svc.getEntityMentionsForNode(mapper.convertValue(a0, GraphNode.class));
                } else {
                    yield svc.getEntityMentionsForNode(argStr(args, 0));
                }
            }

            case "findEntityMention" -> svc.findEntityMention(
                    argObj(args, 0, GraphNode.class, mapper), argStr(args, 1));

            case "findEntityMentionInFactSheet" -> svc.findEntityMentionInFactSheet(
                    argObj(args, 0, GraphNode.class, mapper), argStr(args, 1), argLong(args, 2));

            case "saveEntityMention" -> svc.saveEntityMention(
                    argObj(args, 0, EntityMention.class, mapper));

            case "findNodePairsWithSharedEntities" -> svc.findNodePairsWithSharedEntities(argInt(args, 0));

            case "findNodePairsWithSharedEntitiesInFactSheet" -> svc.findNodePairsWithSharedEntitiesInFactSheet(
                    argLong(args, 0), argInt(args, 1));

            case "getEntityNamesForNode" -> svc.getEntityNamesForNode(argStr(args, 0));

            case "getNodesWithEntity" -> svc.getNodesWithEntity(argStr(args, 0));

            // ── Node embeddings ───────────────────────────────────────────────

            case "exportNodeEmbeddings" -> svc.exportNodeEmbeddings(argLong(args, 0));

            case "applyNodeEmbeddings" -> svc.applyNodeEmbeddings(argINDArrayMap(args, 0));

            // ── KGE embedding storage ─────────────────────────────────────────

            case "storeNodeKgEmbedding" -> {
                Instant updatedAt = isNullArg(args, 4) ? null
                        : mapper.convertValue(arg(args, 4), Instant.class);
                svc.storeNodeKgEmbedding(argStr(args, 0), argINDArray(args, 1),
                        argObj(args, 2, KGEmbeddingAlgorithm.class, mapper),
                        argLong(args, 3), updatedAt);
                yield null;
            }

            case "getNodeKgEmbedding" -> svc.getNodeKgEmbedding(argStr(args, 0));

            case "findNodesWithKgEmbedding" -> svc.findNodesWithKgEmbedding(argLong(args, 0));

            case "storeEdgeTypeKgEmbedding" -> {
                svc.storeEdgeTypeKgEmbedding(argStr(args, 0), argINDArray(args, 1),
                        argObj(args, 2, KGEmbeddingAlgorithm.class, mapper),
                        argLong(args, 3), argLong(args, 4));
                yield null;
            }

            case "getEdgeTypeKgEmbeddings" -> svc.getEdgeTypeKgEmbeddings(argLong(args, 0));

            case "getStoredKgAlgorithm" -> svc.getStoredKgAlgorithm(argLong(args, 0));

            case "clearKgEmbeddings" -> {
                svc.clearKgEmbeddings(argLong(args, 0));
                yield null;
            }

            // ── Count / statistics ────────────────────────────────────────────

            case "countNodesByType" -> svc.countNodesByType(argObj(args, 0, NodeLevel.class, mapper));

            case "countNodesByTypeInFactSheet" -> svc.countNodesByTypeInFactSheet(
                    argLong(args, 0), argObj(args, 1, NodeLevel.class, mapper));

            case "countActiveNodes" -> svc.countActiveNodes(argLong(args, 0));

            case "getGraphStatistics" -> svc.getGraphStatistics();

            case "getVisualizationData" -> svc.getVisualizationData(
                    argStr(args, 0), argInt(args, 1), argInt(args, 2));

            case "getVisualizationDataInTimeRange" -> svc.getVisualizationDataInTimeRange(
                    argObj(args, 0, LocalDateTime.class, mapper),
                    argObj(args, 1, LocalDateTime.class, mapper),
                    argInt(args, 2));

            case "searchEdgesByTimeRange" -> svc.searchEdgesByTimeRange(
                    argObj(args, 0, LocalDateTime.class, mapper),
                    argObj(args, 1, LocalDateTime.class, mapper),
                    argInt(args, 2));

            case "getTemporalBounds" -> svc.getTemporalBounds();

            // ── Maintenance ───────────────────────────────────────────────────

            case "flushPendingNodes" -> {
                svc.flushPendingNodes();
                yield null;
            }

            case "awaitPendingEmbeddings" -> {
                svc.awaitPendingEmbeddings();
                yield null;
            }

            case "deleteByFactSheetId" -> {
                svc.deleteByFactSheetId(argLong(args, 0));
                yield null;
            }

            // ── Pruning ───────────────────────────────────────────────────────

            case "findOrphanNodeIds" -> {
                if (args.size() <= 1) {
                    yield svc.findOrphanNodeIds(argLong(args, 0));
                } else {
                    Set<NodeLevel> levels = argSet(args, 1, NodeLevel.class, mapper);
                    yield svc.findOrphanNodeIds(argLong(args, 0), levels);
                }
            }

            case "findLowConfidenceNodeIds" -> svc.findLowConfidenceNodeIds(
                    argLong(args, 0), argDouble(args, 1));

            case "findLowConfidenceEdgeIds" -> svc.findLowConfidenceEdgeIds(
                    argLong(args, 0), argDouble(args, 1));

            case "findActiveEdgeIds" -> svc.findActiveEdgeIds(argLong(args, 0));

            case "pruneNodes" -> {
                List<String> ids = argList(args, 0, String.class, mapper);
                boolean soft  = argBool(args, 1);
                Duration grace = isNullArg(args, 2) ? null
                        : mapper.convertValue(arg(args, 2), Duration.class);
                boolean dry   = argBool(args, 3);
                if (args.size() <= 4 || isNullArg(args, 4)) {
                    yield svc.pruneNodes(ids, soft, grace, dry);
                }
                yield svc.pruneNodes(ids, soft, grace, dry, argLong(args, 4));
            }

            case "pruneNodesScoped" -> {
                List<String> ids = argList(args, 0, String.class, mapper);
                boolean soft = argBool(args, 1);
                Duration grace = isNullArg(args, 2) ? null
                        : mapper.convertValue(arg(args, 2), Duration.class);
                boolean dry = argBool(args, 3);
                yield svc.pruneNodes(ids, soft, grace, dry, argLong(args, 4));
            }

            case "pruneEdges" -> {
                List<String> ids = argList(args, 0, String.class, mapper);
                boolean soft = argBool(args, 1);
                boolean dry  = argBool(args, 2);
                yield svc.pruneEdges(ids, soft, dry);
            }

            case "hardDeleteStaleNodes" -> {
                Duration grace = isNullArg(args, 1) ? null
                        : mapper.convertValue(arg(args, 1), Duration.class);
                yield svc.hardDeleteStaleNodes(argLong(args, 0), grace);
            }

            default -> throw new IllegalArgumentException(
                    "[graph-matrix] KnowledgeGraphService: unknown method: " + method);
        };
        return serializeResult(method, rawResult, mapper);
    }

    /**
     * Special-case saveGraph: look up the real in-memory graph by id and persist it.
     * The real graph is in the VectorStoreMatrixGraphStore cache; the client only has a shell.
     */
    private static void handleSaveGraph(String graphId,
                                        VectorStoreMatrixGraphStore store,
                                        ObjectMapper mapper) throws IOException {
        Optional<AdjacencyMatrixGraph> opt = store.loadGraph(graphId);
        if (opt.isPresent()) {
            store.saveGraph(opt.get());
            logger.debug("[graph-matrix] saved graph {}", graphId);
        } else {
            logger.warn("[graph-matrix] saveGraph: graph '{}' not found in subprocess cache", graphId);
        }
    }

    /** Resolve method by name and exact parameter types, searching the concrete class and interfaces. */
    private static Method findMethod(Class<?> clazz, String name, Class<?>[] paramTypes) throws NoSuchMethodException {
        // Try the concrete class first (handles overrides).
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && paramTypesMatch(m.getParameterTypes(), paramTypes)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        // Fall back to interface default methods (e.g. addEdge 7-arg, addEdge 9-arg, updateNodeMetadata).
        for (Class<?> iface : clazz.getInterfaces()) {
            for (Method m : iface.getMethods()) {
                if (m.getName().equals(name) && paramTypesMatch(m.getParameterTypes(), paramTypes)) {
                    return m;
                }
            }
        }
        throw new NoSuchMethodException(
                clazz.getSimpleName() + "." + name + "(" + String.join(", ", paramTypes == null ? new String[0]
                        : java.util.Arrays.stream(paramTypes).map(Class::getSimpleName).toArray(String[]::new)) + ")");
    }

    private static boolean paramTypesMatch(Class<?>[] declared, Class<?>[] requested) {
        if (declared.length != requested.length) return false;
        for (int i = 0; i < declared.length; i++) {
            // Normalize primitive↔wrapper before comparing. The client serializes a declared
            // param type by FQCN (e.g. "java.lang.Long"), and resolveClass() maps long-ish names to the
            // PRIMITIVE long.class — so an interface method declaring a boxed Long (getNodesInFactSheet(Long),
            // getSourcesInFactSheet(Long), countNodesByTypeInFactSheet(Long,NodeLevel), …) would never match
            // (Long.isAssignableFrom(long)==false) and every such call failed with NoSuchMethodException.
            // Reflection auto(un)boxes on Method.invoke regardless, so treating long≡Long is correct.
            Class<?> d = wrapPrimitive(declared[i]);
            Class<?> r = wrapPrimitive(requested[i]);
            if (!d.isAssignableFrom(r) && !d.equals(r)) return false;
        }
        return true;
    }

    /** Map a primitive class to its wrapper (long→Long, int→Integer, …); pass non-primitives through. */
    private static Class<?> wrapPrimitive(Class<?> c) {
        if (c == null || !c.isPrimitive()) return c;
        if (c == long.class) return Long.class;
        if (c == int.class) return Integer.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == boolean.class) return Boolean.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        return c;
    }

    /** Convert list of FQCN strings to Class objects; handles primitives and well-known types. */
    private static Class<?>[] resolveParamTypes(List<String> names) throws ClassNotFoundException {
        Class<?>[] types = new Class<?>[names.size()];
        for (int i = 0; i < names.size(); i++) {
            types[i] = resolveClass(names.get(i));
        }
        return types;
    }

    private static Class<?> resolveClass(String name) throws ClassNotFoundException {
        return switch (name) {
            case "int", "java.lang.Integer" -> int.class;
            case "long", "java.lang.Long" -> long.class;
            case "double", "java.lang.Double" -> double.class;
            case "float", "java.lang.Float" -> float.class;
            case "boolean", "java.lang.Boolean" -> boolean.class;
            case "void" -> void.class;
            default -> Class.forName(name);
        };
    }

    /**
     * Deserialize JSON args to Java objects, applying INDArray decoding where the declared
     * parameter type is {@link INDArray}, and INDArray-map decoding where the declared type
     * is {@link Map} and the JSON carries a {@code __indarray_map} marker.
     */
    private static Object[] deserializeArgs(Method method, List<JsonNode> argJsons,
                                            ObjectMapper mapper) throws Exception {
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] result = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            JsonNode json = i < argJsons.size() ? argJsons.get(i) : mapper.nullNode();
            if (json == null || json.isNull()) {
                result[i] = null;
            } else if (INDArray.class.isAssignableFrom(paramTypes[i])) {
                result[i] = decodeINDArray(json);
            } else if (Map.class.isAssignableFrom(paramTypes[i])
                    && json.path("__indarray_map").asBoolean(false)) {
                // Map<String, INDArray> encoded as {"__indarray_map":true, "entries":{...}}
                result[i] = decodeINDArrayMap(json);
            } else {
                // Deserialize to the generic parameter type where possible.
                Type genericType = method.getGenericParameterTypes()[i];
                result[i] = mapper.readValue(json.toString(),
                        mapper.constructType(genericType));
            }
        }
        return result;
    }

    /**
     * Serialise the method's return value, applying:
     * <ul>
     *   <li>{@code createGraph}/{@code loadGraph} → graph ack (no full matrix)</li>
     *   <li>{@code INDArray} return → {@code {"__indarray":true,...}}</li>
     *   <li>{@code Optional<T>} → null or inner value</li>
     *   <li>{@code Map.Entry<K,V>} → {@code {"key":...,"value":...}}</li>
     *   <li>Everything else → standard Jackson</li>
     * </ul>
     */
    private static String serializeResult(String methodName, Object rawResult,
                                          ObjectMapper mapper) throws JsonProcessingException {
        ObjectNode response = mapper.createObjectNode();
        response.put("ok", true);
        response.put("protocolVersion", 2);

        // AdjacencyMatrixGraph return methods — return ack, never the graph.
        if ("createGraph".equals(methodName) || "loadGraph".equals(methodName)) {
            if (rawResult == null) {
                response.putNull("result");
            } else if (rawResult instanceof Optional<?> opt) {
                if (opt.isEmpty()) {
                    response.putNull("result");
                } else {
                    response.set("result", buildGraphAck((AdjacencyMatrixGraph) opt.get(), mapper));
                }
            } else if (rawResult instanceof AdjacencyMatrixGraph graph) {
                response.set("result", buildGraphAck(graph, mapper));
            } else {
                response.putNull("result");
            }
            return writeCapped(mapper, response);
        }

        // INDArray return methods.
        if (rawResult instanceof INDArray arr) {
            response.set("result", encodeINDArray(arr, mapper));
            return writeCapped(mapper, response);
        }

        // Map<String, INDArray> returns (e.g. exportNodeEmbeddings, getEdgeTypeKgEmbeddings).
        if (rawResult instanceof Map<?, ?> m && !m.isEmpty()
                && m.values().iterator().next() instanceof INDArray) {
            @SuppressWarnings("unchecked")
            Map<String, INDArray> indArrayMap = (Map<String, INDArray>) m;
            response.set("result", encodeINDArrayMap(indArrayMap, mapper));
            return writeCapped(mapper, response);
        }

        // Optional<T> — unwrap; inner value may itself need special handling.
        if (rawResult instanceof Optional<?> opt) {
            if (opt.isEmpty()) {
                response.putNull("result");
            } else {
                response.set("result", mapper.valueToTree(opt.get()));
            }
            return writeCapped(mapper, response);
        }

        // AdjacencyMatrixGraph must never be serialized across the wire — return an ack shell.
        if (rawResult instanceof AdjacencyMatrixGraph graph) {
            response.set("result", buildGraphAck(graph, mapper));
            return writeCapped(mapper, response);
        }

        // void/null
        if (rawResult == null) {
            response.putNull("result");
            return writeCapped(mapper, response);
        }

        // List<Map.Entry<String,Double>> (getEdges, findSimilarNodes) — Jackson
        // serialises AbstractMap.SimpleImmutableEntry as {"key":...,"value":...} but
        // not all Entry impls; convert to explicit list-of-maps for safety.
        if (rawResult instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map.Entry) {
            ArrayNode arr = mapper.createArrayNode();
            for (Object item : list) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) item;
                ObjectNode en = mapper.createObjectNode();
                en.set("key", mapper.valueToTree(e.getKey()));
                en.set("value", mapper.valueToTree(e.getValue()));
                arr.add(en);
            }
            response.set("result", arr);
            return writeCapped(mapper, response);
        }

        // Default: standard Jackson serialisation.
        response.set("result", mapper.valueToTree(rawResult));
        return writeCapped(mapper, response);
    }

    // ── INDArray wire codec ───────────────────────────────────────────────────

    public static ObjectNode encodeINDArray(INDArray arr, ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("__indarray", true);
        if (arr == null) {
            node.put("__null", true);
            return node;
        }
        // Dtype-preserving ND4J FlatBuffers serialization (fp16/bf16/fp8/int8/uint8 all round-trip
        // exactly — the SameDiff-tested path), carried as a JSON binary node (jackson base64). Replaces
        // the old toFloatVector() JSON float array, which flattened every array to float32.
        node.put("dtype", arr.dataType().name());
        node.put("flat", FlatArrayCodec.toFlatBytes(arr));
        return node;
    }

    public static INDArray decodeINDArray(JsonNode node) {
        if (node == null || node.isNull() || node.path("__null").asBoolean(false)) return null;
        JsonNode flat = node.get("flat");
        if (flat != null && !flat.isNull()) {
            try {
                return FlatArrayCodec.fromFlatBytes(flat.binaryValue());
            } catch (java.io.IOException e) {
                throw new RuntimeException("[graph-matrix] FlatArray decode failed: " + e, e);
            }
        }
        // Backward-compat: legacy float32 JSON array {"data":[...],"shape":[...]}.
        JsonNode dataNode = node.path("data");
        JsonNode shapeNode = node.path("shape");
        if (!dataNode.isArray()) return null;
        int dataLen = dataNode.size();
        float[] data = new float[dataLen];
        for (int i = 0; i < dataLen; i++) data[i] = dataNode.get(i).floatValue();
        int shapeLen = shapeNode.size();
        long[] shape = new long[shapeLen];
        for (int i = 0; i < shapeLen; i++) shape[i] = shapeNode.get(i).longValue();
        return Nd4j.create(data, shape);
    }

    /**
     * Encode a {@code Map<String, INDArray>} for cross-process transport.
     * Wire format: {@code {"__indarray_map":true,"entries":{"id":{"__indarray":true,...},...}}}
     */
    public static ObjectNode encodeINDArrayMap(Map<String, INDArray> map, ObjectMapper mapper) {
        ObjectNode wrapper = mapper.createObjectNode();
        wrapper.put("__indarray_map", true);
        ObjectNode entries = mapper.createObjectNode();
        for (Map.Entry<String, INDArray> e : map.entrySet()) {
            entries.set(e.getKey(), encodeINDArray(e.getValue(), mapper));
        }
        wrapper.set("entries", entries);
        return wrapper;
    }

    /**
     * Decode a {@code {"__indarray_map":true,"entries":{...}}} node back to a
     * {@code Map<String, INDArray>}.
     */
    public static Map<String, INDArray> decodeINDArrayMap(JsonNode node) {
        if (node == null || node.isNull()) return Map.of();
        Map<String, INDArray> result = new LinkedHashMap<>();
        JsonNode entries = node.path("entries");
        entries.fields().forEachRemaining(e -> result.put(e.getKey(), decodeINDArray(e.getValue())));
        return result;
    }

    /** Build a lightweight graph ack — graphId + node/edge counts. No adjacency data. */
    private static ObjectNode buildGraphAck(AdjacencyMatrixGraph graph, ObjectMapper mapper) {
        ObjectNode ack = mapper.createObjectNode();
        ack.put("__graphack", true);
        ack.put("graphId", graph.getGraphId());
        ack.put("nodeCount", graph.getNodeById() != null ? graph.getNodeById().size() : 0);
        int edgeCount = 0;
        if (graph.getEdgeCountByType() != null) {
            for (AtomicInteger v : graph.getEdgeCountByType().values()) edgeCount += v.get();
        }
        ack.put("edgeCount", edgeCount);
        return ack;
    }

    // ── Spring context ────────────────────────────────────────────────────────

    /**
     * Boot the matrix Spring context.  Mirrors {@code IngestSubprocessMain#createContext} for the
     * vector-store property setup.
     */
    private static AnnotationConfigApplicationContext createContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

        // Activate SubprocessGraphConfiguration (conditional on this property).
        context.getEnvironment().getSystemProperties().put("kompile.subprocess.mode", "true");

        // Enable the Anserini vector store so VectorStoreMatrixGraphStore can read/write graphs.
        context.getEnvironment().getSystemProperties().put("kompile.vectorstore.anserini.enabled", "true");
        context.getEnvironment().getSystemProperties().put("kompile.vectorstore.anserini.persistence-enabled", "true");
        boolean generationAuthority = Boolean.parseBoolean(
                System.getProperty("kompile.graph.generations.subprocess-authority", "false"));
        context.getEnvironment().getSystemProperties().put(
                "kompile.graph.generations.subprocess-authority", Boolean.toString(generationAuthority));
        context.getEnvironment().getSystemProperties().put(
                "kompile.graph.generations.enabled", Boolean.toString(generationAuthority));

        // Propagate the main app's data-dir / index paths if they were set as system properties
        // by the launcher (e.g. via -Dkompile.data.dir=...).  This ensures the subprocess reads
        // from the same vector-store as the main app.  Falls back to the application.properties
        // default (${user.home}/.kompile) when not set — which is correct for the default setup.
        String dataDir = System.getProperty("kompile.data.dir");
        if (dataDir != null && !dataDir.isBlank()) {
            context.getEnvironment().getSystemProperties().put("kompile.data.dir", dataDir);
        }
        String vsPath = System.getProperty("kompile.vectorstore.anserini.index-path");
        if (vsPath != null && !vsPath.isBlank()) {
            context.getEnvironment().getSystemProperties().put("kompile.vectorstore.anserini.index-path", vsPath);
        }
        String kwPath = System.getProperty("anserini.indexPath");
        if (kwPath != null && !kwPath.isBlank()) {
            context.getEnvironment().getSystemProperties().put("anserini.indexPath", kwPath);
        }

        // Do not force all project graphs into one subprocess heap. Individual RPCs lazy-load
        // their addressed graph from the project-scoped Lucene index.
        context.getEnvironment().getSystemProperties().putIfAbsent(
                "kompile.graph.eager-rehydration-enabled", "false");

        // This process IS the graph subprocess: the main-app @Primary HTTP delegate
        // (SubprocessMatrixGraphStore, in ai.kompile.app.*) is NOT on this context's component-scan
        // (base = ai.kompile.knowledgegraph), so it can never be created here to recurse onto itself.
        // The real VectorStoreMatrixGraphStore serves locally.

        context.register(SubprocessGraphConfiguration.class);
        context.refresh();
        return context;
    }

    // ── ND4J lifecycle (mirrored from GraphSubprocessMain) ───────────────────

    private static void initializeNd4j() {
        try {
            DifferentialFunctionClassHolder.initInstance();
            Nd4jBackend backend = Nd4jBackend.load();
            Nd4j.backend = backend;
            logger.info("[graph-matrix] loaded ND4J backend: {}", backend.getClass().getSimpleName());

            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
            nativeOps.initializeDevicesAndFunctions();

            applyNd4jEnvironmentConfig(Nd4jEnvironmentConfig.defaults());

            logger.info("[graph-matrix] ND4J initialised: maxThreads={}, backend={}",
                    Nd4j.getEnvironment().maxThreads(),
                    Nd4j.getBackend().getClass().getSimpleName());
        } catch (Throwable e) {
            logger.warn("[graph-matrix] ND4J initialisation failed: {}. Embedding operations may be unavailable.",
                    e.getMessage());
        }
    }

    /** Mirrors GraphSubprocessMain.applyNd4jEnvironmentConfig exactly. */
    private static void applyNd4jEnvironmentConfig(Nd4jEnvironmentConfig config) {
        if (config == null) config = Nd4jEnvironmentConfig.defaults();
        try {
            if (config.enableBlas() != null)         Nd4j.getEnvironment().setEnableBlas(config.enableBlas());
            if (config.helpersAllowed() != null)      Nd4j.getEnvironment().allowHelpers(config.helpersAllowed());
            if (config.maxThreads() != null)          Nd4j.getEnvironment().setMaxThreads(config.maxThreads());
            if (config.maxMasterThreads() != null)    Nd4j.getEnvironment().setMaxMasterThreads(config.maxMasterThreads());
            if (config.debug() != null)               Nd4j.getEnvironment().setDebug(config.debug());
            if (config.verbose() != null)             Nd4j.getEnvironment().setVerbose(config.verbose());
            if (config.profiling() != null)           Nd4j.getEnvironment().setProfiling(config.profiling());
            if (config.leaksDetector() != null)       Nd4j.getEnvironment().setLeaksDetector(config.leaksDetector());
            if (config.tadThreshold() != null)        Nd4j.getEnvironment().setTadThreshold(config.tadThreshold());
            if (config.elementwiseThreshold() != null) Nd4j.getEnvironment().setElementwiseThreshold(config.elementwiseThreshold());
            if (config.maxPrimaryMemory() != null && config.maxPrimaryMemory() > 0)
                Nd4j.getEnvironment().setMaxPrimaryMemory(config.maxPrimaryMemory());
            if (config.maxSpecialMemory() != null && config.maxSpecialMemory() > 0)
                Nd4j.getEnvironment().setMaxSpecialMemory(config.maxSpecialMemory());
            if (config.maxDeviceMemory() != null && config.maxDeviceMemory() > 0)
                Nd4j.getEnvironment().setMaxDeviceMemory(config.maxDeviceMemory());
        } catch (Exception e) {
            logger.warn("[graph-matrix] error applying ND4J environment config: {}", e.getMessage());
        }
    }
}
