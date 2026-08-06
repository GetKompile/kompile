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
package ai.kompile.app.llm.pipeline;

import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import ai.kompile.core.llm.LanguageModel;
import ai.kompile.core.llm.NoOpLanguageModelImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ManagedServingEndpointTest {

    @TempDir
    Path tempDir;

    @Test
    void servingClientsFollowUiCliManagedEndpointChanges() throws Exception {
        ServiceEndpointsConfigManager manager = new ServiceEndpointsConfigManager(
                tempDir.resolve(ServiceEndpointsConfigManager.FILENAME));
        manager.update(Map.of(ServiceEndpointsConfigManager.SERVING_URL_KEY,
                "http://127.0.0.1:18091/"));

        LlmBeanConfiguration configuration = new LlmBeanConfiguration(manager);
        SubprocessLanguageModelImpl chatModel = configuration.managedServingChatModel();
        LlmObservabilityService observability = new LlmObservabilityService(null, manager);

        assertEquals("http://127.0.0.1:18091", chatModel.currentServingUrl());
        assertEquals("http://127.0.0.1:18091", observability.getServingUrl());

        manager.update(Map.of(ServiceEndpointsConfigManager.SERVING_URL_KEY,
                "http://127.0.0.1:28091"));

        assertEquals("http://127.0.0.1:28091", chatModel.currentServingUrl());
        assertEquals("http://127.0.0.1:28091", observability.getServingUrl());
    }

    @Test
    void managedServingClientUsesNativeGenerateContract() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/llm/generate", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"generatedText\":\"KOMPILE_OK\",\"finishReason\":\"completed\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            SubprocessLanguageModelImpl client = new SubprocessLanguageModelImpl(
                    "http://127.0.0.1:" + server.getAddress().getPort());

            assertEquals("KOMPILE_OK", client.generateResponse("question", List.of("context")));
            JsonNode request = new ObjectMapper().readTree(requestBody.get());
            assertTrue(request.path("prompt").asText().contains("question"));
            assertTrue(request.path("prompt").asText().contains("context"));
            assertEquals(256, request.path("maxTokens").asInt());
            assertFalse(request.has("messages"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void localServingContextNeverRoutesBackToManagedEndpoint() {
        ServiceEndpointsConfigManager manager = new ServiceEndpointsConfigManager(
                tempDir.resolve(ServiceEndpointsConfigManager.FILENAME));
        LlmObservabilityService observability = new LlmObservabilityService(
                mock(SameDiffLanguageModelImpl.class), manager);

        assertFalse(observability.isSubprocessMode());
        assertNull(observability.getServingUrl());
    }

    @Test
    void proxyBeanConditionUsesOnlyTheInternalServingRoleMarker() throws Exception {
        ConditionalOnProperty condition = LlmBeanConfiguration.class
                .getMethod("managedServingChatModel")
                .getAnnotation(ConditionalOnProperty.class);

        assertArrayEquals(new String[]{"kompile.llm.direct-serving.enabled"}, condition.name());
        assertEquals("false", condition.havingValue());
        assertTrue(condition.matchIfMissing());
    }

    @Test
    void managedServingModelIsPrimaryWhenCoreFallbackIsPresent() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(LlmBeanConfiguration.class, NoOpLanguageModelImpl.class);
            context.refresh();

            assertEquals(1, context.getBeansOfType(LanguageModel.class).size());
            assertInstanceOf(SubprocessLanguageModelImpl.class,
                    context.getBean(LanguageModel.class));
        }
    }
}
