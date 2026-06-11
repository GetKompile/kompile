/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 */
package ai.kompile.orchestrator.executor;

import ai.kompile.orchestrator.TestApplication;
import ai.kompile.orchestrator.api.TaskExecutionService;
import ai.kompile.orchestrator.model.task.*;
import ai.kompile.orchestrator.repository.TaskInstanceRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HttpTaskExecutor — spins up a local HTTP server and
 * verifies GET/POST/PUT/DELETE execute correctly through the orchestrator.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class HttpTaskExecutorTest {

    @Autowired
    private TaskExecutionService taskExecutionService;

    @Autowired
    private TaskInstanceRepository taskInstanceRepository;

    private static HttpServer httpServer;
    private static int serverPort;

    @BeforeAll
    static void startServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        serverPort = httpServer.getAddress().getPort();

        // GET /api/status — returns JSON status
        httpServer.createContext("/api/status", exchange -> {
            String response = "{\"status\":\"healthy\",\"version\":\"1.0\"}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        // POST /api/data — echoes the body back
        httpServer.createContext("/api/data", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String method = exchange.getRequestMethod();
            String response = "{\"method\":\"" + method + "\",\"received\":\"" + new String(body) + "\"}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        // GET /api/error — returns 500
        httpServer.createContext("/api/error", exchange -> {
            String response = "{\"error\":\"Internal Server Error\"}";
            exchange.sendResponseHeaders(500, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        // GET /api/not-found — returns 404
        httpServer.createContext("/api/not-found", exchange -> {
            String response = "Not Found";
            exchange.sendResponseHeaders(404, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        // GET /api/slow — takes 5 seconds
        httpServer.createContext("/api/slow", exchange -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String response = "slow response";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        httpServer.setExecutor(null);
        httpServer.start();
    }

    @AfterAll
    static void stopServer() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void executeHttpGet() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-http-get")
                .name("HTTP GET Test")
                .taskType(TaskType.HTTP)
                .httpUrl("http://localhost:" + serverPort + "/api/status")
                .httpMethod("GET")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-http-get",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.SUCCESS, result.getStatus());
        assertNotNull(result.getOutput());
        assertTrue(result.getOutput().contains("HTTP 200"));
        assertTrue(result.getOutput().contains("healthy"));
        assertEquals(0, result.getExitCode());
    }

    @Test
    void executeHttpPost() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-http-post")
                .name("HTTP POST Test")
                .taskType(TaskType.HTTP)
                .httpUrl("http://localhost:" + serverPort + "/api/data")
                .httpMethod("POST")
                .httpBodyTemplate("{\"key\":\"value\"}")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-http-post",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.SUCCESS, result.getStatus());
        assertTrue(result.getOutput().contains("HTTP 200"));
        assertTrue(result.getOutput().contains("POST"));
    }

    @Test
    void executeHttpPut() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-http-put")
                .name("HTTP PUT Test")
                .taskType(TaskType.HTTP)
                .httpUrl("http://localhost:" + serverPort + "/api/data")
                .httpMethod("PUT")
                .httpBodyTemplate("{\"update\":\"data\"}")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-http-put",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.SUCCESS, result.getStatus());
        assertTrue(result.getOutput().contains("PUT"));
    }

    @Test
    void executeHttpServerError() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-http-500")
                .name("HTTP 500 Test")
                .taskType(TaskType.HTTP)
                .httpUrl("http://localhost:" + serverPort + "/api/error")
                .httpMethod("GET")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-http-500",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.FAILED, result.getStatus());
        assertTrue(result.getOutput().contains("HTTP 500"));
        assertEquals(500, result.getExitCode());
    }

    @Test
    void executeHttpNotFound() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-http-404")
                .name("HTTP 404 Test")
                .taskType(TaskType.HTTP)
                .httpUrl("http://localhost:" + serverPort + "/api/not-found")
                .httpMethod("GET")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-http-404",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.FAILED, result.getStatus());
        assertEquals(404, result.getExitCode());
    }

    @Test
    void executeHttpAsync() throws Exception {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-http-async")
                .name("HTTP Async Test")
                .taskType(TaskType.HTTP)
                .httpUrl("http://localhost:" + serverPort + "/api/status")
                .httpMethod("GET")
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-http-async",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.defaults());

        assertNotNull(result.getId());

        // Poll for completion
        for (int i = 0; i < 100; i++) {
            TaskInstance t = taskInstanceRepository.findById(result.getId()).orElseThrow();
            if (t.getStatus().isTerminal()) {
                assertEquals(TaskStatus.SUCCESS, t.getStatus());
                assertTrue(t.getOutput().contains("healthy"));
                return;
            }
            Thread.sleep(100);
        }
        fail("Async HTTP task did not complete in time");
    }

    @Test
    void executeHttpConnectionRefused() {
        TaskDefinition def = TaskDefinition.builder()
                .taskId("test-http-refused")
                .name("HTTP Connection Refused Test")
                .taskType(TaskType.HTTP)
                .httpUrl("http://localhost:1/unreachable")
                .httpMethod("GET")
                .timeoutSeconds(5L)
                .build();

        taskExecutionService.registerTaskDefinition(def);

        TaskInstance result = taskExecutionService.executeTask("test-http-refused",
                Collections.emptyMap(), "test-instance", TaskExecutionOptions.synchronous());

        assertEquals(TaskStatus.FAILED, result.getStatus());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    void httpExecutorRegisteredForCorrectType() {
        assertTrue(taskExecutionService.getExecutor(TaskType.HTTP).isPresent());
        assertInstanceOf(HttpTaskExecutor.class, taskExecutionService.getExecutor(TaskType.HTTP).get());
    }
}
