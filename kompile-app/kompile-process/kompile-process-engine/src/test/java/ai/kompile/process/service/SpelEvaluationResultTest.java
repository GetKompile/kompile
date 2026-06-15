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

package ai.kompile.process.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SpelEvaluationResult}.
 */
class SpelEvaluationResultTest {

    @Test
    void noArgConstructor_createsInstanceWithNullFields() {
        SpelEvaluationResult result = new SpelEvaluationResult();
        assertThat(result.getResult()).isNull();
        assertThat(result.getType()).isNull();
        assertThat(result.getError()).isNull();
    }

    @Test
    void allArgsConstructor_setsAllFields() {
        SpelEvaluationResult result = new SpelEvaluationResult("hello", "String", null);
        assertThat(result.getResult()).isEqualTo("hello");
        assertThat(result.getType()).isEqualTo("String");
        assertThat(result.getError()).isNull();
    }

    @Test
    void builder_setsAllFields() {
        SpelEvaluationResult result = SpelEvaluationResult.builder()
                .result(42)
                .type("Integer")
                .error(null)
                .build();

        assertThat(result.getResult()).isEqualTo(42);
        assertThat(result.getType()).isEqualTo("Integer");
        assertThat(result.getError()).isNull();
    }

    @Test
    void builder_withError_setsErrorField() {
        SpelEvaluationResult result = SpelEvaluationResult.builder()
                .result(null)
                .type(null)
                .error("ELException: unknown token")
                .build();

        assertThat(result.getError()).isEqualTo("ELException: unknown token");
        assertThat(result.getResult()).isNull();
    }

    @Test
    void setters_workCorrectly() {
        SpelEvaluationResult result = new SpelEvaluationResult();
        result.setResult(true);
        result.setType("Boolean");
        result.setError(null);

        assertThat(result.getResult()).isEqualTo(true);
        assertThat(result.getType()).isEqualTo("Boolean");
    }

    @Test
    void equalsAndHashCode_symmetry() {
        SpelEvaluationResult a = SpelEvaluationResult.builder().result("x").type("String").build();
        SpelEvaluationResult b = SpelEvaluationResult.builder().result("x").type("String").build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void toString_containsFieldValues() {
        SpelEvaluationResult result = SpelEvaluationResult.builder()
                .result("ok")
                .type("String")
                .build();

        assertThat(result.toString()).contains("SpelEvaluationResult");
    }

    @Test
    void resultCanHoldNullValue() {
        SpelEvaluationResult result = SpelEvaluationResult.builder()
                .result(null)
                .type("Void")
                .build();

        assertThat(result.getResult()).isNull();
        assertThat(result.getType()).isEqualTo("Void");
    }
}
