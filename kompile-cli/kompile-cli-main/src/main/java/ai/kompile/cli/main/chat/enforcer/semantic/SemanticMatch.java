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

package ai.kompile.cli.main.chat.enforcer.semantic;

/**
 * Result of a semantic match operation. Contains the original banned concept,
 * what was actually found in the text, and the confidence/similarity score.
 */
public record SemanticMatch(
        String concept,
        String matchedPhrase,
        double similarity,
        String matcherType
) {

    /**
     * Whether this is an exact match (similarity == 1.0).
     */
    public boolean isExact() {
        return similarity >= 1.0;
    }

    /**
     * Human-readable description of the match.
     */
    public String describe() {
        if (isExact()) {
            return "exact match: \"" + matchedPhrase + "\" (synonym of \"" + concept + "\")";
        }
        return String.format("semantic match: \"%.40s\" ~ \"%s\" (%.0f%% via %s)",
                matchedPhrase, concept, similarity * 100, matcherType);
    }
}
