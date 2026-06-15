package ai.kompile.app.subprocess;

import ai.kompile.app.services.pipeline.ParallelIngestPipeline;
import ai.kompile.core.retrievers.RetrievedDoc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for handling ingest checkpoints to enable resume functionality.
 * Persists chunks, embeddings, and progress to disk.
 */
public class IngestCheckpointService {
    private static final Logger logger = LoggerFactory.getLogger(IngestCheckpointService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Path checkpointDir;
    private final String taskId;

    // File paths
    private final Path chunksFile;
    private final Path embeddingsFile;
    private final Path indexedFile;
    private final Path manifestFile;
    private final Path argsFile;

    // In-memory state for tracking what we've seen

    private final Set<String> indexedChunkIds = ConcurrentHashMap.newKeySet();

    public IngestCheckpointService(Path baseDir, String taskId) {
        this.taskId = taskId;
        this.checkpointDir = baseDir.resolve("checkpoints").resolve(taskId);
        this.chunksFile = checkpointDir.resolve("chunks.jsonl");
        this.embeddingsFile = checkpointDir.resolve("embeddings.jsonl");
        this.indexedFile = checkpointDir.resolve("indexed.txt");
        this.manifestFile = checkpointDir.resolve("manifest.json");
        this.argsFile = checkpointDir.resolve("args.json");
    }

    /**
     * Initialize checkpoint directory.
     */
    public void init() throws IOException {
        if (!Files.exists(checkpointDir)) {
            Files.createDirectories(checkpointDir);
        }
    }

    /**
     * Check if a valid checkpoint exists for this task.
     */
    public boolean hasCheckpoint() {
        return Files.exists(manifestFile) && Files.exists(chunksFile);
    }

    /**
     * Save subprocess arguments for resumption context.
     */
    public void saveArgs(SubprocessArgs args) {
        try {
            objectMapper.writeValue(argsFile.toFile(), args);
        } catch (IOException e) {
            logger.error("Failed to save checkpoint args: {}", e.getMessage());
        }
    }

    /**
     * Load subprocess arguments.
     */
    public SubprocessArgs loadArgs() {
        if (!Files.exists(argsFile))
            return null;
        try {
            return objectMapper.readValue(argsFile.toFile(), SubprocessArgs.class);
        } catch (IOException e) {
            logger.error("Failed to load checkpoint args: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Append created chunks to disk.
     */
    public void saveChunks(List<RetrievedDoc> chunks) {
        if (chunks == null || chunks.isEmpty())
            return;

        try (BufferedWriter writer = Files.newBufferedWriter(chunksFile, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
            for (RetrievedDoc chunk : chunks) {
                // Generate stable ID if missing (critical for resume)
                if (chunk.getId() == null) {
                    // This shouldn't happen with proper chunker, but fail-safe
                    logger.warn("Chunk missing ID in saveChunks, skipping serialization");
                    continue;
                }

                String json = objectMapper.writeValueAsString(chunk);
                writer.write(json);
                writer.newLine();
            }
        } catch (IOException e) {
            logger.error("Failed to checkpoint chunks: {}", e.getMessage());
        }
    }

    /**
     * Append embedded batch to disk context.
     * We serialize the EmbeddedBatch structure which includes embeddings.
     */
    public void saveEmbeddings(ParallelIngestPipeline.EmbeddedBatch batch) {
        if (batch == null || batch.chunks().isEmpty())
            return;

        try (BufferedWriter writer = Files.newBufferedWriter(embeddingsFile, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
            // We save each chunk with its embedding individually for easier parsing
            List<RetrievedDoc> chunks = batch.chunks();
            List<float[]> embeddings = batch.embeddings();

            for (int i = 0; i < chunks.size(); i++) {
                RetrievedDoc chunk = chunks.get(i);
                float[] embedding = i < embeddings.size() ? embeddings.get(i) : null;

                if (embedding != null) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("id", chunk.getId());
                    data.put("embedding", embedding);

                    writer.write(objectMapper.writeValueAsString(data));
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            logger.error("Failed to checkpoint embeddings: {}", e.getMessage());
        }
    }

    /**
     * Mark chunks as successfully indexed.
     */
    public void markIndexed(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty())
            return;

        try (BufferedWriter writer = Files.newBufferedWriter(indexedFile, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
            for (String id : chunkIds) {
                writer.write(id);
                writer.newLine();
                indexedChunkIds.add(id);
            }
        } catch (IOException e) {
            logger.error("Failed to checkpoint indexed IDs: {}", e.getMessage());
        }
    }

    /**
     * Load state for resumption.
     */
    public CheckpointState loadState() {
        CheckpointState state = new CheckpointState(new HashSet<>(), new HashSet<>(), new ArrayList<>(),
                new HashMap<>());

        if (!hasCheckpoint())
            return state;

        try {
            // 1. Load indexed IDs
            if (Files.exists(indexedFile)) {
                List<String> lines = Files.readAllLines(indexedFile);
                state.indexedIds.addAll(lines);
                this.indexedChunkIds.addAll(lines);
            }

            // 2. Load Embeddings (to skip re-embedding if possible, or just to know what's
            // done)
            // Ideally we could reload embeddings into memory to skip re-embedding.
            // For now, let's just track IDs.
            // Parsing all embeddings might be heavy on memory.
            // Optimally: We only need to reload embeddings for chunks that are EMBEDDED but
            // NOT INDEXED.
            // Chunks that are INDEXED don't need their embeddings loaded.

            Map<String, float[]> orphanedEmbeddings = new HashMap<>();
            if (Files.exists(embeddingsFile)) {
                try (var lines = Files.lines(embeddingsFile)) {
                    lines.forEach(line -> {
                        try {
                            var node = objectMapper.readTree(line);
                            String id = node.get("id").asText();
                            // If NOT indexed yet, keep this embedding
                            if (!state.indexedIds.contains(id)) {
                                float[] emb = objectMapper.convertValue(node.get("embedding"), float[].class);
                                orphanedEmbeddings.put(id, emb);
                            }
                            state.embeddedIds.add(id);
                        } catch (Exception e) {
                            logger.warn("Skipping corrupt embedding checkpoint line: {}", e.getMessage());
                        }
                    });
                }
            }
            state.orphanedEmbeddings.putAll(orphanedEmbeddings);

            // 3. Load Chunks
            // We need to reload chunks that were created but NOT indexed.
            if (Files.exists(chunksFile)) {
                try (var lines = Files.lines(chunksFile)) {
                    lines.forEach(line -> {
                        try {
                            RetrievedDoc doc = objectMapper.readValue(line, RetrievedDoc.class);
                            // If not indexed, we need to process it
                            if (!state.indexedIds.contains(doc.getId())) {
                                state.orphanedChunks.add(doc);
                            }
                        } catch (Exception e) {
                            logger.warn("Skipping corrupt chunk checkpoint line: {}", e.getMessage());
                        }
                    });
                }
            }

            logger.info("Loaded checkpoint: {} indexed, {} orphaned embeddings, {} orphaned chunks to process",
                    state.indexedIds.size(), state.orphanedEmbeddings.size(), state.orphanedChunks.size());

        } catch (IOException e) {
            logger.error("Failed to load checkpoint state", e);
        }

        return state;
    }

    public void cleanup() {
        // No-op: checkpoint cleanup is handled by the caller or by separate retention policy.
        // Retained as a hook for future checkpoint directory cleanup if needed.
    }

    public record CheckpointState(
            Set<String> indexedIds,
            Set<String> embeddedIds,
            List<RetrievedDoc> orphanedChunks, // Chunks created but not yet fully processed
            Map<String, float[]> orphanedEmbeddings // Embeddings computed but not indexed
    ) {
    }
}
