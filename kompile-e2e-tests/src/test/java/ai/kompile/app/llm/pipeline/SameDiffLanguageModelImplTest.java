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

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link SameDiffLanguageModelImpl}.
 *
 * <p>These tests exercise the bean's lifecycle without going anywhere near a real
 * SameDiff graph or tokenizer (those are exercised in the samediff-llm pipeline step
 * tests). We verify:</p>
 *
 * <ol>
 *     <li>An unloaded bean throws on {@code generateResponse}.</li>
 *     <li>{@code isLoaded() == false} initially.</li>
 *     <li>{@code loadModel(...)} fails fast on bogus paths (so callers get a clear
 *         error instead of garbage).</li>
 * </ol>
 */
class SameDiffLanguageModelImplTest {

    @Test
    void unloadedBeanReportsNotLoaded() {
        SameDiffLanguageModelImpl model = new SameDiffLanguageModelImpl(Optional.empty(), Optional.empty());
        assertFalse(model.isLoaded(), "fresh bean should not be loaded");
        assertNull(model.getLoadedModelId(), "fresh bean should have no loaded modelId");
        assertEquals(-1L, model.getLoadDurationMs(), "fresh bean should report -1 load duration");
    }

    @Test
    void generateResponseThrowsWhenNotLoaded() {
        SameDiffLanguageModelImpl model = new SameDiffLanguageModelImpl(Optional.empty(), Optional.empty());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> model.generateResponse("hello", List.of()));
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("/api/llm/load"),
                "error should hint at the load endpoint, was: " + ex.getMessage());
    }

    @Test
    void generateResponseWithToolsThrowsWhenNotLoaded() {
        SameDiffLanguageModelImpl model = new SameDiffLanguageModelImpl(Optional.empty(), Optional.empty());
        assertThrows(IllegalStateException.class,
                () -> {
                    ChatResponse ignored = model.generateResponseWithPotentialToolCalls("hello", List.of());
                });
    }

    @Test
    void unloadIsSafeWhenNothingLoaded() {
        SameDiffLanguageModelImpl model = new SameDiffLanguageModelImpl(Optional.empty(), Optional.empty());
        // Should not throw even when nothing is loaded.
        model.unloadModel();
        assertFalse(model.isLoaded());
    }

    @Test
    void loadModelRejectsMissingModelFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
        SameDiffLanguageModelImpl model = new SameDiffLanguageModelImpl(Optional.empty(), Optional.empty());
        java.nio.file.Path missingModel = tmp.resolve("does-not-exist.sdz");
        java.nio.file.Path tokenizer = tmp.resolve("tokenizer.json");
        Exception ex = assertThrows(Exception.class,
                () -> model.loadModel("bogus", missingModel, tokenizer, null));
        assertTrue(ex.getMessage().toLowerCase().contains("does not exist"),
                "error should mention missing file, was: " + ex.getMessage());
        assertFalse(model.isLoaded(), "bean should remain unloaded after failed load");
    }

    @Test
    void loadModelRejectsMissingTokenizerFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        SameDiffLanguageModelImpl model = new SameDiffLanguageModelImpl(Optional.empty(), Optional.empty());
        java.nio.file.Path fakeModel = tmp.resolve("model.sdz");
        java.nio.file.Files.writeString(fakeModel, "fake");
        java.nio.file.Path missingTokenizer = tmp.resolve("missing-tokenizer.json");
        Exception ex = assertThrows(Exception.class,
                () -> model.loadModel("bogus", fakeModel, missingTokenizer, null));
        assertTrue(ex.getMessage().toLowerCase().contains("does not exist"),
                "error should mention missing tokenizer file, was: " + ex.getMessage());
        assertFalse(model.isLoaded(), "bean should remain unloaded after failed load");
    }
}
