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

import ai.kompile.knowledgegraph.service.ConceptExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Implementation of ConceptExtractor using statistical NLP techniques.
 * Extracts keywords, topics, and relationships from text without requiring external LLM calls.
 */
@Service
@Slf4j
public class ConceptExtractorImpl implements ConceptExtractor {

    // Common English stop words to filter out
    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with",
        "by", "from", "as", "is", "was", "are", "were", "been", "be", "have", "has", "had",
        "do", "does", "did", "will", "would", "could", "should", "may", "might", "must",
        "shall", "can", "need", "dare", "ought", "used", "this", "that", "these", "those",
        "i", "you", "he", "she", "it", "we", "they", "what", "which", "who", "whom",
        "when", "where", "why", "how", "all", "each", "every", "both", "few", "more",
        "most", "other", "some", "such", "no", "nor", "not", "only", "own", "same", "so",
        "than", "too", "very", "just", "also", "now", "here", "there", "then", "once",
        "any", "about", "above", "after", "again", "against", "between", "into", "through",
        "during", "before", "out", "over", "under", "further", "if", "because"
    );

    // Pattern for extracting potential concepts (alphanumeric words)
    private static final Pattern WORD_PATTERN = Pattern.compile("\\b[A-Za-z][A-Za-z0-9_-]{2,}\\b");

    // Pattern for extracting capitalized phrases (potential proper nouns)
    private static final Pattern CAPITALIZED_PHRASE = Pattern.compile("\\b[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*\\b");

    // Pattern for technical terms (camelCase, snake_case, etc.)
    private static final Pattern TECHNICAL_TERM = Pattern.compile(
        "\\b(?:[a-z]+(?:[A-Z][a-z]*)+|[a-z]+(?:_[a-z]+)+|[A-Z]+(?:_[A-Z]+)+)\\b"
    );

    @Override
    public ExtractionResult extractConcepts(String text, ExtractionConfig config) {
        if (text == null || text.isBlank()) {
            return new ExtractionResult(List.of(), List.of(), Map.of());
        }

        List<ExtractedConcept> concepts = new ArrayList<>();
        List<ConceptRelationship> relationships = new ArrayList<>();

        // Extract different types of concepts
        if (config.extractKeywords()) {
            concepts.addAll(extractKeywords(text, config));
        }

        if (config.extractTopics()) {
            concepts.addAll(extractTopics(text, config));
        }

        // Extract capitalized phrases (named entities)
        concepts.addAll(extractCapitalizedPhrases(text, config));

        // Extract technical terms
        concepts.addAll(extractTechnicalTerms(text, config));

        // Extract n-grams if enabled
        if (config.useNgrams()) {
            concepts.addAll(extractNgrams(text, config));
        }

        // Deduplicate and merge concepts
        concepts = deduplicateConcepts(concepts);

        // Filter by confidence and limit
        concepts = concepts.stream()
            .filter(c -> c.confidence() >= config.minConfidence())
            .sorted((a, b) -> Double.compare(b.confidence(), a.confidence()))
            .limit(config.maxConcepts())
            .collect(Collectors.toList());

        // Extract relationships between concepts
        if (config.extractRelationships()) {
            relationships = extractRelationships(text, concepts);
        }

        Map<String, Object> metadata = Map.of(
            "totalExtracted", concepts.size(),
            "relationshipsFound", relationships.size(),
            "textLength", text.length()
        );

        return new ExtractionResult(concepts, relationships, metadata);
    }

    @Override
    public Map<String, ExtractionResult> extractConceptsFromPassages(
            Map<String, String> passages,
            ExtractionConfig config) {

        Map<String, ExtractionResult> results = new HashMap<>();

        for (Map.Entry<String, String> entry : passages.entrySet()) {
            results.put(entry.getKey(), extractConcepts(entry.getValue(), config));
        }

        return results;
    }

    @Override
    public List<SharedConcept> findSharedConcepts(Map<String, ExtractionResult> results, int minShared) {
        // Build a map of normalized concept names to their occurrences across passages
        Map<String, List<ConceptOccurrence>> conceptOccurrences = new HashMap<>();

        for (Map.Entry<String, ExtractionResult> entry : results.entrySet()) {
            String passageId = entry.getKey();
            for (ExtractedConcept concept : entry.getValue().concepts()) {
                conceptOccurrences
                    .computeIfAbsent(concept.normalizedName(), k -> new ArrayList<>())
                    .add(new ConceptOccurrence(passageId, concept));
            }
        }

        // Filter to concepts that appear in at least minShared passages
        return conceptOccurrences.entrySet().stream()
            .filter(e -> getUniquePassageCount(e.getValue()) >= minShared)
            .map(e -> createSharedConcept(e.getKey(), e.getValue()))
            .sorted((a, b) -> Integer.compare(b.passageIds().size(), a.passageIds().size()))
            .collect(Collectors.toList());
    }

    @Override
    public String normalizeConcept(String conceptName) {
        if (conceptName == null) return "";
        return conceptName.toLowerCase()
            .replaceAll("[^a-z0-9\\s]", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private List<ExtractedConcept> extractKeywords(String text, ExtractionConfig config) {
        List<ExtractedConcept> keywords = new ArrayList<>();

        // Tokenize and count word frequencies
        Map<String, Integer> wordCounts = new HashMap<>();
        Matcher matcher = WORD_PATTERN.matcher(text.toLowerCase());

        while (matcher.find()) {
            String word = matcher.group();
            if (!STOP_WORDS.contains(word) && word.length() > 2) {
                wordCounts.merge(word, 1, Integer::sum);
            }
        }

        // Calculate TF-IDF-like scores (simplified)
        int maxCount = wordCounts.values().stream().mapToInt(Integer::intValue).max().orElse(1);

        for (Map.Entry<String, Integer> entry : wordCounts.entrySet()) {
            double tf = (double) entry.getValue() / maxCount;
            double confidence = tf * 0.5 + (entry.getKey().length() > 5 ? 0.3 : 0.1);
            confidence = Math.min(1.0, confidence);

            // Find context
            int pos = text.toLowerCase().indexOf(entry.getKey());
            String context = extractContext(text, pos, 50);

            keywords.add(new ExtractedConcept(
                entry.getKey(),
                normalizeConcept(entry.getKey()),
                "KEYWORD",
                confidence,
                entry.getValue(),
                context
            ));
        }

        return keywords;
    }

    private List<ExtractedConcept> extractTopics(String text, ExtractionConfig config) {
        List<ExtractedConcept> topics = new ArrayList<>();

        // Extract topics from document structure (headings, bullet points, etc.)
        // Look for patterns that indicate topic headings
        Pattern headingPattern = Pattern.compile("(?:^|\\n)#+\\s*(.+)|(?:^|\\n)([A-Z][^.!?\\n]{5,50})(?:[:\\n])", Pattern.MULTILINE);
        Matcher matcher = headingPattern.matcher(text);

        while (matcher.find()) {
            String topic = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (topic != null) {
                topic = topic.trim();
                topics.add(new ExtractedConcept(
                    topic,
                    normalizeConcept(topic),
                    "TOPIC",
                    0.8,  // High confidence for heading-derived topics
                    1,
                    topic
                ));
            }
        }

        return topics;
    }

    private List<ExtractedConcept> extractCapitalizedPhrases(String text, ExtractionConfig config) {
        List<ExtractedConcept> concepts = new ArrayList<>();
        Map<String, Integer> phraseCounts = new HashMap<>();

        Matcher matcher = CAPITALIZED_PHRASE.matcher(text);
        while (matcher.find()) {
            String phrase = matcher.group();
            // Filter out common sentence starters and single capital letters
            if (phrase.length() > 2 && !isCommonSentenceStarter(phrase)) {
                phraseCounts.merge(phrase, 1, Integer::sum);
            }
        }

        for (Map.Entry<String, Integer> entry : phraseCounts.entrySet()) {
            double confidence = Math.min(0.9, 0.5 + entry.getValue() * 0.1);
            int pos = text.indexOf(entry.getKey());
            String context = extractContext(text, pos, 50);

            concepts.add(new ExtractedConcept(
                entry.getKey(),
                normalizeConcept(entry.getKey()),
                "ENTITY",
                confidence,
                entry.getValue(),
                context
            ));
        }

        return concepts;
    }

    private List<ExtractedConcept> extractTechnicalTerms(String text, ExtractionConfig config) {
        List<ExtractedConcept> concepts = new ArrayList<>();
        Map<String, Integer> termCounts = new HashMap<>();

        Matcher matcher = TECHNICAL_TERM.matcher(text);
        while (matcher.find()) {
            String term = matcher.group();
            if (term.length() > 3) {
                termCounts.merge(term, 1, Integer::sum);
            }
        }

        for (Map.Entry<String, Integer> entry : termCounts.entrySet()) {
            double confidence = Math.min(0.85, 0.6 + entry.getValue() * 0.05);
            int pos = text.indexOf(entry.getKey());
            String context = extractContext(text, pos, 50);

            concepts.add(new ExtractedConcept(
                entry.getKey(),
                normalizeConcept(entry.getKey()),
                "TECHNICAL",
                confidence,
                entry.getValue(),
                context
            ));
        }

        return concepts;
    }

    private List<ExtractedConcept> extractNgrams(String text, ExtractionConfig config) {
        List<ExtractedConcept> ngrams = new ArrayList<>();

        // Extract 2-grams and 3-grams
        String[] words = text.toLowerCase().split("\\s+");
        Map<String, Integer> ngramCounts = new HashMap<>();

        for (int n = 2; n <= 3; n++) {
            for (int i = 0; i <= words.length - n; i++) {
                StringBuilder ngram = new StringBuilder();
                boolean valid = true;

                for (int j = 0; j < n; j++) {
                    String word = words[i + j].replaceAll("[^a-z0-9]", "");
                    if (word.length() < 2 || STOP_WORDS.contains(word)) {
                        valid = false;
                        break;
                    }
                    if (j > 0) ngram.append(" ");
                    ngram.append(word);
                }

                if (valid) {
                    ngramCounts.merge(ngram.toString(), 1, Integer::sum);
                }
            }
        }

        // Only keep n-grams that appear multiple times
        for (Map.Entry<String, Integer> entry : ngramCounts.entrySet()) {
            if (entry.getValue() >= 2) {
                double confidence = Math.min(0.7, 0.3 + entry.getValue() * 0.1);
                int pos = text.toLowerCase().indexOf(entry.getKey());
                String context = extractContext(text, pos, 50);

                ngrams.add(new ExtractedConcept(
                    entry.getKey(),
                    normalizeConcept(entry.getKey()),
                    "PHRASE",
                    confidence,
                    entry.getValue(),
                    context
                ));
            }
        }

        return ngrams;
    }

    private List<ConceptRelationship> extractRelationships(String text, List<ExtractedConcept> concepts) {
        List<ConceptRelationship> relationships = new ArrayList<>();

        // Find co-occurring concepts (appear within N words of each other)
        int windowSize = 30;  // Word window for co-occurrence
        String[] words = text.toLowerCase().split("\\s+");

        for (int i = 0; i < concepts.size(); i++) {
            for (int j = i + 1; j < concepts.size(); j++) {
                ExtractedConcept c1 = concepts.get(i);
                ExtractedConcept c2 = concepts.get(j);

                // Calculate co-occurrence strength
                double cooccurrence = calculateCooccurrence(text.toLowerCase(),
                    c1.normalizedName(), c2.normalizedName(), windowSize);

                if (cooccurrence > 0.1) {
                    relationships.add(new ConceptRelationship(
                        c1.name(),
                        c2.name(),
                        "CO_OCCURS",
                        cooccurrence
                    ));
                }
            }
        }

        return relationships;
    }

    private double calculateCooccurrence(String text, String concept1, String concept2, int windowSize) {
        // Find all positions of each concept
        List<Integer> positions1 = findAllPositions(text, concept1);
        List<Integer> positions2 = findAllPositions(text, concept2);

        if (positions1.isEmpty() || positions2.isEmpty()) {
            return 0.0;
        }

        // Count how many times they appear within the window
        int cooccurrences = 0;
        for (int pos1 : positions1) {
            for (int pos2 : positions2) {
                if (Math.abs(pos1 - pos2) <= windowSize * 6) {  // Approximate characters per word
                    cooccurrences++;
                }
            }
        }

        // Normalize by the minimum count
        int minCount = Math.min(positions1.size(), positions2.size());
        return Math.min(1.0, (double) cooccurrences / (minCount * 2));
    }

    private List<Integer> findAllPositions(String text, String term) {
        List<Integer> positions = new ArrayList<>();
        int index = 0;
        while ((index = text.indexOf(term, index)) != -1) {
            positions.add(index);
            index += term.length();
        }
        return positions;
    }

    private List<ExtractedConcept> deduplicateConcepts(List<ExtractedConcept> concepts) {
        Map<String, ExtractedConcept> uniqueConcepts = new LinkedHashMap<>();

        for (ExtractedConcept concept : concepts) {
            String key = concept.normalizedName();
            ExtractedConcept existing = uniqueConcepts.get(key);

            if (existing == null || concept.confidence() > existing.confidence()) {
                uniqueConcepts.put(key, concept);
            } else if (concept.confidence() == existing.confidence() &&
                       concept.frequency() > existing.frequency()) {
                // Merge frequency
                uniqueConcepts.put(key, new ExtractedConcept(
                    existing.name(),
                    existing.normalizedName(),
                    existing.category(),
                    existing.confidence(),
                    existing.frequency() + concept.frequency(),
                    existing.context()
                ));
            }
        }

        return new ArrayList<>(uniqueConcepts.values());
    }

    private String extractContext(String text, int position, int contextLength) {
        if (position < 0 || position >= text.length()) {
            return "";
        }

        int start = Math.max(0, position - contextLength / 2);
        int end = Math.min(text.length(), position + contextLength / 2);

        String context = text.substring(start, end).replaceAll("\\s+", " ").trim();
        if (start > 0) context = "..." + context;
        if (end < text.length()) context = context + "...";

        return context;
    }

    private boolean isCommonSentenceStarter(String phrase) {
        String[] starters = {"The", "A", "An", "This", "That", "It", "We", "They", "He", "She",
                            "There", "Here", "What", "How", "When", "Where", "Why", "Who"};
        for (String starter : starters) {
            if (phrase.equals(starter) || phrase.startsWith(starter + " ")) {
                return true;
            }
        }
        return false;
    }

    private int getUniquePassageCount(List<ConceptOccurrence> occurrences) {
        return (int) occurrences.stream()
            .map(ConceptOccurrence::passageId)
            .distinct()
            .count();
    }

    private SharedConcept createSharedConcept(String normalizedName, List<ConceptOccurrence> occurrences) {
        List<String> passageIds = occurrences.stream()
            .map(ConceptOccurrence::passageId)
            .distinct()
            .collect(Collectors.toList());

        double avgConfidence = occurrences.stream()
            .mapToDouble(o -> o.concept().confidence())
            .average()
            .orElse(0.0);

        int totalFrequency = occurrences.stream()
            .mapToInt(o -> o.concept().frequency())
            .sum();

        // Use the most confident occurrence's name as the display name
        String displayName = occurrences.stream()
            .max(Comparator.comparingDouble(o -> o.concept().confidence()))
            .map(o -> o.concept().name())
            .orElse(normalizedName);

        return new SharedConcept(displayName, normalizedName, passageIds, avgConfidence, totalFrequency);
    }

    // Helper record for tracking concept occurrences across passages
    private record ConceptOccurrence(String passageId, ExtractedConcept concept) {}
}
