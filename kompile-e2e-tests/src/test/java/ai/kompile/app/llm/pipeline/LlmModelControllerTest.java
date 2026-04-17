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
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.llm.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration test for {@link LlmModelController}.
 *
 * <p>Uses a fake {@link SameDiffLanguageModelImpl} subclass that overrides
 * {@code loadModel} so we don't have to wire up a real SameDiff graph + tokenizer,
 * and a tiny in-process {@link HttpServer} that mimics the kompile-model-staging
 * endpoints we depend on.</p>
 */
class LlmModelControllerTest {

    /** A loadable fake that bypasses SameDiff init. */
    static class FakeSameDiffLanguageModel extends SameDiffLanguageModelImpl {
        volatile String lastModelId;
        volatile Path lastModelFile;
        volatile Path lastTokenizerFile;
        volatile boolean loaded;
        volatile long durationMs = 42L;

        FakeSameDiffLanguageModel() {
            super(Optional.empty(), Optional.empty());
        }

        @Override
        public void loadModel(String modelId, Path modelFile, Path tokenizerFile, Map<String, Object> configOpts) {
            this.lastModelId = modelId;
            this.lastModelFile = modelFile;
            this.lastTokenizerFile = tokenizerFile;
            this.loaded = true;
        }

        @Override
        public void unloadModel() {
            this.loaded = false;
        }

        @Override
        public boolean isLoaded() { return loaded; }

        @Override
        public String getLoadedModelId() { return loaded ? lastModelId : null; }

        @Override
        public long getLoadDurationMs() { return loaded ? durationMs : -1L; }
    }

    private MockMvc buildMockMvc(FakeSameDiffLanguageModel fake, String stagingUrl, Path cacheDir) {
        LlmModelController controller = new LlmModelController(
                fake,
                new RestTemplateBuilder(),
                new ObjectMapper(),
                stagingUrl,
                cacheDir.toString());
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void statusReportsUnloadedInitially(@TempDir Path tmp) throws Exception {
        FakeSameDiffLanguageModel fake = new FakeSameDiffLanguageModel();
        MockMvc mvc = buildMockMvc(fake, "http://localhost:0", tmp);

        mvc.perform(get("/api/llm/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loaded").value(false))
                .andExpect(jsonPath("$.modelId").doesNotExist());
    }

    @Test
    void loadFailsWithoutModelId(@TempDir Path tmp) throws Exception {
        FakeSameDiffLanguageModel fake = new FakeSameDiffLanguageModel();
        MockMvc mvc = buildMockMvc(fake, "http://localhost:0", tmp);

        mvc.perform(post("/api/llm/load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("modelId is required")));
    }

    @Test
    void loadDownloadsFromStagingAndDelegatesToLanguageModel(@TempDir Path tmp) throws Exception {
        FakeSameDiffLanguageModel fake = new FakeSameDiffLanguageModel();
        FakeStagingServer staging = FakeStagingServer.start();
        try {
            staging.registerModel("test-llm", "model.sdz", "tokenizer.json", "MODEL_BYTES", "TOKENIZER_BYTES");

            MockMvc mvc = buildMockMvc(fake, staging.baseUrl(), tmp);
            String body = "{\"modelId\":\"test-llm\"}";

            mvc.perform(post("/api/llm/load")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.loaded").value(true))
                    .andExpect(jsonPath("$.modelId").value("test-llm"))
                    .andExpect(jsonPath("$.modelFile").exists())
                    .andExpect(jsonPath("$.tokenizerFile").exists());

            assertEquals("test-llm", fake.lastModelId);
            assertNotNull(fake.lastModelFile);
            assertNotNull(fake.lastTokenizerFile);
            assertTrue(Files.exists(fake.lastModelFile));
            assertTrue(Files.exists(fake.lastTokenizerFile));
            assertEquals("MODEL_BYTES", Files.readString(fake.lastModelFile));
            assertEquals("TOKENIZER_BYTES", Files.readString(fake.lastTokenizerFile));
        } finally {
            staging.stop();
        }
    }

    @Test
    void unloadClearsLoadedState(@TempDir Path tmp) throws Exception {
        FakeSameDiffLanguageModel fake = new FakeSameDiffLanguageModel();
        // Pretend it was loaded already.
        fake.loadModel("preloaded", tmp.resolve("m.sdz"), tmp.resolve("t.json"), null);
        assertTrue(fake.isLoaded());

        MockMvc mvc = buildMockMvc(fake, "http://localhost:0", tmp);
        mvc.perform(post("/api/llm/unload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unloaded").value(true))
                .andExpect(jsonPath("$.previousModelId").value("preloaded"));

        assertEquals(false, fake.isLoaded());
    }

    /** Tiny embedded HTTP server mimicking the staging registry endpoints used by the controller. */
    static class FakeStagingServer {
        private final HttpServer server;
        private final Map<String, ModelEntry> models = new HashMap<>();

        private FakeStagingServer(HttpServer server) {
            this.server = server;
        }

        static FakeStagingServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeStagingServer fs = new FakeStagingServer(server);

            server.createContext("/api/staging/registry/model/", exchange -> {
                String requestPath = exchange.getRequestURI().getPath();
                String suffix = requestPath.substring("/api/staging/registry/model/".length());
                String modelId;
                String tail;
                int slash = suffix.indexOf('/');
                if (slash < 0) {
                    modelId = suffix;
                    tail = "";
                } else {
                    modelId = suffix.substring(0, slash);
                    tail = suffix.substring(slash + 1);
                }
                ModelEntry entry = fs.models.get(modelId);
                if (entry == null) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                byte[] body;
                String contentType;
                switch (tail) {
                    case "":
                        body = entry.toJson().getBytes();
                        contentType = "application/json";
                        break;
                    case "download/model":
                        body = entry.modelBytes.getBytes();
                        contentType = "application/octet-stream";
                        break;
                    case "download/vocab":
                        body = entry.tokenizerBytes.getBytes();
                        contentType = "application/octet-stream";
                        break;
                    default:
                        exchange.sendResponseHeaders(404, -1);
                        exchange.close();
                        return;
                }
                exchange.getResponseHeaders().add("Content-Type", contentType);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });

            server.start();
            return fs;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void registerModel(String modelId, String modelFile, String vocabFile, String modelBytes, String tokenizerBytes) {
            models.put(modelId, new ModelEntry(modelId, modelFile, vocabFile, modelBytes, tokenizerBytes));
        }

        void stop() {
            server.stop(0);
        }

        static class ModelEntry {
            final String modelId;
            final String modelFile;
            final String vocabFile;
            final String modelBytes;
            final String tokenizerBytes;

            ModelEntry(String modelId, String modelFile, String vocabFile, String modelBytes, String tokenizerBytes) {
                this.modelId = modelId;
                this.modelFile = modelFile;
                this.vocabFile = vocabFile;
                this.modelBytes = modelBytes;
                this.tokenizerBytes = tokenizerBytes;
            }

            String toJson() {
                return "{\"model_id\":\"" + modelId + "\","
                        + "\"model_file\":\"" + modelFile + "\","
                        + "\"vocab_file\":\"" + vocabFile + "\","
                        + "\"path\":\"" + modelId + "\"}";
            }
        }
    }
}
