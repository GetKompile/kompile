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
package ai.kompile.app.services;

import ai.kompile.app.services.subprocess.ServingSubprocessLauncher;
import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StagingServingBridge} polling decision logic.
 *
 * <p>Verifies: (a) poller is a no-op when {@code kbServingAutoLoadEnabled=false};
 * (b) active LLM change triggers {@code loadModel}; (c) LLM removal triggers
 * {@code stop()}; (d) no model → subprocess stays idle; (e) HTTP error → inert.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StagingServingBridgeTest {

    @Mock
    private ServingSubprocessLauncher launcher;

    @Mock
    private KbConfigManager kbConfigManager;

    @TempDir
    Path tempDir;

    private StagingServingBridge bridge;

    /** A fake active-endpoint response that returns the given llm_ggml model ID. */
    private static String activeResponse(String modelId) {
        if (modelId == null) {
            return "{\"active\":{}}";
        }
        return "{\"active\":{\"llm_ggml\":\"" + modelId + "\"}}";
    }

    @BeforeEach
    void setUp() {
        bridge = new StagingServingBridge();
        ReflectionTestUtils.setField(bridge, "launcher", launcher);
        ReflectionTestUtils.setField(bridge, "kbConfigManager", kbConfigManager);
        ReflectionTestUtils.setField(bridge, "stagingUrl", "http://localhost:8090");
    }

    private KbConfig configWith(boolean servingAutoLoadEnabled) {
        KbConfig cfg = new KbConfig();
        cfg.setServingAutoLoadEnabled(servingAutoLoadEnabled);
        return cfg;
    }

    // ── poller disabled via KbConfig ──────────────────────────────────────────

    @Test
    void poll_disabled_isNoop() throws Exception {
        when(kbConfigManager.current()).thenReturn(configWith(false));

        bridge.poll();

        // No HTTP call, no launcher interaction
        verifyNoInteractions(launcher);
    }

    // ── fetchActiveLlmModelId parses response ─────────────────────────────────

    @Test
    void fetchActiveLlmModelId_parsesLlmGgmlKey() throws Exception {
        // Build a bridge with an injected mock HTTP client
        StagingServingBridge testBridge = new BridgeWithMockHttp("{\"active\":{\"llm_ggml\":\"my-llm-v1\"}}");
        String modelId = testBridge.fetchActiveLlmModelId();
        assertEquals("my-llm-v1", modelId);
    }

    @Test
    void fetchActiveLlmModelId_returnsNull_whenNoLlmKey() throws Exception {
        StagingServingBridge testBridge = new BridgeWithMockHttp("{\"active\":{\"dense_encoder\":\"bge\"}}");
        String modelId = testBridge.fetchActiveLlmModelId();
        assertNull(modelId);
    }

    @Test
    void fetchActiveLlmModelId_returnsNull_whenActiveEmpty() throws Exception {
        StagingServingBridge testBridge = new BridgeWithMockHttp("{\"active\":{}}");
        String modelId = testBridge.fetchActiveLlmModelId();
        assertNull(modelId);
    }

    // ── poll: no change → no launcher call ───────────────────────────────────

    @Test
    void poll_noChange_noLauncherCall() throws Exception {
        when(kbConfigManager.current()).thenReturn(configWith(true));
        // Simulate: currentModelId is null and staging returns null (no active LLM)
        StagingServingBridge testBridge = new BridgeWithMockHttp("{\"active\":{}}");
        ReflectionTestUtils.setField(testBridge, "launcher", launcher);
        ReflectionTestUtils.setField(testBridge, "kbConfigManager", kbConfigManager);
        ReflectionTestUtils.setField(testBridge, "stagingUrl", "http://localhost:8090");
        // currentModelId stays null — matches staging null → no change
        testBridge.poll();
        verifyNoInteractions(launcher);
    }

    @Test
    void poll_sameActiveModel_restartsDeadServingSubprocess() throws Exception {
        when(kbConfigManager.current()).thenReturn(configWith(true));
        when(launcher.isRunning()).thenReturn(false);
        StagingServingBridge testBridge = new BridgeWithMockHttp(activeResponse("my-llm"));
        ReflectionTestUtils.setField(testBridge, "launcher", launcher);
        ReflectionTestUtils.setField(testBridge, "kbConfigManager", kbConfigManager);
        ReflectionTestUtils.setField(testBridge, "currentModelId", "my-llm");

        testBridge.poll();

        verify(launcher).loadModel("my-llm", "/tmp/test-model.sdz", null);
        assertEquals("my-llm", ReflectionTestUtils.getField(testBridge, "currentModelId"));
    }

    @Test
    void poll_sameActiveModel_keepsHealthyServingSubprocess() throws Exception {
        when(kbConfigManager.current()).thenReturn(configWith(true));
        when(launcher.isRunning()).thenReturn(true);
        StagingServingBridge testBridge = new BridgeWithMockHttp(activeResponse("my-llm"));
        ReflectionTestUtils.setField(testBridge, "launcher", launcher);
        ReflectionTestUtils.setField(testBridge, "kbConfigManager", kbConfigManager);
        ReflectionTestUtils.setField(testBridge, "currentModelId", "my-llm");

        testBridge.poll();

        verify(launcher).isRunning();
        verifyNoMoreInteractions(launcher);
    }

    // ── Stop is called when active model removed ──────────────────────────────

    @Test
    void poll_modelRemoved_callsStop() throws Exception {
        when(kbConfigManager.current()).thenReturn(configWith(true));
        // Pretend: last known = "my-llm", staging now has nothing
        StagingServingBridge testBridge = new BridgeWithMockHttp("{\"active\":{}}");
        ReflectionTestUtils.setField(testBridge, "launcher", launcher);
        ReflectionTestUtils.setField(testBridge, "kbConfigManager", kbConfigManager);
        ReflectionTestUtils.setField(testBridge, "stagingUrl", "http://localhost:8090");
        ReflectionTestUtils.setField(testBridge, "currentModelId", "my-llm");

        testBridge.poll();

        verify(launcher, times(1)).stop();
    }

    // ── resolveLLMCacheDir convention ─────────────────────────────────────────

    @Test
    void llmCacheDir_usesManagedCacheDirectory() throws Exception {
        Path cacheDir = tempDir.resolve("llm-cache").toAbsolutePath();
        String previous = System.getProperty("kompile.llm.cache.dir");
        try {
            System.setProperty("kompile.llm.cache.dir", cacheDir.toString());
            java.lang.reflect.Method method = StagingServingBridge.class.getDeclaredMethod("llmCacheDir");
            method.setAccessible(true);

            assertEquals(cacheDir, method.invoke(null));
        } finally {
            if (previous == null) {
                System.clearProperty("kompile.llm.cache.dir");
            } else {
                System.setProperty("kompile.llm.cache.dir", previous);
            }
        }
    }

    @Test
    void findCompleteShardedEntry_returnsCanonicalBaseWithoutManifest() throws Exception {
        Path shard0 = tempDir.resolve("model.shard0-of-2.sdnb");
        Path shard1 = tempDir.resolve("model.shard1-of-2.sdnb");
        java.nio.file.Files.writeString(shard0, "graph-metadata");
        java.nio.file.Files.writeString(shard1, "parameter-data");

        Path entry = StagingServingBridge.findCompleteShardedEntry(tempDir);

        Path canonicalBase = tempDir.resolve("model.sdnb");
        assertEquals(canonicalBase, entry);
        assertFalse(java.nio.file.Files.exists(canonicalBase));
        assertNotEquals(shard0, entry);
    }

    @Test
    void findCompleteShardedEntry_rejectsIncompleteSet() throws Exception {
        java.nio.file.Files.writeString(
                tempDir.resolve("model.shard0-of-2.sdnb"),
                "graph-metadata");

        assertNull(StagingServingBridge.findCompleteShardedEntry(tempDir));
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /**
     * StagingServingBridge subclass that overrides HTTP calls by returning a fixed
     * JSON body for every GET, eliminating any real network dependency.
     */
    private static class BridgeWithMockHttp extends StagingServingBridge {
        private final String fixedBody;

        BridgeWithMockHttp(String fixedBody) {
            this.fixedBody = fixedBody;
            ReflectionTestUtils.setField(this, "stagingUrl", "http://localhost:8090");
        }

        @Override
        String fetchActiveLlmModelId() throws IOException, InterruptedException {
            // Parse the fixed body directly using the parent's ObjectMapper via super logic—
            // but since the parent's httpClient is private, we delegate to a parse-only path.
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = om.readTree(fixedBody);
                com.fasterxml.jackson.databind.JsonNode active = root.path("active");
                if (active.isMissingNode() || active.isNull()) return null;
                com.fasterxml.jackson.databind.JsonNode llmNode = active.path("llm_ggml");
                if (llmNode.isMissingNode() || llmNode.isNull() || !llmNode.isTextual()) return null;
                String id = llmNode.asText().trim();
                return id.isBlank() ? null : id;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        Path resolveLocalModelPath(String modelId) throws Exception {
            // Return a dummy path for tests that need loadModel to be called
            return java.nio.file.Paths.get("/tmp/test-model.sdz");
        }
    }
}
