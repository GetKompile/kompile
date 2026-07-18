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

package ai.kompile.pipelines.steps.samediff.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SameDiffLanguageModelStepRunnerTest {

    @Test
    void preservesAnExplicitGraphOutput() {
        assertEquals(
                "decoder_scores",
                SameDiffLanguageModelStepRunner.resolveLogitsOutputName(
                        "decoder_scores",
                        List.of("lm_logits", "decoder_scores")));
    }

    @Test
    void discoversLfmLmLogitsForTheConventionalDefault() {
        assertEquals(
                "lm_logits",
                SameDiffLanguageModelStepRunner.resolveLogitsOutputName(
                        "logits",
                        List.of("present_0_key", "present_0_value", "lm_logits")));
    }

    @Test
    void discoversASingleConventionalLogitsOutput() {
        assertEquals(
                "decoder/output_logits",
                SameDiffLanguageModelStepRunner.resolveLogitsOutputName(
                        null,
                        List.of("hidden_state", "decoder/output_logits")));
    }

    @Test
    void rejectsAMissingExplicitOutputInsteadOfSilentlySwitchingModels() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> SameDiffLanguageModelStepRunner.resolveLogitsOutputName(
                        "requested_scores",
                        List.of("lm_logits")));

        assertTrue(exception.getMessage().contains("requested_scores"));
        assertTrue(exception.getMessage().contains("lm_logits"));
    }

    @Test
    void rejectsAmbiguousConventionalOutputs() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> SameDiffLanguageModelStepRunner.resolveLogitsOutputName(
                        "logits",
                        List.of("text_logits", "vision_logits")));

        assertTrue(exception.getMessage().contains("ambiguous"));
        assertTrue(exception.getMessage().contains("logitsOutputName"));
    }

    @Test
    void rejectsGraphsWithoutALogitsOutput() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> SameDiffLanguageModelStepRunner.resolveLogitsOutputName(
                        "",
                        List.of("hidden_state")));

        assertTrue(exception.getMessage().contains("no conventional logits output"));
    }
}
