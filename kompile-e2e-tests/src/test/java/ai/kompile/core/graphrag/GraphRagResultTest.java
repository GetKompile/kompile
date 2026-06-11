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

package ai.kompile.core.graphrag;

import ai.kompile.core.graphrag.query.GraphRagResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GraphRagResult model.
 */
@DisplayName("GraphRagResult Tests")
public class GraphRagResultTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("All-args constructor should set all fields")
        void allArgsConstructor_shouldSetAllFields() {
            GraphRagResult result = GraphRagResult.builder()
                    .answer("The answer is 42")
                    .formattedContext("Context: Entity1, Entity2")
                    .build();

            assertEquals("The answer is 42", result.getAnswer());
            assertEquals("Context: Entity1, Entity2", result.getFormattedContext());
        }

        @Test
        @DisplayName("No-args constructor should create empty result")
        void noArgsConstructor_shouldCreateEmptyResult() {
            GraphRagResult result = new GraphRagResult();

            assertNull(result.getAnswer());
            assertNull(result.getFormattedContext());
        }

        @Test
        @DisplayName("Constructor should allow null values")
        void constructor_shouldAllowNullValues() {
            GraphRagResult result = GraphRagResult.builder().answer(null).formattedContext(null).build();

            assertNull(result.getAnswer());
            assertNull(result.getFormattedContext());
        }

        @Test
        @DisplayName("Constructor should allow empty strings")
        void constructor_shouldAllowEmptyStrings() {
            GraphRagResult result = GraphRagResult.builder().answer("").formattedContext("").build();

            assertEquals("", result.getAnswer());
            assertEquals("", result.getFormattedContext());
        }
    }

    @Nested
    @DisplayName("Setter Tests")
    class SetterTests {

        @Test
        @DisplayName("setAnswer should update answer field")
        void setAnswer_shouldUpdateAnswerField() {
            GraphRagResult result = new GraphRagResult();
            result.setAnswer("New answer");

            assertEquals("New answer", result.getAnswer());
        }

        @Test
        @DisplayName("setFormattedContext should update context field")
        void setFormattedContext_shouldUpdateContextField() {
            GraphRagResult result = new GraphRagResult();
            result.setFormattedContext("New context");

            assertEquals("New context", result.getFormattedContext());
        }
    }

    @Nested
    @DisplayName("Equality Tests")
    class EqualityTests {

        @Test
        @DisplayName("Results with same values should be equal")
        void resultsWithSameValues_shouldBeEqual() {
            GraphRagResult result1 = GraphRagResult.builder().answer("Answer").formattedContext("Context").build();
            GraphRagResult result2 = GraphRagResult.builder().answer("Answer").formattedContext("Context").build();

            assertEquals(result1, result2);
            assertEquals(result1.hashCode(), result2.hashCode());
        }

        @Test
        @DisplayName("Results with different answers should not be equal")
        void resultsWithDifferentAnswers_shouldNotBeEqual() {
            GraphRagResult result1 = GraphRagResult.builder().answer("Answer 1").formattedContext("Context").build();
            GraphRagResult result2 = GraphRagResult.builder().answer("Answer 2").formattedContext("Context").build();

            assertNotEquals(result1, result2);
        }

        @Test
        @DisplayName("Results with different contexts should not be equal")
        void resultsWithDifferentContexts_shouldNotBeEqual() {
            GraphRagResult result1 = GraphRagResult.builder().answer("Answer").formattedContext("Context 1").build();
            GraphRagResult result2 = GraphRagResult.builder().answer("Answer").formattedContext("Context 2").build();

            assertNotEquals(result1, result2);
        }

        @Test
        @DisplayName("Result should not equal null")
        void result_shouldNotEqualNull() {
            GraphRagResult result = GraphRagResult.builder().answer("Answer").formattedContext("Context").build();

            assertNotEquals(null, result);
        }

        @Test
        @DisplayName("Result should not equal different type")
        void result_shouldNotEqualDifferentType() {
            GraphRagResult result = GraphRagResult.builder().answer("Answer").formattedContext("Context").build();

            assertNotEquals("Answer", result);
        }
    }

    @Nested
    @DisplayName("ToString Tests")
    class ToStringTests {

        @Test
        @DisplayName("toString should contain answer")
        void toString_shouldContainAnswer() {
            GraphRagResult result = GraphRagResult.builder().answer("The answer is 42").formattedContext("Context").build();

            String str = result.toString();
            assertTrue(str.contains("The answer is 42"));
        }

        @Test
        @DisplayName("toString should contain formatted context")
        void toString_shouldContainFormattedContext() {
            GraphRagResult result = GraphRagResult.builder().answer("Answer").formattedContext("Entity: TechCorp").build();

            String str = result.toString();
            assertTrue(str.contains("Entity: TechCorp"));
        }

        @Test
        @DisplayName("toString should handle null values")
        void toString_shouldHandleNullValues() {
            GraphRagResult result = new GraphRagResult();

            // Should not throw NPE
            assertDoesNotThrow(() -> result.toString());
        }
    }

    @Nested
    @DisplayName("Content Validation Tests")
    class ContentValidationTests {

        @Test
        @DisplayName("Long answer should be stored correctly")
        void longAnswer_shouldBeStoredCorrectly() {
            StringBuilder longAnswer = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                longAnswer.append("This is sentence ").append(i).append(". ");
            }

            GraphRagResult result = GraphRagResult.builder().answer(longAnswer.toString()).formattedContext("Context").build();

            assertEquals(longAnswer.toString(), result.getAnswer());
        }

        @Test
        @DisplayName("Special characters should be handled")
        void specialCharacters_shouldBeHandled() {
            String answerWithSpecialChars = "Answer with <html>, \"quotes\", and \n newlines";
            String contextWithSpecialChars = "Context: Entity<1> -> Entity\"2\"";

            GraphRagResult result = GraphRagResult.builder()
                    .answer(answerWithSpecialChars)
                    .formattedContext(contextWithSpecialChars)
                    .build();

            assertEquals(answerWithSpecialChars, result.getAnswer());
            assertEquals(contextWithSpecialChars, result.getFormattedContext());
        }

        @Test
        @DisplayName("Unicode content should be handled")
        void unicodeContent_shouldBeHandled() {
            String unicodeAnswer = "La respuesta es: 日本語テスト 中文测试 한국어테스트";
            String unicodeContext = "实体: 公司A -> 公司B";

            GraphRagResult result = GraphRagResult.builder()
                    .answer(unicodeAnswer).formattedContext(unicodeContext).build();

            assertEquals(unicodeAnswer, result.getAnswer());
            assertEquals(unicodeContext, result.getFormattedContext());
        }
    }
}
