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

package ai.kompile.cli.main.chat.harness.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result of evaluating a single {@link Assertion} against agent output.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssertionResult(
        @JsonProperty Assertion.Type type,
        @JsonProperty boolean passed,
        @JsonProperty boolean critical,
        @JsonProperty String description,
        @JsonProperty String detail
) {
    public static AssertionResult pass(Assertion assertion) {
        return new AssertionResult(assertion.getType(), true, assertion.isCritical(),
                assertion.getDescription(), null);
    }

    public static AssertionResult fail(Assertion assertion, String detail) {
        return new AssertionResult(assertion.getType(), false, assertion.isCritical(),
                assertion.getDescription(), detail);
    }
}
