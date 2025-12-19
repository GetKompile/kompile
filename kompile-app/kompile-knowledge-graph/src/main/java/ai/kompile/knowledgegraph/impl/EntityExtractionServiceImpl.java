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
package ai.kompile.knowledgegraph.impl;

import ai.kompile.knowledgegraph.service.EntityExtractionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Implementation of EntityExtractionService using pattern-based extraction.
 * This implementation uses regex patterns and heuristics to identify entities.
 * For production, this could be enhanced with ML-based NER models.
 */
@Service
@Slf4j
public class EntityExtractionServiceImpl implements EntityExtractionService {

    // Patterns for different entity types
    private static final Pattern CAPITALIZED_PHRASE = Pattern.compile(
        "\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)+)\\b"
    );

    private static final Pattern SINGLE_CAPITALIZED = Pattern.compile(
        "\\b([A-Z][a-z]{2,})\\b"
    );

    private static final Pattern ORGANIZATION_INDICATORS = Pattern.compile(
        "\\b([A-Z][a-zA-Z]*(?:\\s+[A-Z][a-zA-Z]*)*\\s+(?:Inc|Corp|LLC|Ltd|Company|Corporation|Association|Institute|University|Foundation|Organization|Group|Team))\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern LOCATION_INDICATORS = Pattern.compile(
        "\\b(?:in|at|from|to|near)\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*)\\b"
    );

    private static final Pattern DATE_PATTERN = Pattern.compile(
        "\\b(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|" +
        "(?:January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{1,2},?\\s+\\d{4}|" +
        "\\d{4}[/-]\\d{2}[/-]\\d{2})\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TECHNICAL_TERM = Pattern.compile(
        "\\b([A-Z]{2,}[a-z]*|[a-z]+[A-Z][a-zA-Z]*|" +
        "(?:API|SDK|REST|HTTP|JSON|XML|SQL|HTML|CSS|JavaScript|Python|Java|Kubernetes|Docker|AWS|Azure|GCP))\\b"
    );

    private static final Pattern QUOTED_TERM = Pattern.compile(
        "\"([^\"]{2,50})\"|'([^']{2,50})'"
    );

    // Common words to exclude from entity extraction
    private static final Set<String> STOP_WORDS = Set.of(
        "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
        "of", "with", "by", "from", "as", "is", "was", "are", "were", "been",
        "be", "have", "has", "had", "do", "does", "did", "will", "would",
        "could", "should", "may", "might", "must", "shall", "can", "need",
        "this", "that", "these", "those", "it", "its", "they", "them", "their",
        "we", "us", "our", "you", "your", "he", "she", "him", "her", "his",
        "i", "me", "my", "if", "then", "else", "when", "where", "why", "how",
        "all", "each", "every", "both", "few", "more", "most", "other", "some",
        "such", "no", "nor", "not", "only", "own", "same", "so", "than", "too",
        "very", "just", "also", "now", "here", "there", "today", "tomorrow",
        "yesterday", "however", "therefore", "thus", "hence", "although",
        "because", "since", "while", "during", "before", "after", "above",
        "below", "between", "under", "over", "through", "into", "about"
    );

    // Common first names (sample)
    private static final Set<String> COMMON_FIRST_NAMES = Set.of(
        "John", "James", "Michael", "Robert", "David", "William", "Richard",
        "Joseph", "Thomas", "Charles", "Mary", "Patricia", "Jennifer", "Linda",
        "Elizabeth", "Barbara", "Susan", "Jessica", "Sarah", "Karen", "Mark",
        "Paul", "Daniel", "Steven", "Andrew", "Peter", "Alex", "Chris", "Sam"
    );

    @Override
    public List<ExtractedEntity> extractEntities(String text) {
        return extractEntities(text, Arrays.asList(EntityType.values()));
    }

    @Override
    public List<ExtractedEntity> extractEntities(String text, List<EntityType> types) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        List<ExtractedEntity> entities = new ArrayList<>();

        for (EntityType type : types) {
            switch (type) {
                case PERSON -> entities.addAll(extractPersons(text));
                case ORGANIZATION -> entities.addAll(extractOrganizations(text));
                case LOCATION -> entities.addAll(extractLocations(text));
                case DATE -> entities.addAll(extractDates(text));
                case TECHNICAL_TERM -> entities.addAll(extractTechnicalTerms(text));
                case CONCEPT -> entities.addAll(extractConcepts(text));
                default -> {}
            }
        }

        // Deduplicate and merge overlapping entities
        return deduplicateEntities(entities);
    }

    @Override
    public List<ExtractedEntity> extractEntities(String text, double minConfidence) {
        return extractEntities(text).stream()
            .filter(e -> e.confidence() >= minConfidence)
            .collect(Collectors.toList());
    }

    @Override
    public String normalizeEntityName(String entityName) {
        if (entityName == null) return "";
        return entityName.trim()
            .toLowerCase()
            .replaceAll("[^a-z0-9\\s]", "")
            .replaceAll("\\s+", " ");
    }

    @Override
    public boolean isSameEntity(String entity1, String entity2) {
        String norm1 = normalizeEntityName(entity1);
        String norm2 = normalizeEntityName(entity2);

        // Exact match
        if (norm1.equals(norm2)) return true;

        // One contains the other
        if (norm1.contains(norm2) || norm2.contains(norm1)) return true;

        // Levenshtein distance for fuzzy matching
        int distance = levenshteinDistance(norm1, norm2);
        int maxLen = Math.max(norm1.length(), norm2.length());
        double similarity = 1.0 - (double) distance / maxLen;

        return similarity >= 0.85;
    }

    @Override
    public List<EntityType> getSupportedTypes() {
        return Arrays.asList(EntityType.values());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE EXTRACTION METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private List<ExtractedEntity> extractPersons(String text) {
        List<ExtractedEntity> persons = new ArrayList<>();

        // Look for capitalized phrases that might be names
        Matcher matcher = CAPITALIZED_PHRASE.matcher(text);
        while (matcher.find()) {
            String phrase = matcher.group(1);
            String[] words = phrase.split("\\s+");

            // Check if first word looks like a first name
            if (words.length >= 2 && words.length <= 4) {
                boolean looksLikeName = COMMON_FIRST_NAMES.contains(words[0]) ||
                    (words[0].length() >= 3 && words[0].length() <= 15);

                if (looksLikeName && !isLikelyOrganization(phrase)) {
                    double confidence = COMMON_FIRST_NAMES.contains(words[0]) ? 0.85 : 0.6;
                    persons.add(new ExtractedEntity(
                        phrase,
                        EntityType.PERSON.name(),
                        matcher.start(),
                        matcher.end(),
                        confidence,
                        Map.of()
                    ));
                }
            }
        }

        return persons;
    }

    private List<ExtractedEntity> extractOrganizations(String text) {
        List<ExtractedEntity> orgs = new ArrayList<>();

        Matcher matcher = ORGANIZATION_INDICATORS.matcher(text);
        while (matcher.find()) {
            orgs.add(new ExtractedEntity(
                matcher.group(1),
                EntityType.ORGANIZATION.name(),
                matcher.start(),
                matcher.end(),
                0.9,
                Map.of()
            ));
        }

        // Also look for all-caps abbreviations (likely org names)
        Pattern allCaps = Pattern.compile("\\b([A-Z]{2,6})\\b");
        Matcher capsMatcher = allCaps.matcher(text);
        while (capsMatcher.find()) {
            String abbrev = capsMatcher.group(1);
            if (!TECHNICAL_TERM.matcher(abbrev).matches()) {
                orgs.add(new ExtractedEntity(
                    abbrev,
                    EntityType.ORGANIZATION.name(),
                    capsMatcher.start(),
                    capsMatcher.end(),
                    0.5,
                    Map.of("type", "abbreviation")
                ));
            }
        }

        return orgs;
    }

    private List<ExtractedEntity> extractLocations(String text) {
        List<ExtractedEntity> locations = new ArrayList<>();

        Matcher matcher = LOCATION_INDICATORS.matcher(text);
        while (matcher.find()) {
            locations.add(new ExtractedEntity(
                matcher.group(1),
                EntityType.LOCATION.name(),
                matcher.start(1),
                matcher.end(1),
                0.75,
                Map.of()
            ));
        }

        return locations;
    }

    private List<ExtractedEntity> extractDates(String text) {
        List<ExtractedEntity> dates = new ArrayList<>();

        Matcher matcher = DATE_PATTERN.matcher(text);
        while (matcher.find()) {
            dates.add(new ExtractedEntity(
                matcher.group(1),
                EntityType.DATE.name(),
                matcher.start(),
                matcher.end(),
                0.95,
                Map.of()
            ));
        }

        return dates;
    }

    private List<ExtractedEntity> extractTechnicalTerms(String text) {
        List<ExtractedEntity> terms = new ArrayList<>();

        Matcher matcher = TECHNICAL_TERM.matcher(text);
        while (matcher.find()) {
            String term = matcher.group(1);
            if (term.length() >= 2 && !STOP_WORDS.contains(term.toLowerCase())) {
                terms.add(new ExtractedEntity(
                    term,
                    EntityType.TECHNICAL_TERM.name(),
                    matcher.start(),
                    matcher.end(),
                    0.8,
                    Map.of()
                ));
            }
        }

        return terms;
    }

    private List<ExtractedEntity> extractConcepts(String text) {
        List<ExtractedEntity> concepts = new ArrayList<>();

        // Extract quoted terms as concepts
        Matcher quotedMatcher = QUOTED_TERM.matcher(text);
        while (quotedMatcher.find()) {
            String concept = quotedMatcher.group(1) != null ?
                quotedMatcher.group(1) : quotedMatcher.group(2);
            if (concept != null && concept.length() >= 3) {
                concepts.add(new ExtractedEntity(
                    concept,
                    EntityType.CONCEPT.name(),
                    quotedMatcher.start(),
                    quotedMatcher.end(),
                    0.7,
                    Map.of()
                ));
            }
        }

        // Extract noun phrases (simplified)
        Matcher nounPhrase = Pattern.compile(
            "\\b((?:the\\s+)?[a-z]+(?:\\s+[a-z]+){1,3}(?:\\s+(?:system|process|method|approach|technique|algorithm|framework|model|architecture|pattern|principle|concept)))\\b",
            Pattern.CASE_INSENSITIVE
        ).matcher(text);

        while (nounPhrase.find()) {
            String phrase = nounPhrase.group(1);
            if (!phrase.toLowerCase().startsWith("the ")) {
                phrase = phrase.replaceFirst("^(?i)the\\s+", "");
            }
            concepts.add(new ExtractedEntity(
                phrase,
                EntityType.CONCEPT.name(),
                nounPhrase.start(),
                nounPhrase.end(),
                0.65,
                Map.of()
            ));
        }

        return concepts;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean isLikelyOrganization(String phrase) {
        String lower = phrase.toLowerCase();
        return lower.contains("inc") || lower.contains("corp") ||
               lower.contains("llc") || lower.contains("ltd") ||
               lower.contains("company") || lower.contains("university") ||
               lower.contains("institute") || lower.contains("foundation");
    }

    private List<ExtractedEntity> deduplicateEntities(List<ExtractedEntity> entities) {
        // Sort by confidence descending
        entities.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));

        List<ExtractedEntity> deduplicated = new ArrayList<>();
        Set<String> seenNormalized = new HashSet<>();

        for (ExtractedEntity entity : entities) {
            String normalized = normalizeEntityName(entity.name());
            boolean isDuplicate = false;

            for (String seen : seenNormalized) {
                if (isSameEntity(normalized, seen)) {
                    isDuplicate = true;
                    break;
                }
            }

            if (!isDuplicate && !normalized.isBlank() && normalized.length() >= 2) {
                deduplicated.add(entity);
                seenNormalized.add(normalized);
            }
        }

        return deduplicated;
    }

    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) {
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                } else if (j == 0) {
                    dp[i][j] = i;
                } else {
                    int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                    dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                    );
                }
            }
        }

        return dp[s1.length()][s2.length()];
    }
}
