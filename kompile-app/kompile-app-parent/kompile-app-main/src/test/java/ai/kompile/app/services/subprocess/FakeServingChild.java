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
package ai.kompile.app.services.subprocess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * Stands in for the serving child in {@link ServingSubprocessLauncherVerdictTest}. The launcher
 * starts it with the launcher's real args file; the {@code fake.serving.scenario} system property
 * picks what it does. It serves the model named in the args file on the port named there, through
 * the status and unload endpoints the launcher calls.
 */
public final class FakeServingChild {

    static final String SCENARIO_PROPERTY = "fake.serving.scenario";
    /** Serves until it is stopped. */
    static final String READY = "ready";
    /** Exits before it serves, as a child whose native library is missing does. */
    static final String CRASH_BEFORE_READY = "crash-before-ready";
    /** Serves, and does not exit when asked to terminate: only a kill stops it. */
    static final String IGNORE_TERM = "ignore-term";
    /** Serves, but never answers a request to unload its model. */
    static final String HANG_UNLOAD = "hang-unload";

    /** A POST here makes a serving child exit once it has answered, as one whose device faults does. */
    static final String EXIT_PATH = "/test/exit";
    static final int EXIT_BEFORE_READY = 3;
    static final int EXIT_AFTER_READY = 7;
    static final String MISSING_NATIVE_LIBRARY =
            "java.lang.UnsatisfiedLinkError: no jnind4jcuda in java.library.path";
    static final String DEVICE_FAULT = "FATAL: CUDA error 700 (an illegal memory access was encountered)";

    /** Output ahead of the crash, so its cause is the last line the launcher reads. */
    private static final int LINES_BEFORE_CRASH = 20;

    private FakeServingChild() {
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode servingArgs = json.readTree(Path.of(args[0]).toFile());
        int port = servingArgs.path("port").asInt();
        String modelId = servingArgs.path("modelId").asText();
        String scenario = System.getProperty(SCENARIO_PROPERTY, READY);
        switch (scenario) {
            case CRASH_BEFORE_READY -> {
                for (int line = 1; line <= LINES_BEFORE_CRASH; line++) {
                    System.err.println("loading native backend, step " + line);
                }
                System.err.println(MISSING_NATIVE_LIBRARY);
                System.err.flush();
                System.exit(EXIT_BEFORE_READY);
            }
            // A terminate request runs the shutdown hooks, and this one never returns
            case IGNORE_TERM -> Runtime.getRuntime().addShutdownHook(new Thread(FakeServingChild::blockForever));
            case READY, HANG_UNLOAD -> {
            }
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(
                InetAddress.getByName(ServingSubprocessLauncher.SERVING_BIND_HOST), port), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        String status = json.writeValueAsString(Map.of("loaded", true, "modelId", modelId));
        server.createContext("/api/llm/status", exchange -> respond(exchange, status));
        server.createContext("/api/llm/unload", exchange -> {
            if (HANG_UNLOAD.equals(scenario)) {
                blockForever();
            }
            respond(exchange, "{\"loaded\":false}");
        });
        server.createContext(EXIT_PATH, exchange -> {
            respond(exchange, "{}");
            System.err.println(DEVICE_FAULT);
            System.err.flush();
            new Thread(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.exit(EXIT_AFTER_READY);
            }, "fake-serving-child-exit").start();
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void blockForever() {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
