/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Hybrid semantic/lexical search over crawl artifacts from exactly one project directory. */
final class LocalProjectRagSearch {
    private static final int INDEX_VERSION = 1;
    private static final int EMBEDDING_BATCH_SIZE = 32;
    private static final String INDEX_FILE = "semantic-index.jsonl";

    interface EmbeddingRuntime extends AutoCloseable {
        String modelId();

        String fingerprint();

        List<float[]> embedBatch(List<String> texts) throws Exception;

        @Override
        void close();
    }

    @FunctionalInterface
    interface RuntimeFactory {
        EmbeddingRuntime open(Path projectRoot, ObjectMapper mapper) throws Exception;
    }

    private final ObjectMapper mapper;
    private final RuntimeFactory runtimeFactory;

    LocalProjectRagSearch(ObjectMapper mapper) {
        this(mapper, LocalEmbeddingRuntime::open);
    }

    LocalProjectRagSearch(ObjectMapper mapper, RuntimeFactory runtimeFactory) {
        this.mapper = mapper;
        this.runtimeFactory = runtimeFactory;
    }

    synchronized ToolResult search(Path projectRoot, List<Path> knowledgeBases, String query,
                                   String topic, int limit) {
        final Path root;
        final List<Path> localKnowledgeBases = new ArrayList<>();
        try {
            root = projectRoot.toRealPath();
            Path configuredCrawlRoot = root.resolve("data/crawls");
            if (Files.isSymbolicLink(configuredCrawlRoot)) {
                return ToolResult.error("Local RAG refused a symbolic-link crawl directory: "
                        + configuredCrawlRoot);
            }
            Path crawlRoot = configuredCrawlRoot.toRealPath();
            if (!crawlRoot.startsWith(root)) {
                return ToolResult.error("Local RAG refused a crawl directory outside the current folder: "
                        + configuredCrawlRoot);
            }
            for (Path knowledgeBase : knowledgeBases) {
                if (Files.isSymbolicLink(knowledgeBase)) {
                    return ToolResult.error("Local RAG refused a symbolic-link knowledge base: "
                            + knowledgeBase);
                }
                Path local = knowledgeBase.toRealPath();
                if (!local.startsWith(crawlRoot)) {
                    return ToolResult.error("Local RAG refused a knowledge base outside the current folder: "
                            + knowledgeBase);
                }
                localKnowledgeBases.add(local);
            }
        } catch (IOException e) {
            return ToolResult.error("Local RAG could not validate the current folder: "
                    + conciseMessage(e));
        }
        for (Path knowledgeBase : localKnowledgeBases) {
            if (!knowledgeBase.startsWith(root)) {
                return ToolResult.error("Local RAG refused a knowledge base outside the current folder: "
                        + knowledgeBase);
            }
        }

        try {
            List<Chunk> chunks = loadChunks(localKnowledgeBases);
            if (chunks.isEmpty()) {
                return emptyResult(query, localKnowledgeBases.size(), "hybrid", null);
            }
            String effectiveQuery = topic == null || topic.isBlank()
                    ? query : query + "\nTopic: " + topic.strip();
            try (EmbeddingRuntime embedding = runtimeFactory.open(root, mapper)) {
                Map<String, VectorEntry> vectors = ensureVectors(chunks, localKnowledgeBases, embedding);
                float[] queryVector = singleEmbedding(embedding.embedBatch(List.of(effectiveQuery)));
                return format(query, topic, localKnowledgeBases, hybridHits(chunks, vectors, queryVector,
                        effectiveQuery, limit), "hybrid", embedding.modelId(), null);
            } catch (Exception semanticFailure) {
                List<Hit> lexical = lexicalHits(chunks, effectiveQuery, limit);
                return format(query, topic, localKnowledgeBases, lexical, "lexical-fallback", null,
                        conciseMessage(semanticFailure));
            }
        } catch (Exception e) {
            return ToolResult.error("Project-local knowledge search failed: " + conciseMessage(e));
        }
    }

    private List<Chunk> loadChunks(List<Path> knowledgeBases) throws IOException {
        List<Chunk> chunks = new ArrayList<>();
        for (Path directoryValue : knowledgeBases) {
            Path directory = directoryValue.toRealPath();
            Path documentsPath = containedRegularFile(directory, "documents.jsonl");
            Map<String, String> sources = documentsPath == null
                    ? Map.of() : documentSources(documentsPath);
            String knowledgeBase = directory.getFileName().toString();
            Path chunksPath = containedRegularFile(directory, "chunks.jsonl");
            if (chunksPath == null) {
                continue;
            }
            try (Stream<String> lines = Files.lines(chunksPath, StandardCharsets.UTF_8)) {
                lines.filter(line -> !line.isBlank()).forEach(line -> {
                    try {
                        JsonNode chunk = mapper.readTree(line);
                        String content = chunk.path("text").asText("");
                        if (content.isBlank()) {
                            return;
                        }
                        String documentId = chunk.path("documentId").asText("");
                        String chunkId = chunk.path("chunkId").asText("");
                        chunks.add(new Chunk(directory, knowledgeBase,
                                sources.getOrDefault(documentId, documentId), documentId,
                                chunkId, documentId + "\u0000" + chunkId,
                                content, sha256(content)));
                    } catch (Exception ignored) {
                        // Keep healthy chunks searchable when one JSONL row is malformed.
                    }
                });
            }
        }
        return chunks;
    }

    private Map<String, VectorEntry> ensureVectors(List<Chunk> chunks, List<Path> knowledgeBases,
                                                   EmbeddingRuntime embedding) throws Exception {
        Map<Path, List<Chunk>> byDirectory = new LinkedHashMap<>();
        for (Chunk chunk : chunks) {
            byDirectory.computeIfAbsent(chunk.directory(), ignored -> new ArrayList<>()).add(chunk);
        }
        Map<String, VectorEntry> all = new HashMap<>();
        for (Path directory : knowledgeBases) {
            List<Chunk> localChunks = byDirectory.getOrDefault(
                    directory.toAbsolutePath().normalize(), List.of());
            if (localChunks.isEmpty()) {
                continue;
            }
            Map<String, VectorEntry> cached = readIndex(directory.resolve(INDEX_FILE),
                    embedding.modelId(), embedding.fingerprint());
            List<Chunk> missing = localChunks.stream()
                    .filter(chunk -> {
                        VectorEntry vector = cached.get(chunk.key());
                        return vector == null || !chunk.contentHash().equals(vector.contentHash());
                    }).toList();
            for (int from = 0; from < missing.size(); from += EMBEDDING_BATCH_SIZE) {
                int to = Math.min(missing.size(), from + EMBEDDING_BATCH_SIZE);
                List<Chunk> batch = missing.subList(from, to);
                List<float[]> embedded = embedding.embedBatch(
                        batch.stream().map(Chunk::content).toList());
                if (embedded.size() != batch.size()) {
                    throw new IOException("Embedding subprocess returned " + embedded.size()
                            + " vectors for " + batch.size() + " chunks");
                }
                for (int i = 0; i < batch.size(); i++) {
                    float[] vector = embedded.get(i);
                    validateVector(vector);
                    Chunk chunk = batch.get(i);
                    cached.put(chunk.key(), new VectorEntry(chunk.key(), chunk.contentHash(), vector));
                }
            }
            LinkedHashMap<String, VectorEntry> current = new LinkedHashMap<>();
            for (Chunk chunk : localChunks) {
                VectorEntry vector = cached.get(chunk.key());
                if (vector != null && chunk.contentHash().equals(vector.contentHash())) {
                    current.put(chunk.key(), vector);
                    all.put(chunk.knowledgeBase() + "\u0000" + chunk.key(), vector);
                }
            }
            if (!missing.isEmpty() || cached.size() != current.size()) {
                try {
                    writeIndex(directory.resolve(INDEX_FILE), embedding.modelId(),
                            embedding.fingerprint(), current.values());
                } catch (IOException ignored) {
                    // The semantic index is a cache. Keep the computed result when persistence is
                    // unavailable; a later writable search can rebuild it.
                }
            }
        }
        return all;
    }

    private Map<String, VectorEntry> readIndex(Path path, String modelId, String fingerprint) {
        Map<String, VectorEntry> result = new HashMap<>();
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return result;
        }
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            lines.filter(line -> !line.isBlank()).forEach(line -> {
                try {
                    JsonNode node = mapper.readTree(line);
                    if (node.path("version").asInt() != INDEX_VERSION
                            || !modelId.equals(node.path("modelId").asText())
                            || !fingerprint.equals(node.path("modelFingerprint").asText())) {
                        return;
                    }
                    ArrayNode values = (ArrayNode) node.path("vector");
                    float[] vector = new float[values.size()];
                    for (int i = 0; i < vector.length; i++) {
                        vector[i] = (float) values.get(i).asDouble();
                    }
                    validateVector(vector);
                    String key = node.path("key").asText();
                    result.put(key, new VectorEntry(key,
                            node.path("contentHash").asText(), vector));
                } catch (Exception ignored) {
                    // A stale/corrupt row is recomputed from the current chunk.
                }
            });
        } catch (IOException ignored) {
            // The index is a cache; unreadable caches are rebuilt.
        }
        return result;
    }

    private void writeIndex(Path path, String modelId, String fingerprint,
                            Iterable<VectorEntry> entries)
            throws IOException {
        Files.createDirectories(path.getParent());
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Refusing to replace a symbolic-link semantic index: " + path);
        }
        Path temporary = Files.createTempFile(path.getParent(), ".semantic-index-", ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                for (VectorEntry entry : entries) {
                    ObjectNode row = mapper.createObjectNode();
                    row.put("version", INDEX_VERSION);
                    row.put("modelId", modelId);
                    row.put("modelFingerprint", fingerprint);
                    row.put("key", entry.key());
                    row.put("contentHash", entry.contentHash());
                    ArrayNode vector = row.putArray("vector");
                    for (float value : entry.vector()) {
                        vector.add(value);
                    }
                    writer.write(mapper.writeValueAsString(row));
                    writer.newLine();
                }
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private List<Hit> hybridHits(List<Chunk> chunks, Map<String, VectorEntry> vectors,
                                 float[] queryVector, String query, int limit) {
        List<String> terms = queryTerms(query);
        int maxLexical = chunks.stream().mapToInt(chunk -> lexicalScore(chunk.content(), query, terms))
                .max().orElse(0);
        List<Hit> hits = new ArrayList<>();
        for (Chunk chunk : chunks) {
            VectorEntry vector = vectors.get(chunk.knowledgeBase() + "\u0000" + chunk.key());
            if (vector == null) {
                continue;
            }
            int lexical = lexicalScore(chunk.content(), query, terms);
            double semantic = Math.max(0.0, cosine(queryVector, vector.vector()));
            double lexicalNormalized = maxLexical == 0 ? 0.0 : (double) lexical / maxLexical;
            hits.add(new Hit(chunk, 0.85 * semantic + 0.15 * lexicalNormalized,
                    semantic, lexical));
        }
        return limit(hits, limit);
    }

    private List<Hit> lexicalHits(List<Chunk> chunks, String query, int limit) {
        List<String> terms = queryTerms(query);
        List<Hit> hits = new ArrayList<>();
        int max = 0;
        for (Chunk chunk : chunks) {
            int lexical = lexicalScore(chunk.content(), query, terms);
            if (lexical > 0) {
                max = Math.max(max, lexical);
                hits.add(new Hit(chunk, lexical, 0.0, lexical));
            }
        }
        int denominator = Math.max(1, max);
        List<Hit> normalized = hits.stream()
                .map(hit -> new Hit(hit.chunk(), (double) hit.lexicalScore() / denominator,
                        0.0, hit.lexicalScore()))
                .toList();
        return limit(normalized, limit);
    }

    private List<Hit> limit(List<Hit> hits, int requestedLimit) {
        hits = new ArrayList<>(hits);
        hits.sort(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(hit -> hit.chunk().source())
                .thenComparing(hit -> hit.chunk().chunkId()));
        int bounded = Math.min(50, Math.max(1, requestedLimit));
        return hits.size() > bounded ? new ArrayList<>(hits.subList(0, bounded)) : hits;
    }

    private ToolResult format(String query, String topic, List<Path> knowledgeBases,
                              List<Hit> hits, String mode, String modelId, String degradedReason) {
        if (hits.isEmpty()) {
            return emptyResult(query, knowledgeBases.size(), mode, degradedReason);
        }
        StringBuilder output = new StringBuilder("Project-local ")
                .append("hybrid".equals(mode) ? "hybrid RAG" : "lexical RAG fallback")
                .append(" results\n\n");
        int index = 0;
        for (Hit hit : hits) {
            index++;
            Chunk chunk = hit.chunk();
            output.append("### ").append(index).append(". ").append(chunk.source())
                    .append(" [").append(chunk.knowledgeBase()).append("] (")
                    .append(String.format(Locale.ROOT, "%.2f", hit.score())).append(")\n")
                    .append(chunk.content().strip()).append("\n\n");
        }
        Map<String, Object> metadata = metadata(query, topic, knowledgeBases, hits.size(), mode,
                modelId, degradedReason);
        return ToolResult.success("knowledge_search: " + query, output.toString().strip(), metadata);
    }

    private ToolResult emptyResult(String query, int baseCount, String mode, String degradedReason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("query", query);
        metadata.put("resultCount", 0);
        metadata.put("backend", "project-local");
        metadata.put("retrievalMode", mode);
        metadata.put("knowledgeBaseCount", baseCount);
        if (degradedReason != null) {
            metadata.put("degradedReason", degradedReason);
        }
        return ToolResult.success("knowledge_search: " + query,
                "No project-local chunks matched. Knowledge bases searched: " + baseCount + ".",
                metadata);
    }

    private Map<String, Object> metadata(String query, String topic, List<Path> knowledgeBases,
                                         int resultCount, String mode, String modelId,
                                         String degradedReason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("query", query);
        metadata.put("resultCount", resultCount);
        metadata.put("backend", "project-local");
        metadata.put("retrievalMode", mode);
        metadata.put("knowledgeBases", knowledgeBases.stream()
                .map(path -> path.getFileName().toString()).toList());
        if (topic != null && !topic.isBlank()) metadata.put("topic", topic);
        if (modelId != null) metadata.put("embeddingModel", modelId);
        if (degradedReason != null) metadata.put("degradedReason", degradedReason);
        return metadata;
    }

    private Map<String, String> documentSources(Path path) throws IOException {
        Map<String, String> result = new HashMap<>();
        if (!Files.isRegularFile(path)) return result;
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            lines.filter(line -> !line.isBlank()).forEach(line -> {
                try {
                    JsonNode document = mapper.readTree(line);
                    String id = document.path("documentId").asText("");
                    String source = firstNonBlank(document.path("relativePath").asText(null),
                            document.path("source").asText(null), id, "Unknown");
                    result.put(id, source);
                } catch (Exception ignored) {
                }
            });
        }
        return result;
    }

    private int lexicalScore(String content, String query, List<String> terms) {
        String normalized = content.toLowerCase(Locale.ROOT);
        int score = 0;
        String phrase = query.toLowerCase(Locale.ROOT).strip();
        if (phrase.length() > 2 && normalized.contains(phrase)) score += 8;
        for (String term : terms) {
            int from = 0;
            while ((from = normalized.indexOf(term, from)) >= 0) {
                score++;
                from += term.length();
            }
        }
        return score;
    }

    private List<String> queryTerms(String query) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String term : query.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) {
            if (term.length() > 1) terms.add(term);
        }
        return new ArrayList<>(terms);
    }

    private static float[] singleEmbedding(List<float[]> embeddings) throws IOException {
        if (embeddings.size() != 1) {
            throw new IOException("Embedding subprocess did not return one query vector");
        }
        float[] vector = embeddings.get(0);
        validateVector(vector);
        return vector;
    }

    private static void validateVector(float[] vector) throws IOException {
        if (vector == null || vector.length == 0) throw new IOException("Empty embedding vector");
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IOException("Non-finite embedding vector");
        }
    }

    private static double cosine(float[] left, float[] right) {
        if (left.length != right.length) return 0.0;
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        return leftNorm == 0.0 || rightNorm == 0.0
                ? 0.0 : dot / Math.sqrt(leftNorm * rightNorm);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String conciseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static Path containedRegularFile(Path directory, String fileName) throws IOException {
        Path candidate = directory.resolve(fileName);
        if (Files.isSymbolicLink(candidate)) {
            throw new IOException("Refusing symbolic-link crawl artifact: " + candidate);
        }
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) return null;
        Path real = candidate.toRealPath();
        if (!real.startsWith(directory)) {
            throw new IOException("Crawl artifact escapes its knowledge base: " + candidate);
        }
        return real;
    }

    private record Chunk(Path directory, String knowledgeBase, String source, String documentId,
                         String chunkId, String key, String content, String contentHash) {
    }

    private record VectorEntry(String key, String contentHash, float[] vector) {
    }

    private record Hit(Chunk chunk, double score, double semanticScore, int lexicalScore) {
    }
}
