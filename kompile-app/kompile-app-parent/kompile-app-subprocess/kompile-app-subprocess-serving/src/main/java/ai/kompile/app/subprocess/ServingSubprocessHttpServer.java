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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.subprocess;

import ai.kompile.app.llm.pipeline.LlmGenerateController;
import ai.kompile.app.llm.pipeline.LlmModelController;
import ai.kompile.app.llm.pipeline.LoadRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native-image-safe HTTP adapter for the four serving-subprocess endpoints.
 *
 * <p>The parent native image cannot start a second Spring Boot application because
 * Spring AOT generated artifacts belong to the primary application. This adapter
 * follows the graph and pipeline subprocess pattern: a narrow Spring bean context
 * supplies the existing controllers, while the JDK HTTP server owns transport.
 * Requests, responses, worker count, and queued work are all bounded.</p>
 */
final class ServingSubprocessHttpServer implements AutoCloseable {

    static final long DEFAULT_MAX_REQUEST_BYTES = 1024L * 1024L;
    static final long DEFAULT_MAX_RESPONSE_BYTES = 16L * 1024L * 1024L;
    static final int DEFAULT_HTTP_THREADS = 8;
    static final int DEFAULT_HTTP_QUEUE_CAPACITY = 32;

    private static final Logger logger = LoggerFactory.getLogger(ServingSubprocessHttpServer.class);
    private static final String LOAD_PATH = "/api/llm/load";
    private static final String STATUS_PATH = "/api/llm/status";
    private static final String GENERATE_PATH = "/api/llm/generate";
    private static final String UNLOAD_PATH = "/api/llm/unload";
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "content-length", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");

    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private ServingSubprocessHttpServer(HttpServer server, ThreadPoolExecutor executor) {
        this.server = server;
        this.executor = executor;
    }

    static ServingSubprocessHttpServer start(
            String host,
            int port,
            ObjectMapper objectMapper,
            LlmModelController modelController,
            LlmGenerateController generateController) throws IOException {
        Api api = new Api() {
            @Override
            public synchronized ResponseEntity<Map<String, Object>> load(LoadRequest request) {
                return modelController.load(request);
            }

            @Override
            public ResponseEntity<Map<String, Object>> status() {
                return modelController.status();
            }

            @Override
            public ResponseEntity<Map<String, Object>> generate(Map<String, Object> request) {
                return generateController.generate(request);
            }

            @Override
            public synchronized ResponseEntity<Map<String, Object>> unload() {
                return modelController.unload();
            }
        };

        return start(
                host,
                port,
                objectMapper,
                api,
                positiveLongProperty(
                        "kompile.serving.subprocess.max-request-bytes",
                        DEFAULT_MAX_REQUEST_BYTES),
                positiveLongProperty(
                        "kompile.serving.subprocess.max-response-bytes",
                        DEFAULT_MAX_RESPONSE_BYTES),
                positiveIntProperty(
                        "kompile.serving.subprocess.http-threads",
                        DEFAULT_HTTP_THREADS),
                positiveIntProperty(
                        "kompile.serving.subprocess.http-queue-capacity",
                        DEFAULT_HTTP_QUEUE_CAPACITY));
    }

    static ServingSubprocessHttpServer start(
            String host,
            int port,
            ObjectMapper objectMapper,
            Api api,
            long maxRequestBytes,
            long maxResponseBytes,
            int httpThreads,
            int queueCapacity) throws IOException {
        if (objectMapper == null || api == null) {
            throw new IllegalArgumentException("objectMapper and api are required");
        }
        if (maxRequestBytes <= 0 || maxResponseBytes <= 0 || httpThreads <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("serving HTTP limits must be positive");
        }

        HttpServer httpServer = HttpServer.create(new InetSocketAddress(host, port), 128);
        ThreadPoolExecutor httpExecutor = new ThreadPoolExecutor(
                httpThreads,
                httpThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new NamedThreadFactory(),
                // Never run expensive model work on the JDK HTTP dispatcher thread.
                new ThreadPoolExecutor.AbortPolicy());

        ServingSubprocessHttpServer adapter = new ServingSubprocessHttpServer(httpServer, httpExecutor);
        httpServer.createContext(LOAD_PATH, exchange -> adapter.handle(
                exchange, LOAD_PATH, "POST", Operation.LOAD, objectMapper, api,
                maxRequestBytes, maxResponseBytes));
        httpServer.createContext(STATUS_PATH, exchange -> adapter.handle(
                exchange, STATUS_PATH, "GET", Operation.STATUS, objectMapper, api,
                maxRequestBytes, maxResponseBytes));
        httpServer.createContext(GENERATE_PATH, exchange -> adapter.handle(
                exchange, GENERATE_PATH, "POST", Operation.GENERATE, objectMapper, api,
                maxRequestBytes, maxResponseBytes));
        httpServer.createContext(UNLOAD_PATH, exchange -> adapter.handle(
                exchange, UNLOAD_PATH, "POST", Operation.UNLOAD, objectMapper, api,
                maxRequestBytes, maxResponseBytes));
        httpServer.setExecutor(httpExecutor);
        httpServer.start();
        logger.info("LLM serving HTTP server listening on {}:{}", host, adapter.port());
        return adapter;
    }

    int port() {
        return server.getAddress().getPort();
    }

    private void handle(
            HttpExchange exchange,
            String exactPath,
            String requiredMethod,
            Operation operation,
            ObjectMapper objectMapper,
            Api api,
            long maxRequestBytes,
            long maxResponseBytes) {
        try {
            if (!exactPath.equals(exchange.getRequestURI().getPath())) {
                sendJson(exchange, 404, Map.of("error", "not found"), objectMapper, maxResponseBytes);
                return;
            }
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", requiredMethod + ", OPTIONS");
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!requiredMethod.equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", requiredMethod + ", OPTIONS");
                sendJson(exchange, 405, Map.of("error", "method not allowed"), objectMapper, maxResponseBytes);
                return;
            }
            if (operation.requiresJsonBody() && !hasJsonContentType(exchange)) {
                sendJson(exchange, 415, Map.of("error", "Content-Type must be application/json"),
                        objectMapper, maxResponseBytes);
                return;
            }

            ResponseEntity<Map<String, Object>> response = switch (operation) {
                case LOAD -> api.load(readLoadRequest(exchange, objectMapper, maxRequestBytes));
                case STATUS -> api.status();
                case GENERATE -> api.generate(readMapRequest(exchange, objectMapper, maxRequestBytes));
                case UNLOAD -> api.unload();
            };
            Map<String, Object> body = response.getBody() != null ? response.getBody() : Map.of();
            copyResponseHeaders(exchange, response);
            sendJson(exchange, response.getStatusCode().value(), body, objectMapper, maxResponseBytes);
        } catch (PayloadTooLargeException e) {
            sendJsonQuietly(exchange, 413, Map.of("error", "request exceeds byte limit"),
                    objectMapper, maxResponseBytes);
        } catch (BadRequestException e) {
            sendJsonQuietly(exchange, 400, Map.of("error", e.getMessage()),
                    objectMapper, maxResponseBytes);
        } catch (Exception e) {
            logger.error("Serving HTTP request failed: {} {}", exchange.getRequestMethod(),
                    exchange.getRequestURI(), e);
            sendJsonQuietly(exchange, 500, Map.of("error", "internal serving error"),
                    objectMapper, maxResponseBytes);
        } finally {
            exchange.close();
        }
    }

    private static LoadRequest readLoadRequest(
            HttpExchange exchange,
            ObjectMapper objectMapper,
            long maxRequestBytes) throws IOException {
        JsonNode requestNode = readObjectRequest(exchange, objectMapper, maxRequestBytes);
        LoadRequest request = new LoadRequest();
        JsonNode modelId = requestNode.get("modelId");
        if (modelId != null && !modelId.isNull()) {
            request.setModelId(modelId.asText());
        }
        JsonNode stagingUrl = requestNode.get("stagingUrl");
        if (stagingUrl != null && !stagingUrl.isNull()) {
            request.setStagingUrl(stagingUrl.asText());
        }
        JsonNode options = requestNode.get("options");
        if (options != null && !options.isNull()) {
            if (!options.isObject()) {
                throw new BadRequestException("options must be a JSON object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> optionMap = objectMapper.convertValue(options, Map.class);
            request.setOptions(optionMap);
        }
        return request;
    }

    private static Map<String, Object> readMapRequest(
            HttpExchange exchange,
            ObjectMapper objectMapper,
            long maxRequestBytes) throws IOException {
        JsonNode requestNode = readObjectRequest(exchange, objectMapper, maxRequestBytes);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.convertValue(requestNode, Map.class);
            return request;
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid JSON request", e);
        }
    }

    private static JsonNode readObjectRequest(
            HttpExchange exchange,
            ObjectMapper objectMapper,
            long maxRequestBytes) throws IOException {
        long contentLength = parseContentLength(exchange);
        if (contentLength > maxRequestBytes) {
            throw new PayloadTooLargeException();
        }
        byte[] requestBytes = readBounded(exchange.getRequestBody(), maxRequestBytes);
        if (requestBytes.length == 0) {
            throw new BadRequestException("JSON request body is required");
        }
        try {
            JsonNode request = objectMapper.readTree(requestBytes);
            if (request == null || !request.isObject()) {
                throw new BadRequestException("JSON request body must be an object");
            }
            return request;
        } catch (JsonProcessingException e) {
            throw new BadRequestException("invalid JSON request", e);
        }
    }

    private static byte[] readBounded(InputStream input, long maxBytes) throws IOException {
        int initialCapacity = (int) Math.min(maxBytes, 8192L);
        ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity);
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            total += read;
            if (total > maxBytes || total > Integer.MAX_VALUE - 8L) {
                throw new PayloadTooLargeException();
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static long parseContentLength(HttpExchange exchange) {
        String raw = exchange.getRequestHeaders().getFirst("Content-Length");
        if (raw == null || raw.isBlank()) {
            return -1L;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed >= 0 ? parsed : -1L;
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static boolean hasJsonContentType(HttpExchange exchange) {
        String raw = exchange.getRequestHeaders().getFirst("Content-Type");
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String mediaType = raw.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return "application/json".equals(mediaType) || mediaType.endsWith("+json");
    }

    private static void copyResponseHeaders(
            HttpExchange exchange,
            ResponseEntity<Map<String, Object>> response) {
        response.getHeaders().forEach((name, values) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                exchange.getResponseHeaders().put(name, new ArrayList<>(values));
            }
        });
    }

    private static void sendJson(
            HttpExchange exchange,
            int status,
            Map<String, Object> response,
            ObjectMapper objectMapper,
            long maxResponseBytes) throws IOException {
        byte[] body;
        try {
            body = serializeCapped(objectMapper, response, maxResponseBytes);
        } catch (ResponseTooLargeException e) {
            sendResponseTooLarge(exchange, maxResponseBytes);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static byte[] serializeCapped(
            ObjectMapper objectMapper,
            Map<String, Object> response,
            long maxResponseBytes) throws IOException {
        CappedByteArrayOutputStream output = new CappedByteArrayOutputStream(maxResponseBytes);
        try {
            objectMapper.writeValue(output, response);
        } catch (ResponseTooLargeException e) {
            throw e;
        } catch (IOException e) {
            if (output.exceeded()) {
                throw new ResponseTooLargeException(maxResponseBytes);
            }
            throw e;
        }
        if (output.exceeded()) {
            throw new ResponseTooLargeException(maxResponseBytes);
        }
        return output.toByteArray();
    }

    private static void sendResponseTooLarge(HttpExchange exchange, long maxResponseBytes) throws IOException {
        byte[] error = "{\"error\":\"response exceeds byte limit\"}".getBytes(StandardCharsets.UTF_8);
        if (error.length <= maxResponseBytes) {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(507, error.length);
            try (var output = exchange.getResponseBody()) {
                output.write(error);
            }
        } else {
            exchange.getResponseHeaders().remove("Content-Type");
            exchange.sendResponseHeaders(507, -1);
        }
    }

    private static void sendJsonQuietly(
            HttpExchange exchange,
            int status,
            Map<String, Object> response,
            ObjectMapper objectMapper,
            long maxResponseBytes) {
        try {
            sendJson(exchange, status, response, objectMapper, maxResponseBytes);
        } catch (IOException responseFailure) {
            logger.debug("Failed to write serving HTTP error response: {}", responseFailure.getMessage());
        }
    }

    private static long positiveLongProperty(String name, long defaultValue) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Fall through to the documented default.
        }
        logger.warn("Ignoring invalid {}='{}'; using {}", name, raw, defaultValue);
        return defaultValue;
    }

    private static int positiveIntProperty(String name, int defaultValue) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Fall through to the documented default.
        }
        logger.warn("Ignoring invalid {}='{}'; using {}", name, raw, defaultValue);
        return defaultValue;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        server.stop(2);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    interface Api {
        ResponseEntity<Map<String, Object>> load(LoadRequest request);

        ResponseEntity<Map<String, Object>> status();

        ResponseEntity<Map<String, Object>> generate(Map<String, Object> request);

        ResponseEntity<Map<String, Object>> unload();
    }

    private enum Operation {
        LOAD(true),
        STATUS(false),
        GENERATE(true),
        UNLOAD(false);

        private final boolean requiresJsonBody;

        Operation(boolean requiresJsonBody) {
            this.requiresJsonBody = requiresJsonBody;
        }

        boolean requiresJsonBody() {
            return requiresJsonBody;
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            return new Thread(runnable, "serving-http-" + sequence.incrementAndGet());
        }
    }

    private static final class PayloadTooLargeException extends IOException {
    }

    private static final class ResponseTooLargeException extends RuntimeException {
        private ResponseTooLargeException(long limit) {
            super("serialized response exceeds " + limit + " bytes");
        }
    }

    private static final class CappedByteArrayOutputStream extends ByteArrayOutputStream {
        private final long limit;
        private boolean exceeded;

        private CappedByteArrayOutputStream(long limit) {
            super((int) Math.min(limit, 8192L));
            this.limit = limit;
        }

        private boolean exceeded() {
            return exceeded;
        }

        @Override
        public synchronized void write(int value) {
            if (exceeded) {
                return;
            }
            if (count + 1L > limit) {
                exceeded = true;
                throw new ResponseTooLargeException(limit);
            }
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            if (exceeded) {
                return;
            }
            if (count + (long) length > limit) {
                exceeded = true;
                throw new ResponseTooLargeException(limit);
            }
            super.write(bytes, offset, length);
        }
    }

    private static final class BadRequestException extends IOException {
        private BadRequestException(String message) {
            super(message);
        }

        private BadRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
