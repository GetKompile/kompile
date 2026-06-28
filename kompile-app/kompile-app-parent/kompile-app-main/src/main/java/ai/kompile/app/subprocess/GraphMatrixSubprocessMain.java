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

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.core.graphrag.GraphRagService;
import ai.kompile.core.graphrag.maintenance.model.GraphPruneResult;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.EntityMention;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
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
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.factory.Nd4jBackend;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
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

    /** Signals that rehydrateGraphsOnStartup() has returned successfully. */
    private static volatile boolean rehydrationDone = false;

    /** Static reference to the Spring context so HTTP handlers can look up service beans. */
    private static volatile AnnotationConfigApplicationContext springContext;

    /** Cache of already-resolved service beans, keyed by interface FQCN. */
    private static final ConcurrentHashMap<String, Object> serviceCache = new ConcurrentHashMap<>();

    public static void main(String[] args) throws InterruptedException {
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

        // 3. Trigger rehydration HERE — the matrix lives ONLY in this process.
        VectorStoreMatrixGraphStore store = context.getBean(VectorStoreMatrixGraphStore.class);
        MatrixGraphStore matrixStore = store; // VectorStoreMatrixGraphStore implements MatrixGraphStore
        logger.info("[graph-matrix] starting graph rehydration...");
        try {
            store.rehydrateGraphsOnStartup();
            rehydrationDone = true;
            logger.info("[graph-matrix] rehydration complete");
        } catch (Exception e) {
            logger.error("[graph-matrix] rehydration failed — serving anyway (graphs may be empty)", e);
            rehydrationDone = true; // still serve; individual calls will handle missing graphs
        }

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

        httpServer.createContext("/health", exchange -> handleHealth(exchange));
        httpServer.createContext("/invoke", exchange -> handleInvoke(exchange, matrixStore, store, objectMapper));
        httpServer.setExecutor(Executors.newCachedThreadPool());
        httpServer.start();
        logger.info("[graph-matrix] HTTP server listening on 127.0.0.1:{}", finalPort);

        // 5. Shutdown hook.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("[graph-matrix] shutting down...");
            httpServer.stop(2);
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

    private static void handleInvoke(HttpExchange exchange,
                                     MatrixGraphStore matrixStore,
                                     VectorStoreMatrixGraphStore store,
                                     ObjectMapper mapper) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"ok\":false,\"error\":\"only POST /invoke is supported\"}");
            return;
        }
        String requestBody;
        try (InputStream is = exchange.getRequestBody()) {
            requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        String responseJson;
        try {
            responseJson = dispatch(requestBody, matrixStore, store, mapper);
        } catch (Exception e) {
            logger.error("[graph-matrix] invoke error: {}", e.getMessage(), e);
            ObjectNode err = mapper.createObjectNode();
            err.put("ok", false);
            err.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            responseJson = mapper.writeValueAsString(err);
        }
        sendJson(exchange, 200, responseJson);
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
    private static String dispatch(String requestBody,
                                   MatrixGraphStore matrixStore,
                                   VectorStoreMatrixGraphStore store,
                                   ObjectMapper mapper) throws Exception {
        JsonNode root = mapper.readTree(requestBody);
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

        // --- Service dispatch (GraphConstructor / GraphRagService / KnowledgeGraphService) ---
        if (root.has("service")) {
            String serviceFqcn = root.path("service").asText();
            return dispatchToService(serviceFqcn, methodName, argJsons, mapper);
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
                yield dispatchKnowledgeGraphService(svc, methodName, argJsons, mapper);
            }
            case "ai.kompile.core.graphrag.GraphConstructor" -> {
                GraphConstructor svc = (GraphConstructor) serviceCache.computeIfAbsent(
                        serviceFqcn, k -> springContext.getBean(GraphConstructor.class));
                yield dispatchGraphConstructor(svc, methodName, argJsons, mapper);
            }
            case "ai.kompile.core.graphrag.GraphRagService" -> {
                GraphRagService svc = (GraphRagService) serviceCache.computeIfAbsent(
                        serviceFqcn, k -> springContext.getBean(GraphRagService.class));
                yield dispatchGraphRagService(svc, methodName, argJsons, mapper);
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

    // ── GraphConstructor dispatcher ───────────────────────────────────────────

    private static String dispatchGraphConstructor(GraphConstructor svc, String method,
                                                    List<JsonNode> args,
                                                    ObjectMapper mapper) throws Exception {
        JavaType listRetrievedDoc = mapper.getTypeFactory().constructCollectionType(List.class, RetrievedDoc.class);
        Object rawResult = switch (method) {
            case "configure" -> {
                svc.configure(argObj(args, 0, GraphConstructor.ExtractionModelConfig.class, mapper));
                yield null;
            }
            case "constructGraph" -> svc.constructGraph(argStr(args, 0));
            case "constructGraphFromDocs" -> {
                List<RetrievedDoc> docs = mapper.convertValue(arg(args, 0), listRetrievedDoc);
                GraphSchema schema = argObj(args, 1, GraphSchema.class, mapper);
                SchemaEnforcementMode mode = argObj(args, 2, SchemaEnforcementMode.class, mapper);
                if (args.size() <= 3) {
                    yield svc.constructGraphFromDocs(docs, schema, mode);
                } else {
                    boolean skipEmbedding   = argBool(args, 3);
                    boolean skipMatrixGraph = argBool(args, 4);
                    // args[5] is null (ProgressListener cannot cross the wire)
                    GraphConstructor.ProgressListener noOp = p -> {};
                    yield svc.constructGraphFromDocs(docs, schema, mode, skipEmbedding, skipMatrixGraph, noOp);
                }
            }
            default -> throw new IllegalArgumentException(
                    "[graph-matrix] GraphConstructor: unknown method: " + method);
        };
        return serializeResult(method, rawResult, mapper);
    }

    // ── KnowledgeGraphService dispatcher ─────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String dispatchKnowledgeGraphService(KnowledgeGraphService svc, String method,
                                                        List<JsonNode> args,
                                                        ObjectMapper mapper) throws Exception {
        Object rawResult = switch (method) {

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

            case "createNodesBatch" -> {
                JavaType specList = mapper.getTypeFactory()
                        .constructCollectionType(List.class, KnowledgeGraphService.NodeSpec.class);
                List<KnowledgeGraphService.NodeSpec> specs = mapper.convertValue(arg(args, 0), specList);
                yield svc.createNodesBatch(specs, argLong(args, 1));
            }

            case "updateNodeKgeMetadataBatch" -> {
                JavaType updateList = mapper.getTypeFactory()
                        .constructCollectionType(List.class, KnowledgeGraphService.NodeMetadataUpdate.class);
                List<KnowledgeGraphService.NodeMetadataUpdate> updates =
                        mapper.convertValue(arg(args, 0), updateList);
                yield svc.updateNodeKgeMetadataBatch(updates);
            }

            case "createSnippetNodesBatch" -> {
                JavaType snippetSpecList = mapper.getTypeFactory()
                        .constructCollectionType(List.class, KnowledgeGraphService.SnippetSpec.class);
                List<KnowledgeGraphService.SnippetSpec> snippetSpecs =
                        mapper.convertValue(arg(args, 0), snippetSpecList);
                yield svc.createSnippetNodesBatch(snippetSpecs);
            }

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

            case "createEdgesBatch" -> {
                JavaType edgeSpecList = mapper.getTypeFactory()
                        .constructCollectionType(List.class, KnowledgeGraphService.EdgeSpec.class);
                List<KnowledgeGraphService.EdgeSpec> edgeSpecs =
                        mapper.convertValue(arg(args, 0), edgeSpecList);
                yield svc.createEdgesBatch(edgeSpecs);
            }

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
                yield svc.pruneNodes(ids, soft, grace, dry);
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
            return mapper.writeValueAsString(response);
        }

        // INDArray return methods.
        if (rawResult instanceof INDArray arr) {
            response.set("result", encodeINDArray(arr, mapper));
            return mapper.writeValueAsString(response);
        }

        // Map<String, INDArray> returns (e.g. exportNodeEmbeddings, getEdgeTypeKgEmbeddings).
        if (rawResult instanceof Map<?, ?> m && !m.isEmpty()
                && m.values().iterator().next() instanceof INDArray) {
            @SuppressWarnings("unchecked")
            Map<String, INDArray> indArrayMap = (Map<String, INDArray>) m;
            response.set("result", encodeINDArrayMap(indArrayMap, mapper));
            return mapper.writeValueAsString(response);
        }

        // Optional<T> — unwrap; inner value may itself need special handling.
        if (rawResult instanceof Optional<?> opt) {
            if (opt.isEmpty()) {
                response.putNull("result");
            } else {
                response.set("result", mapper.valueToTree(opt.get()));
            }
            return mapper.writeValueAsString(response);
        }

        // AdjacencyMatrixGraph must never be serialized across the wire — return an ack shell.
        if (rawResult instanceof AdjacencyMatrixGraph graph) {
            response.set("result", buildGraphAck(graph, mapper));
            return mapper.writeValueAsString(response);
        }

        // void/null
        if (rawResult == null) {
            response.putNull("result");
            return mapper.writeValueAsString(response);
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
            return mapper.writeValueAsString(response);
        }

        // Default: standard Jackson serialisation.
        response.set("result", mapper.valueToTree(rawResult));
        return mapper.writeValueAsString(response);
    }

    // ── INDArray wire codec ───────────────────────────────────────────────────

    public static ObjectNode encodeINDArray(INDArray arr, ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("__indarray", true);
        long[] shape = arr.shape();
        ArrayNode shapeNode = mapper.createArrayNode();
        for (long s : shape) shapeNode.add(s);
        node.set("shape", shapeNode);
        float[] data = arr.toFloatVector();
        ArrayNode dataNode = mapper.createArrayNode();
        for (float f : data) dataNode.add(f);
        node.set("data", dataNode);
        return node;
    }

    public static INDArray decodeINDArray(JsonNode node) {
        if (node == null || node.isNull()) return null;
        JsonNode dataNode = node.path("data");
        JsonNode shapeNode = node.path("shape");
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
     * Boot the matrix Spring context.  Mirrors {@link GraphSubprocessMain#createContext} and
     * {@code IngestSubprocessMain#createContext} for the vector-store property setup.
     */
    private static AnnotationConfigApplicationContext createContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

        // Activate SubprocessGraphConfiguration (conditional on this property).
        context.getEnvironment().getSystemProperties().put("kompile.subprocess.mode", "true");

        // Enable the Anserini vector store so VectorStoreMatrixGraphStore can read/write graphs.
        context.getEnvironment().getSystemProperties().put("kompile.vectorstore.anserini.enabled", "true");
        context.getEnvironment().getSystemProperties().put("kompile.vectorstore.anserini.persistence-enabled", "true");

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

        // Keep eager rehydration ENABLED: we invoke store.rehydrateGraphsOnStartup() explicitly after
        // refresh, and THAT method early-returns when this flag is false (so disabling it here would
        // load an empty graph). A bare AnnotationConfigApplicationContext never publishes
        // ApplicationReadyEvent, so GraphRehydrationListener does not auto-fire here — there is no
        // double-rehydration to guard against.
        context.getEnvironment().getSystemProperties().put("kompile.graph.eager-rehydration-enabled", "true");

        // This process IS the graph subprocess: the @Primary HTTP delegate (SubprocessMatrixGraphStore)
        // must NOT be created in this context (it would recurse onto itself). The real
        // VectorStoreMatrixGraphStore serves locally here.
        context.getEnvironment().getSystemProperties().put("kompile.graph.subprocess.enabled", "false");

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
