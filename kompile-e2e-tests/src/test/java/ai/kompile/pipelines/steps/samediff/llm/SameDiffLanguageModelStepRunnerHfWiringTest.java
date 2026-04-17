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

package ai.kompile.pipelines.steps.samediff.llm;

import ai.kompile.pipelines.framework.api.llm.LLMStepConfig;
import ai.kompile.pipelines.steps.samediff.nlp.SameDiffHuggingFaceTokenizer;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link SameDiffLanguageModelStepRunner} accepts the new "huggingface"
 * tokenizer types in its config without crashing during type validation, and that the
 * {@link SameDiffHuggingFaceTokenizer} adapter parses the supplied tokenizer URI as a
 * filesystem path.
 *
 * <p>This test does NOT require a real model or a real tokenizer.json — it only
 * exercises the config wiring code paths added in Task A. End-to-end inference is
 * covered by the kompile e2e test suite.</p>
 */
public class SameDiffLanguageModelStepRunnerHfWiringTest {

    @Test
    public void llmStepConfigAcceptsHuggingfaceTokenizerType() {
        // The LLMStepConfig layer must accept the new tokenizer types added in Task A
        // ("huggingface", "hf", "bpe") without throwing during construction or get().
        for (String type : new String[] { "huggingface", "hf", "bpe" }) {
            LLMStepConfig cfg = LLMStepConfig.builder()
                    .name("hf-test-" + type)
                    .type("SAMEDIFF_LANGUAGE_MODEL")
                    .modelUri("file:///does/not/matter.sdz")
                    .tokenizerUri("file:///some/tokenizer.json")
                    .tokenizerType(type)
                    .build();
            assertEquals(type, cfg.getTokenizerType(), "tokenizerType round-trip mismatch for: " + type);
            // String getter via the generic typed map should also work
            assertEquals(type, cfg.get("tokenizerType"));
        }
    }

    @Test
    public void hfAdapterResolvesPlainPath() {
        // Plain path, no file:// prefix
        File f = SameDiffHuggingFaceTokenizer.resolveTokenizerFile("/tmp/no-such-tokenizer.json");
        assertNotNull(f);
        assertEquals("/tmp/no-such-tokenizer.json", f.getAbsolutePath());
    }

    @Test
    public void hfAdapterResolvesFileUri() {
        File f = SameDiffHuggingFaceTokenizer.resolveTokenizerFile("file:///tmp/tok.json");
        assertNotNull(f);
        assertTrue(f.getAbsolutePath().endsWith("/tmp/tok.json"),
                "Expected path to end with /tmp/tok.json, got: " + f.getAbsolutePath());
    }

    @Test
    public void hfAdapterRejectsEmptyVocabUri() {
        SameDiffHuggingFaceTokenizer tok = new SameDiffHuggingFaceTokenizer();
        assertThrows(IllegalArgumentException.class, () -> tok.initialize(null, null));
        assertThrows(IllegalArgumentException.class, () -> tok.initialize("", null));
    }
}
