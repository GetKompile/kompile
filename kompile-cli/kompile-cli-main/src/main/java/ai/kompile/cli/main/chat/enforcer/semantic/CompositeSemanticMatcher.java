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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Composite semantic matcher that chains multiple matchers (WordNet + Embedding).
 * Returns the first match found across all matchers, preferring exact/synonym matches
 * over fuzzy embedding matches.
 *
 * <p>Execution order:
 * <ol>
 *   <li>WordNet synonym expansion (fast, deterministic)</li>
 *   <li>Embedding similarity (slower, catches novel rephrasings)</li>
 * </ol>
 *
 * <p>If WordNet finds an exact synonym match, the embedding check is skipped.
 * This avoids unnecessary API calls for obvious violations.</p>
 */
public class CompositeSemanticMatcher implements SemanticMatcher {

    private final List<SemanticMatcher> matchers;

    public CompositeSemanticMatcher(List<SemanticMatcher> matchers) {
        this.matchers = matchers != null ? List.copyOf(matchers) : List.of();
    }

    /**
     * Create a composite from available matchers based on the semantic mode configuration.
     *
     * @param mode           "none", "wordnet", "embedding", or "both"
     * @param embeddingUrl   URL for embedding endpoint (required for "embedding" and "both" modes)
     * @param threshold      similarity threshold for embedding matching
     * @return configured composite matcher
     */
    public static CompositeSemanticMatcher create(String mode, String embeddingUrl, double threshold) {
        if (mode == null || "none".equalsIgnoreCase(mode)) {
            return new CompositeSemanticMatcher(List.of());
        }

        List<SemanticMatcher> matchers = new ArrayList<>();

        if ("wordnet".equalsIgnoreCase(mode) || "both".equalsIgnoreCase(mode)) {
            WordNetMatcher wordnet = new WordNetMatcher();
            if (wordnet.isAvailable()) {
                matchers.add(wordnet);
            }
        }

        if ("embedding".equalsIgnoreCase(mode) || "both".equalsIgnoreCase(mode)) {
            if (embeddingUrl != null && !embeddingUrl.isBlank()) {
                EmbeddingMatcher embedding = new EmbeddingMatcher(embeddingUrl, threshold, 5, 2);
                if (embedding.isAvailable()) {
                    matchers.add(embedding);
                }
            }
        }

        return new CompositeSemanticMatcher(matchers);
    }

    @Override
    public List<String> expand(String phrase) {
        Set<String> allExpanded = new LinkedHashSet<>();
        for (SemanticMatcher matcher : matchers) {
            if (matcher.isAvailable()) {
                allExpanded.addAll(matcher.expand(phrase));
            }
        }
        return allExpanded.isEmpty() ? List.of(phrase) : new ArrayList<>(allExpanded);
    }

    @Override
    public SemanticMatch matches(String text, String concept) {
        for (SemanticMatcher matcher : matchers) {
            if (!matcher.isAvailable()) continue;
            SemanticMatch match = matcher.matches(text, concept);
            if (match != null) return match;
        }
        return null;
    }

    @Override
    public SemanticMatch matchesWithExpansion(String text, String concept, List<String> expandedVariants) {
        for (SemanticMatcher matcher : matchers) {
            if (!matcher.isAvailable()) continue;
            SemanticMatch match = matcher.matchesWithExpansion(text, concept, expandedVariants);
            if (match != null) return match;
        }
        return null;
    }

    @Override
    public String matcherType() {
        if (matchers.isEmpty()) return "none";
        List<String> types = new ArrayList<>();
        for (SemanticMatcher m : matchers) {
            if (m.isAvailable()) types.add(m.matcherType());
        }
        return String.join("+", types);
    }

    @Override
    public boolean isAvailable() {
        return matchers.stream().anyMatch(SemanticMatcher::isAvailable);
    }

    public int matcherCount() {
        return (int) matchers.stream().filter(SemanticMatcher::isAvailable).count();
    }

    public List<SemanticMatcher> getMatchers() {
        return matchers;
    }
}
