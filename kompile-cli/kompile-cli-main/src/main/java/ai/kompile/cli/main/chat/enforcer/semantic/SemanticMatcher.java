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

import java.util.List;

/**
 * Common interface for detecting semantically equivalent phrases in text.
 * Implementations can use WordNet synonym expansion, embedding similarity,
 * or both to catch reworded variants of banned concepts.
 *
 * <p>Two-phase design:
 * <ol>
 *   <li><b>Expansion phase</b> (config time): {@link #expand(String)} generates
 *       all known equivalent forms of a concept. These can be cached.</li>
 *   <li><b>Matching phase</b> (runtime): {@link #matches(String, String)} checks
 *       if text contains a semantic equivalent of a banned concept.</li>
 * </ol>
 */
public interface SemanticMatcher {

    /**
     * Expand a phrase into all semantically equivalent variants.
     * Called at config/startup time. Results can be cached in enforcer config.
     *
     * @param phrase the banned phrase to expand (e.g., "pre-existing")
     * @return list of equivalent phrases/patterns, including the original
     */
    List<String> expand(String phrase);

    /**
     * Check if text contains a semantic equivalent of the banned concept.
     * Called at runtime on every chunk of agent output.
     *
     * @param text    the text to check (agent output)
     * @param concept the banned concept to look for
     * @return match result with details, or null if no match
     */
    SemanticMatch matches(String text, String concept);

    /**
     * Check if text contains a semantic equivalent, using pre-expanded variants.
     * Faster than {@link #matches(String, String)} since expansion is already done.
     *
     * @param text            the text to check
     * @param concept         the original banned concept
     * @param expandedVariants pre-computed variants from {@link #expand(String)}
     * @return match result with details, or null if no match
     */
    default SemanticMatch matchesWithExpansion(String text, String concept, List<String> expandedVariants) {
        // Default: check each expanded variant as a substring
        String lower = text.toLowerCase();
        for (String variant : expandedVariants) {
            if (lower.contains(variant.toLowerCase())) {
                return new SemanticMatch(concept, variant, 1.0, matcherType());
            }
        }
        return null;
    }

    /**
     * The type of this matcher (for diagnostics and configuration).
     */
    String matcherType();

    /**
     * Whether this matcher is available (dependencies present, models loaded, etc.).
     */
    boolean isAvailable();
}
