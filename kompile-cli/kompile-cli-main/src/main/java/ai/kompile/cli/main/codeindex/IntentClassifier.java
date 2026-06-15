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

package ai.kompile.cli.main.codeindex;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Classifies search queries into intents and returns tuned weight profiles
 * for the relevance ranker. Each intent adjusts how heavily different
 * signal types (exact match, symbol match, path match, graph boost, etc.)
 * contribute to the final score.
 *
 * <p>Inspired by sigmap's intent-aware ranking — different query types
 * benefit from different scoring strategies.</p>
 */
public class IntentClassifier {

    /**
     * The detected intent of a query.
     */
    public enum Intent {
        SEARCH,     // General code search (default)
        DEBUG,      // Finding bugs, errors, exceptions
        EXPLAIN,    // Understanding code behavior
        REFACTOR,   // Code restructuring
        REVIEW,     // Code review context
        TEST,       // Finding/writing tests
        INTEGRATE,  // Understanding connections between components
        NAVIGATE    // Finding specific files or symbols by path
    }

    /**
     * Weight profile for a given intent. Each weight adjusts how a specific
     * signal contributes to the relevance score.
     */
    public record WeightProfile(
            double exactToken,
            double symbolMatch,
            double prefixMatch,
            double pathMatch,
            double recencyBoost,
            double graphBoost
    ) {
        public static final WeightProfile DEFAULT = new WeightProfile(1.0, 0.5, 0.3, 0.8, 1.5, 0.4);
    }

    // Intent keyword patterns (lowercase)
    private static final Map<Intent, List<Pattern>> INTENT_PATTERNS = new EnumMap<>(Intent.class);

    static {
        INTENT_PATTERNS.put(Intent.DEBUG, List.of(
                Pattern.compile("\\b(bug|error|exception|crash|fail|fix|issue|problem|wrong|broken|null\\s*pointer|stack\\s*trace|debug|trace|log)\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\b(why\\s+(does|is|isn't|doesn't|did)|what\\s+causes|how\\s+to\\s+fix)\\b", Pattern.CASE_INSENSITIVE)
        ));

        INTENT_PATTERNS.put(Intent.EXPLAIN, List.of(
                Pattern.compile("\\b(explain|how\\s+does|what\\s+does|what\\s+is|understand|describe|walk\\s+through|overview|purpose|meaning)\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\b(how\\s+it\\s+works|what\\s+happens|flow|logic|algorithm)\\b", Pattern.CASE_INSENSITIVE)
        ));

        INTENT_PATTERNS.put(Intent.REFACTOR, List.of(
                Pattern.compile("\\b(refactor|restructure|reorganize|simplify|extract|inline|rename|move|split|merge|clean\\s*up|dedup|consolidate)\\b", Pattern.CASE_INSENSITIVE)
        ));

        INTENT_PATTERNS.put(Intent.REVIEW, List.of(
                Pattern.compile("\\b(review|audit|check|inspect|verify|validate|quality|safe|secure|vulnerability|smell|anti.?pattern)\\b", Pattern.CASE_INSENSITIVE)
        ));

        INTENT_PATTERNS.put(Intent.TEST, List.of(
                Pattern.compile("\\b(test|spec|assert|mock|stub|fixture|coverage|unit\\s*test|integration\\s*test|e2e)\\b", Pattern.CASE_INSENSITIVE)
        ));

        INTENT_PATTERNS.put(Intent.INTEGRATE, List.of(
                Pattern.compile("\\b(integrat\\w*|connect\\w*|depend\\w*|consum\\w*|inject\\w*|wir(?:e|ing|ed)\\b|coupl\\w*|interact\\w*|communicat\\w*)\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\b(how\\s+.*\\s+(connect|interact|communicate|depend)|what\\s+(uses|calls|imports|depends))\\b", Pattern.CASE_INSENSITIVE)
        ));

        INTENT_PATTERNS.put(Intent.NAVIGATE, List.of(
                Pattern.compile("\\b(where\\s+is|find|locate|open|show\\s+me|go\\s+to|path\\s+to|file\\s+for|which\\s+file)\\b", Pattern.CASE_INSENSITIVE)
        ));
    }

    // Weight profiles per intent — tuned for each query type
    private static final Map<Intent, WeightProfile> WEIGHT_PROFILES = new EnumMap<>(Intent.class);

    static {
        //                                                exact  symbol prefix path   recency graph
        WEIGHT_PROFILES.put(Intent.SEARCH,    new WeightProfile(1.0,   0.5,   0.3,   0.8,   1.5,   0.4));
        WEIGHT_PROFILES.put(Intent.DEBUG,     new WeightProfile(1.2,   0.5,   0.3,   0.6,   1.5,   0.4));
        WEIGHT_PROFILES.put(Intent.EXPLAIN,   new WeightProfile(1.0,   0.8,   0.3,   0.9,   1.5,   0.4));
        WEIGHT_PROFILES.put(Intent.REFACTOR,  new WeightProfile(0.8,   0.9,   0.3,   0.8,   1.5,   0.4));
        WEIGHT_PROFILES.put(Intent.REVIEW,    new WeightProfile(0.9,   0.5,   0.3,   1.0,   1.5,   0.4));
        WEIGHT_PROFILES.put(Intent.TEST,      new WeightProfile(0.7,   0.4,   0.3,   0.8,   1.5,   0.4));
        WEIGHT_PROFILES.put(Intent.INTEGRATE, new WeightProfile(1.0,   0.5,   0.3,   1.1,   1.5,   0.7));
        WEIGHT_PROFILES.put(Intent.NAVIGATE,  new WeightProfile(0.9,   0.5,   0.3,   1.2,   1.5,   0.4));
    }

    /**
     * Classify a query into an intent based on keyword patterns.
     *
     * @param query the search query
     * @return the detected intent (defaults to SEARCH if no patterns match)
     */
    public static Intent classify(String query) {
        if (query == null || query.isEmpty()) return Intent.SEARCH;

        // Score each intent by pattern match count
        Intent best = Intent.SEARCH;
        int bestScore = 0;

        for (Map.Entry<Intent, List<Pattern>> entry : INTENT_PATTERNS.entrySet()) {
            int score = 0;
            for (Pattern pattern : entry.getValue()) {
                if (pattern.matcher(query).find()) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = entry.getKey();
            }
        }

        return best;
    }

    /**
     * Get the weight profile for an intent.
     */
    public static WeightProfile getWeights(Intent intent) {
        return WEIGHT_PROFILES.getOrDefault(intent, WeightProfile.DEFAULT);
    }

    /**
     * Classify a query and return its weight profile in one call.
     */
    public static WeightProfile classifyAndGetWeights(String query) {
        return getWeights(classify(query));
    }

    /**
     * Get a human-readable description of the detected intent.
     */
    public static String describeIntent(Intent intent) {
        return switch (intent) {
            case SEARCH -> "General search";
            case DEBUG -> "Debugging/error investigation";
            case EXPLAIN -> "Code understanding/explanation";
            case REFACTOR -> "Code restructuring";
            case REVIEW -> "Code review/audit";
            case TEST -> "Test-related";
            case INTEGRATE -> "Integration/dependency analysis";
            case NAVIGATE -> "File/symbol navigation";
        };
    }
}
