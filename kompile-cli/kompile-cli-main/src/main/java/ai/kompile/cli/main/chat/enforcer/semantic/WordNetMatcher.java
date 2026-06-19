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

import com.fasterxml.jackson.databind.JsonNode;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * WordNet-based semantic matcher. Expands banned phrases using synonym sets
 * to catch reworded equivalents without needing an LLM at runtime.
 *
 * <p>Uses a bundled synonym dictionary (JSON) that maps words to their
 * WordNet synsets. At config time, expands a phrase like "pre-existing" into
 * all synonym combinations: "previously existing", "prior condition",
 * "already present issue", etc.</p>
 *
 * <p>Supports two dictionary sources:
 * <ul>
 *   <li>Bundled resource: {@code wordnet-synonyms.json} in classpath</li>
 *   <li>External file: user-provided synonym dictionary at a custom path</li>
 * </ul></p>
 *
 * <p>The dictionary format is a JSON object mapping words to arrays of synonyms:
 * <pre>
 * {
 *   "existing": ["present", "current", "extant", "known"],
 *   "pre": ["prior", "previous", "earlier", "preceding", "before"],
 *   "condition": ["issue", "problem", "defect", "state", "situation"],
 *   ...
 * }
 * </pre></p>
 */
public class WordNetMatcher implements SemanticMatcher {

    private final Map<String, Set<String>> synonymMap;
    private final boolean available;

    public WordNetMatcher() {
        this(loadBundledDictionary());
    }

    public WordNetMatcher(Path dictionaryPath) {
        this(loadDictionary(dictionaryPath));
    }

    public WordNetMatcher(Map<String, Set<String>> synonymMap) {
        this.synonymMap = synonymMap != null ? synonymMap : Map.of();
        this.available = !this.synonymMap.isEmpty();
    }

    @Override
    public List<String> expand(String phrase) {
        if (phrase == null || phrase.isBlank()) return List.of();

        Set<String> results = new LinkedHashSet<>();
        results.add(phrase.toLowerCase());

        // Split into component words
        String normalized = phrase.toLowerCase().replaceAll("[\\-_]", " ").trim();
        String[] words = normalized.split("\\s+");

        if (words.length == 1) {
            // Single word: just add all synonyms
            Set<String> synonyms = synonymMap.getOrDefault(words[0], Set.of());
            results.addAll(synonyms);
        } else {
            // Multi-word phrase: expand each word and generate combinations
            List<Set<String>> wordExpansions = new ArrayList<>();
            for (String word : words) {
                Set<String> expansion = new LinkedHashSet<>();
                expansion.add(word);
                Set<String> synonyms = synonymMap.getOrDefault(word, Set.of());
                expansion.addAll(synonyms);
                wordExpansions.add(expansion);
            }

            // Generate combinations (limit to avoid explosion)
            List<String> combinations = generateCombinations(wordExpansions, 50);
            results.addAll(combinations);
        }

        // Also add common reformulations
        addStructuralVariants(phrase, results);

        return new ArrayList<>(results);
    }

    @Override
    public SemanticMatch matches(String text, String concept) {
        List<String> expanded = expand(concept);
        return matchesWithExpansion(text, concept, expanded);
    }

    @Override
    public SemanticMatch matchesWithExpansion(String text, String concept, List<String> expandedVariants) {
        if (text == null || text.isBlank()) return null;
        String lower = text.toLowerCase();

        for (String variant : expandedVariants) {
            // Use word-boundary-aware matching for short variants to avoid false positives
            if (variant.length() <= 4) {
                Pattern p = Pattern.compile("\\b" + Pattern.quote(variant) + "\\b",
                        Pattern.CASE_INSENSITIVE);
                if (p.matcher(text).find()) {
                    return new SemanticMatch(concept, variant, 1.0, matcherType());
                }
            } else if (lower.contains(variant.toLowerCase())) {
                return new SemanticMatch(concept, variant, 1.0, matcherType());
            }
        }
        return null;
    }

    @Override
    public String matcherType() {
        return "wordnet";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    public int dictionarySize() {
        return synonymMap.size();
    }

    // ── Expansion helpers ──────────────────────────────────────────────────

    private List<String> generateCombinations(List<Set<String>> wordExpansions, int maxResults) {
        List<String> results = new ArrayList<>();
        generateCombinationsRecursive(wordExpansions, 0, new String[wordExpansions.size()], results, maxResults);
        return results;
    }

    private void generateCombinationsRecursive(List<Set<String>> expansions, int depth,
                                               String[] current, List<String> results, int maxResults) {
        if (results.size() >= maxResults) return;
        if (depth == expansions.size()) {
            results.add(String.join(" ", current));
            return;
        }
        for (String word : expansions.get(depth)) {
            if (results.size() >= maxResults) return;
            current[depth] = word;
            generateCombinationsRecursive(expansions, depth + 1, current, results, maxResults);
        }
    }

    private void addStructuralVariants(String phrase, Set<String> results) {
        String lower = phrase.toLowerCase().replaceAll("[\\-_]", " ").trim();

        // "pre existing" → "existing before", "existed before", "existed prior"
        if (lower.contains("pre ") || lower.contains("pre-")) {
            String base = lower.replaceAll("pre[\\s\\-]?", "").trim();
            Set<String> baseSynonyms = synonymMap.getOrDefault(base, Set.of());
            Set<String> allBases = new LinkedHashSet<>();
            allBases.add(base);
            allBases.addAll(baseSynonyms);

            for (String b : allBases) {
                results.add(b + " before");
                results.add(b + " prior");
                results.add(b + " previously");
                results.add("prior " + b);
                results.add("previous " + b);
                results.add("previously " + b);
                results.add("already " + b);
            }
        }

        // Add -ing/-ed variants
        if (lower.endsWith("ing")) {
            String stem = lower.substring(0, lower.length() - 3);
            results.add(stem + "ed");
            // "existing" → "existed"
            Set<String> stemSynonyms = synonymMap.getOrDefault(stem + "ing", Set.of());
            results.addAll(stemSynonyms);
        }
    }

    // ── Dictionary loading ─────────────────────────────────────────────────

    private static Map<String, Set<String>> loadBundledDictionary() {
        try (InputStream is = WordNetMatcher.class.getResourceAsStream("/ai/kompile/enforcer/wordnet-synonyms.json")) {
            if (is == null) {
                // Try fallback: kompile home directory
                Path homePath = Path.of(System.getProperty("user.home"), ".kompile", "wordnet-synonyms.json");
                if (Files.exists(homePath)) {
                    return loadDictionary(homePath);
                }
                return getBuiltinSynonyms();
            }
            return parseDictionary(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return getBuiltinSynonyms();
        }
    }

    private static Map<String, Set<String>> loadDictionary(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return parseDictionary(content);
        } catch (IOException e) {
            return getBuiltinSynonyms();
        }
    }

    private static Map<String, Set<String>> parseDictionary(String json) {
        try {
            ObjectMapper mapper = JsonUtils.standardMapper();
            JsonNode root = mapper.readTree(json);
            Map<String, Set<String>> result = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                Set<String> synonyms = new LinkedHashSet<>();
                if (entry.getValue().isArray()) {
                    for (JsonNode syn : entry.getValue()) {
                        synonyms.add(syn.asText().toLowerCase());
                    }
                }
                result.put(entry.getKey().toLowerCase(), synonyms);
            }
            return result;
        } catch (Exception e) {
            return getBuiltinSynonyms();
        }
    }

    /**
     * Built-in minimal synonym set for common enforcer use cases.
     * Covers the most frequent deflection/responsibility-avoidance patterns.
     */
    private static Map<String, Set<String>> getBuiltinSynonyms() {
        Map<String, Set<String>> map = new HashMap<>();

        // Existence/presence
        map.put("existing", Set.of("present", "current", "extant", "known", "established", "in place"));
        map.put("pre", Set.of("prior", "previous", "earlier", "preceding", "before", "already"));
        map.put("condition", Set.of("issue", "problem", "defect", "state", "situation", "bug", "flaw"));
        map.put("broken", Set.of("defective", "faulty", "damaged", "malfunctioning", "non-functional", "bugged"));
        map.put("fix", Set.of("repair", "resolve", "correct", "patch", "remedy", "address", "mend"));

        // Responsibility deflection
        map.put("environmental", Set.of("infrastructure", "platform", "system-level", "external",
                "deployment", "configuration", "setup-related"));
        map.put("scope", Set.of("responsibility", "domain", "purview", "jurisdiction", "remit"));
        map.put("outside", Set.of("beyond", "not within", "excluded from", "separate from"));
        map.put("cannot", Set.of("unable to", "not possible to", "impossible to", "incapable of"));
        map.put("already", Set.of("previously", "prior", "before", "earlier", "formerly"));

        // Quality/state
        map.put("legacy", Set.of("inherited", "historical", "old", "pre-existing", "leftover", "carried over"));
        map.put("technical debt", Set.of("inherited code", "legacy code", "accumulated issues", "deferred maintenance"));
        map.put("flaky", Set.of("unreliable", "intermittent", "unstable", "non-deterministic", "fragile"));

        return map;
    }
}
