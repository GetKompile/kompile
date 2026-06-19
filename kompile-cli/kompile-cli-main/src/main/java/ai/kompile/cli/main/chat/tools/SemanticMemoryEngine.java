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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.utils.StringUtils;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Semantic memory engine that integrates with kompile's SameDiff embedding
 * infrastructure for high-quality dense vector retrieval.
 *
 * <p>When the anserini module is on the classpath (provides
 * {@code GenericDenseSameDiffEncoder}), the engine uses bge-base-en-v1.5
 * to produce 768-dimensional dense embeddings — the same encoder used by
 * kompile's RAG pipeline. Model files are auto-downloaded and cached by
 * {@code KompileModelManager} to {@code ~/.kompile/models/}.
 *
 * <p>When native ML libraries are not available (e.g., lightweight CLI
 * deployments), the engine falls back to TF-IDF bag-of-words vectors
 * with cosine similarity.
 *
 * <p>The engine watches {@code ~/.kompile/memory/} and
 * {@code ~/.claude/projects/} and refreshes the index every 60 seconds.
 */
public class SemanticMemoryEngine {

    private static final int DEFAULT_TOP_K = 5;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.15;
    private static final int MAX_MEMORY_CONTENT_LENGTH = 2000;
    private static final String DEFAULT_MODEL_ID = "bge-base-en-v1.5";

    private final List<MemoryEntry> memories = new CopyOnWriteArrayList<>();
    private final Map<String, float[]> denseVectors = new ConcurrentHashMap<>();
    private final Map<String, double[]> tfidfVectors = new ConcurrentHashMap<>();
    private final Map<String, Integer> documentFrequency = new ConcurrentHashMap<>();
    private final Set<String> vocabulary = ConcurrentHashMap.newKeySet();
    private final List<Path> watchDirs = new CopyOnWriteArrayList<>();
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final ScheduledExecutorService refreshExecutor;
    private volatile long lastRefreshTime = 0;
    private static final long REFRESH_INTERVAL_MS = 60_000; // Re-scan every 60s

    // Dense encoder — loaded via reflection to avoid hard dependency on anserini/ND4J
    private volatile Object encoder; // GenericDenseSameDiffEncoder (or null if unavailable)
    private volatile java.lang.reflect.Method encodeMethod; // encode(String) -> float[]
    private volatile boolean useDenseEmbeddings = false;
    private volatile String encoderMode = "uninitialized";

    public SemanticMemoryEngine() {
        this.refreshExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "semantic-memory-refresh");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Initialize the engine by attempting to load the SameDiff encoder,
     * scanning memory directories, and building the index.
     * Idempotent — safe to call multiple times.
     */
    public void initialize() {
        if (initialized.getAndSet(true)) return;

        // Standard memory locations
        Path home = Paths.get(System.getProperty("user.home"));
        addWatchDir(home.resolve(".kompile").resolve("memory"));
        addWatchDir(home.resolve(".claude").resolve("projects"));

        // Initial scan with TF-IDF (available immediately)
        refreshIndex();

        // Schedule periodic refresh
        refreshExecutor.scheduleAtFixedRate(this::refreshIndex,
                REFRESH_INTERVAL_MS, REFRESH_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // Load dense encoder asynchronously — model download can take minutes
        // and must not block the MCP server startup handshake. The engine starts
        // with TF-IDF and upgrades to dense embeddings once the encoder is ready.
        Thread encoderLoader = new Thread(() -> {
            initializeDenseEncoder();
            if (useDenseEmbeddings) {
                // Re-index existing memories with dense vectors now that encoder is available
                reindexWithDenseEncoder();
            }
        }, "semantic-encoder-loader");
        encoderLoader.setDaemon(true);
        encoderLoader.start();
    }

    /**
     * Attempt to load GenericDenseSameDiffEncoder via reflection.
     * Falls back to TF-IDF if anserini/ND4J not on classpath.
     */
    private void initializeDenseEncoder() {
        try {
            Class<?> encoderClass = Class.forName(
                    "io.anserini.encoder.samediff.GenericDenseSameDiffEncoder");
            // Use the simple single-arg constructor: new GenericDenseSameDiffEncoder("bge-base-en-v1.5")
            var constructor = encoderClass.getConstructor(String.class);
            encoder = constructor.newInstance(DEFAULT_MODEL_ID);
            encodeMethod = encoderClass.getMethod("encode", String.class);
            useDenseEmbeddings = true;
            encoderMode = "samediff:" + DEFAULT_MODEL_ID;
            System.err.println("[SemanticMemory] Initialized with SameDiff encoder: " + DEFAULT_MODEL_ID);
        } catch (ClassNotFoundException e) {
            encoderMode = "tfidf (anserini not on classpath)";
            System.err.println("[SemanticMemory] Anserini encoder not available, using TF-IDF fallback");
        } catch (Exception e) {
            encoderMode = "tfidf (encoder init failed: " + e.getMessage() + ")";
            System.err.println("[SemanticMemory] Failed to initialize SameDiff encoder: "
                    + e.getMessage() + " — using TF-IDF fallback");
        }
    }

    /**
     * Re-index all existing memory entries with the dense encoder after it
     * becomes available. Entries that were initially indexed with TF-IDF
     * get a dense vector added so subsequent queries use dense similarity.
     */
    private void reindexWithDenseEncoder() {
        int reindexed = 0;
        for (MemoryEntry entry : memories) {
            if (denseVectors.containsKey(entry.id)) continue;
            float[] vec = denseEncode(entry.content);
            if (vec != null) {
                denseVectors.put(entry.id, vec);
                reindexed++;
            }
        }
        if (reindexed > 0) {
            System.err.println("[SemanticMemory] Re-indexed " + reindexed
                    + " memories with dense encoder");
        }
    }

    /**
     * Add a directory to the watch list.
     * Directories that do not exist are silently ignored.
     */
    public void addWatchDir(Path dir) {
        if (Files.isDirectory(dir)) {
            watchDirs.add(dir);
        }
    }

    /**
     * Query for relevant memories given a turn/message text.
     * Returns the top-K most similar memories above the threshold.
     */
    public List<RetrievedMemory> query(String text) {
        return query(text, DEFAULT_TOP_K, DEFAULT_SIMILARITY_THRESHOLD);
    }

    /**
     * Query for relevant memories with explicit top-K and similarity threshold.
     *
     * @param text      the query text to embed and compare
     * @param topK      maximum number of results to return
     * @param threshold minimum cosine similarity required (0–1)
     * @return ranked list of retrieved memories, most similar first
     */
    public List<RetrievedMemory> query(String text, int topK, double threshold) {
        if (memories.isEmpty() || text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        List<RetrievedMemory> results;
        if (useDenseEmbeddings) {
            results = queryDense(text, threshold);
        } else {
            results = queryTfidf(text, threshold);
        }

        results.sort((a, b) -> Double.compare(b.similarity, a.similarity));
        return results.subList(0, Math.min(topK, results.size()));
    }

    private List<RetrievedMemory> queryDense(String text, double threshold) {
        float[] queryVec = denseEncode(text);
        if (queryVec == null) return Collections.emptyList();

        List<RetrievedMemory> results = new ArrayList<>();
        for (MemoryEntry entry : memories) {
            float[] memVec = denseVectors.get(entry.id);
            if (memVec == null) continue;

            double sim = cosineSimilarityFloat(queryVec, memVec);
            if (sim >= threshold) {
                results.add(new RetrievedMemory(entry, sim));
            }
        }
        return results;
    }

    private List<RetrievedMemory> queryTfidf(String text, double threshold) {
        double[] queryVec = tfidfVector(tokenize(text));

        List<RetrievedMemory> results = new ArrayList<>();
        for (MemoryEntry entry : memories) {
            double[] memVec = tfidfVectors.get(entry.id);
            if (memVec == null) continue;

            double sim = cosineSimilarityDouble(queryVec, memVec);
            if (sim >= threshold) {
                results.add(new RetrievedMemory(entry, sim));
            }
        }
        return results;
    }

    /**
     * Index a conversation turn for future retrieval within the session.
     */
    public void indexTurn(String sessionId, int turnNumber, String role, String content) {
        String id = "turn:" + sessionId + ":" + turnNumber;
        String text = role + ": " + content;
        MemoryEntry entry = new MemoryEntry(id, "session_turn", text,
                StringUtils.truncate(text, MAX_MEMORY_CONTENT_LENGTH), null, System.currentTimeMillis());
        addEntry(entry);
    }

    /** Return the total number of indexed memory entries. */
    public int size() {
        return memories.size();
    }

    /** Return a human-readable summary of index statistics. */
    public String stats() {
        Map<String, Long> typeCounts = memories.stream()
                .collect(Collectors.groupingBy(m -> m.type, Collectors.counting()));
        if (useDenseEmbeddings) {
            return String.format("Indexed: %d memories, encoder: %s, types: %s",
                    memories.size(), encoderMode, typeCounts);
        } else {
            return String.format("Indexed: %d memories, mode: %s, vocab: %d terms, types: %s",
                    memories.size(), encoderMode, vocabulary.size(), typeCounts);
        }
    }

    /** Return the current encoder mode ("samediff:bge-base-en-v1.5" or "tfidf"). */
    public String getEncoderMode() {
        return encoderMode;
    }

    /** Return whether dense embeddings are active. */
    public boolean isDenseMode() {
        return useDenseEmbeddings;
    }

    /**
     * Shut down the background refresh executor and close the encoder.
     */
    public void shutdown() {
        refreshExecutor.shutdownNow();
        if (encoder != null) {
            try {
                // GenericDenseSameDiffEncoder implements AutoCloseable
                if (encoder instanceof AutoCloseable) {
                    ((AutoCloseable) encoder).close();
                }
            } catch (Exception e) {
                // ignore
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void refreshIndex() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshTime < REFRESH_INTERVAL_MS / 2) return;
        lastRefreshTime = now;

        for (Path dir : watchDirs) {
            try {
                scanDirectory(dir);
            } catch (Exception e) {
                System.err.println("[SemanticMemory] Error scanning " + dir + ": " + e.getMessage());
            }
        }
    }

    private void scanDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;

        try (var stream = Files.walk(dir, 5)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                  .filter(Files::isRegularFile)
                  .forEach(this::indexMemoryFile);
        }
    }

    private void indexMemoryFile(Path file) {
        String id = "file:" + file.toAbsolutePath();
        try {
            long lastModified = Files.getLastModifiedTime(file).toMillis();
            // Skip if already indexed and file hasn't changed
            for (MemoryEntry existing : memories) {
                if (existing.id.equals(id) && existing.indexedAt >= lastModified) {
                    return;
                }
            }

            String content = Files.readString(file);
            if (content.isBlank()) return;

            // Parse YAML frontmatter if present
            String type = "unknown";
            String name = file.getFileName().toString();
            if (content.startsWith("---")) {
                int endIdx = content.indexOf("---", 3);
                if (endIdx > 0) {
                    String frontmatter = content.substring(3, endIdx);
                    for (String line : frontmatter.split("\n")) {
                        line = line.trim();
                        if (line.startsWith("type:")) {
                            type = line.substring(5).trim();
                        } else if (line.startsWith("name:")) {
                            name = line.substring(5).trim();
                        }
                    }
                    content = content.substring(endIdx + 3).trim();
                }
            }

            // Remove existing entry if updating
            memories.removeIf(e -> e.id.equals(id));
            denseVectors.remove(id);
            tfidfVectors.remove(id);

            MemoryEntry entry = new MemoryEntry(id, type, name,
                    StringUtils.truncate(content, MAX_MEMORY_CONTENT_LENGTH), file, lastModified);
            addEntry(entry);

        } catch (IOException e) {
            // Skip unreadable files silently
        }
    }

    private void addEntry(MemoryEntry entry) {
        if (useDenseEmbeddings) {
            float[] vec = denseEncode(entry.content);
            if (vec != null) {
                memories.add(entry);
                denseVectors.put(entry.id, vec);
                return;
            }
            // Dense encoding failed for this entry — fall through to TF-IDF for this entry
        }

        // TF-IDF path
        List<String> tokens = tokenize(entry.content);
        Set<String> uniqueTokens = new HashSet<>(tokens);
        for (String token : uniqueTokens) {
            vocabulary.add(token);
            documentFrequency.merge(token, 1, Integer::sum);
        }

        double[] vec = tfidfVector(tokens);
        memories.add(entry);
        tfidfVectors.put(entry.id, vec);
    }

    // ── Dense encoding via SameDiff ───────────────────────────────────────────

    /**
     * Encode text using the SameDiff encoder (bge-base-en-v1.5).
     * Returns null if encoding fails or encoder is not available.
     */
    private float[] denseEncode(String text) {
        if (encoder == null || encodeMethod == null) return null;
        try {
            Object result = encodeMethod.invoke(encoder, text);
            if (result instanceof float[]) {
                return (float[]) result;
            }
        } catch (Exception e) {
            // Log first failure, then suppress
            System.err.println("[SemanticMemory] Dense encode failed: " + e.getMessage());
        }
        return null;
    }

    private static double cosineSimilarityFloat(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // ── TF-IDF fallback ──────────────────────────────────────────────────────

    private List<String> tokenize(String text) {
        if (text == null) return Collections.emptyList();
        return Arrays.stream(text.toLowerCase().split("[\\s\\p{Punct}]+"))
                .filter(t -> t.length() >= 2)
                .filter(t -> !STOP_WORDS.contains(t))
                .collect(Collectors.toList());
    }

    private double[] tfidfVector(List<String> tokens) {
        Map<String, Integer> termFreq = new HashMap<>();
        for (String token : tokens) {
            termFreq.merge(token, 1, Integer::sum);
        }

        List<String> vocabList = new ArrayList<>(vocabulary);
        double[] vec = new double[vocabList.size()];
        int totalDocs = Math.max(1, memories.size());

        for (int i = 0; i < vocabList.size(); i++) {
            String term = vocabList.get(i);
            int tf = termFreq.getOrDefault(term, 0);
            int df = documentFrequency.getOrDefault(term, 1);
            vec[i] = tf * Math.log((double) totalDocs / df + 1);
        }
        return vec;
    }

    private static double cosineSimilarityDouble(double[] a, double[] b) {
        int len = Math.min(a.length, b.length);
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        for (int i = len; i < a.length; i++) normA += a[i] * a[i];
        for (int i = len; i < b.length; i++) normB += b[i] * b[i];

        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // ── Data Types ────────────────────────────────────────────────────────────

    /** A single indexed memory item. */
    public static class MemoryEntry {
        public final String id;
        public final String type;
        public final String name;
        public final String content;
        public final Path sourcePath;
        public final long indexedAt;

        public MemoryEntry(String id, String type, String name, String content,
                           Path sourcePath, long indexedAt) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.content = content;
            this.sourcePath = sourcePath;
            this.indexedAt = indexedAt;
        }
    }

    /** A memory entry paired with its cosine-similarity score for a given query. */
    public static class RetrievedMemory {
        public final MemoryEntry entry;
        public final double similarity;

        public RetrievedMemory(MemoryEntry entry, double similarity) {
            this.entry = entry;
            this.similarity = similarity;
        }

        @Override
        public String toString() {
            return String.format("[%.2f] %s: %s", similarity, entry.name,
                    StringUtils.truncate(entry.content, 100));
        }
    }

    // ── English stop-word list ─────────────────────────────────────────────────

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "is", "at", "which", "on", "a", "an", "and", "or", "but",
            "in", "with", "to", "for", "of", "not", "no", "can", "had", "have",
            "was", "were", "be", "been", "being", "do", "does", "did", "will",
            "would", "could", "should", "may", "might", "shall", "this", "that",
            "these", "those", "it", "its", "my", "your", "our", "their", "his",
            "her", "he", "she", "we", "they", "me", "him", "us", "them", "if",
            "then", "else", "when", "where", "how", "what", "who", "whom",
            "from", "by", "as", "so", "just", "also", "than", "more", "very"
    );
}
