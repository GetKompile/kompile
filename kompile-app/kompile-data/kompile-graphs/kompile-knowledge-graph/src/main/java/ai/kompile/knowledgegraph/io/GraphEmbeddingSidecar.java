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
package ai.kompile.knowledgegraph.io;

import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.embedding.util.INDArrayConverter;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Serializes a fact sheet's KG embeddings to a compact binary sidecar so they travel with a
 * cloned project (versioned via git-xet) rather than being recomputed.
 *
 * <p>Two embedding families are preserved:</p>
 * <ol>
 *   <li><b>Structural KGE</b> ({@code kgEmbedding}: TransE/RotatE) — stored in node metadata
 *       inside the @Primary matrix/vector store via the {@link KnowledgeGraphService} seam.
 *       Written by {@link ai.kompile.knowledgegraph.embedding.service.KGEmbeddingStorageService}.</li>
 *   <li><b>Live-store node vectors</b> — the text embeddings the @Primary store holds for
 *       similarity search. Read/written through
 *       {@link KnowledgeGraphService#exportNodeEmbeddings}/{@link KnowledgeGraphService#applyNodeEmbeddings}.</li>
 * </ol>
 *
 * <p>Binary layout ("KGE2"): {@code [int magic]} then three sections —
 * {@code [int jpaNodeCount]}×{@code [UTF nodeType][UTF externalId][UTF algorithm][long version][long updatedAtEpochMilli][int len][bytes]},
 * {@code [int edgeTypeCount]}×{@code [UTF edgeType][UTF algorithm][long version][int len][bytes]},
 * {@code [int liveNodeCount]}×{@code [UTF nodeType][UTF externalId][int len][bytes]}.
 * Legacy "KGE1" files (first two sections, node entries without the {@code updatedAtEpochMilli} slot) are still read.</p>
 */
@Service
public class GraphEmbeddingSidecar {

    private static final Logger log = LoggerFactory.getLogger(GraphEmbeddingSidecar.class);

    /** Legacy magic ("KGE1"): structural-KGE node + edge sections only. */
    private static final int MAGIC_V1 = 0x4B474531;
    /** Current magic ("KGE2"): adds a live-store node-embedding section. */
    private static final int MAGIC_V2 = 0x4B474532;

    private final INDArrayConverter converter;

    /**
     * The @Primary live store — provides both KGE metadata (TransE/RotatE) via the new seam
     * methods and text embeddings via exportNodeEmbeddings/applyNodeEmbeddings.
     * Optional to keep test seams simple.
     */
    @Autowired(required = false)
    private KnowledgeGraphService graphService;

    @Autowired
    public GraphEmbeddingSidecar() {
        this(new INDArrayConverter());
    }

    /** Test seam: inject a converter so framing can be exercised without a live ND4J backend. */
    GraphEmbeddingSidecar(INDArrayConverter converter) {
        this.converter = converter;
    }

    /** Test seam: also wire the live store so the matrix/vector node-embedding path can be exercised. */
    GraphEmbeddingSidecar(INDArrayConverter converter, KnowledgeGraphService graphService) {
        this(converter);
        this.graphService = graphService;
    }

    /**
     * Serialize a fact sheet's structural KGE node embeddings (TransE/RotatE), edge-type embeddings,
     * and live-store per-node text vectors.
     *
     * @return the sidecar bytes, or {@code null} when the fact sheet has no embeddings at all.
     */
    public byte[] export(Long factSheetId) {
        if (factSheetId == null || graphService == null) {
            return null;
        }

        // Section 1: structural-KGE node embeddings (stored in node metadata via seam)
        List<GraphNode> kgeNodes = graphService.findNodesWithKgEmbedding(factSheetId);

        // Section 2: edge-type relation embeddings (keyed by EdgeType name)
        Map<String, INDArray> edgeTypeEmbs = graphService.getEdgeTypeKgEmbeddings(factSheetId);
        KGEmbeddingAlgorithm storedAlgo = graphService.getStoredKgAlgorithm(factSheetId);

        // Section 3: live-store text embedding vectors
        Map<GraphNode, INDArray> liveNodes = collectLiveNodeEmbeddings(factSheetId);

        if (kgeNodes.isEmpty() && edgeTypeEmbs.isEmpty() && liveNodes.isEmpty()) {
            return null;
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(MAGIC_V2);

            // Section 1: structural-KGE node embeddings
            out.writeInt(kgeNodes.size());
            for (GraphNode n : kgeNodes) {
                INDArray emb = n.getKgEmbedding();
                byte[] embBytes = safeBytes(emb);
                out.writeUTF(n.getNodeType() == null ? NodeLevel.ENTITY.name() : n.getNodeType().name());
                out.writeUTF(n.getExternalId() == null ? "" : n.getExternalId());
                out.writeUTF(n.getKgEmbeddingAlgorithm() == null ? "" : n.getKgEmbeddingAlgorithm().name());
                out.writeLong(n.getKgEmbeddingVersion() == null ? -1L : n.getKgEmbeddingVersion());
                out.writeLong(n.getKgEmbeddingUpdatedAt() == null ? -1L : n.getKgEmbeddingUpdatedAt().toEpochMilli());
                out.writeInt(embBytes.length);
                out.write(embBytes);
            }

            // Section 2: edge-type relation embeddings (type-shared)
            out.writeInt(edgeTypeEmbs.size());
            for (Map.Entry<String, INDArray> entry : edgeTypeEmbs.entrySet()) {
                byte[] embBytes = safeBytes(entry.getValue());
                out.writeUTF(entry.getKey());
                out.writeUTF(storedAlgo == null ? "" : storedAlgo.name());
                // Version not tracked per edge type — use -1 sentinel
                out.writeLong(-1L);
                out.writeInt(embBytes.length);
                out.write(embBytes);
            }

            // Section 3 (KGE2): live-store node text embeddings
            out.writeInt(liveNodes.size());
            for (Map.Entry<GraphNode, INDArray> entry : liveNodes.entrySet()) {
                GraphNode n = entry.getKey();
                byte[] embBytes = safeBytes(entry.getValue());
                out.writeUTF(n.getNodeType() == null ? NodeLevel.ENTITY.name() : n.getNodeType().name());
                out.writeUTF(n.getExternalId() == null ? "" : n.getExternalId());
                out.writeInt(embBytes.length);
                out.write(embBytes);
            }

        } catch (IOException e) {
            log.warn("Failed to serialize embeddings for fact sheet {}: {}", factSheetId, e.getMessage());
            return null;
        }
        return bos.toByteArray();
    }

    /** Map each fact-sheet node that the live store has a text embedding for to its vector. */
    private Map<GraphNode, INDArray> collectLiveNodeEmbeddings(Long factSheetId) {
        Map<GraphNode, INDArray> result = new LinkedHashMap<>();
        if (graphService == null) {
            return result;
        }
        Map<String, INDArray> byNodeId;
        try {
            byNodeId = graphService.exportNodeEmbeddings(factSheetId);
        } catch (Exception e) {
            log.warn("Live-store embedding export failed for fact sheet {}: {}", factSheetId, e.getMessage());
            return result;
        }
        if (byNodeId == null || byNodeId.isEmpty()) {
            return result;
        }
        for (GraphNode n : graphService.getNodesInFactSheet(factSheetId)) {
            INDArray emb = byNodeId.get(n.getNodeId());
            if (emb != null && n.getExternalId() != null) {
                result.put(n, emb);
            }
        }
        return result;
    }

    private byte[] safeBytes(INDArray vec) {
        byte[] emb = converter.convertToDatabaseColumn(vec);
        return emb == null ? new byte[0] : emb;
    }

    /**
     * Reattach embeddings from a sidecar onto already-rehydrated nodes/edges of a fact sheet:
     * structural KGE back into node metadata via the seam, and live-store text vectors back into
     * the matrix/vector store (which re-indexes them for similarity search).
     *
     * @return the number of node + edge embeddings applied
     */
    public int importInto(Long factSheetId, byte[] data) {
        if (factSheetId == null || data == null || data.length == 0 || graphService == null) {
            return 0;
        }
        int applied = 0;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int magic = in.readInt();
            if (magic != MAGIC_V1 && magic != MAGIC_V2) {
                log.warn("Embedding sidecar for fact sheet {} has an unexpected header; skipping", factSheetId);
                return 0;
            }

            // Section 1: structural-KGE node embeddings
            int nodeCount = in.readInt();
            for (int i = 0; i < nodeCount; i++) {
                String typeName = in.readUTF();
                String externalId = in.readUTF();
                String algoName = in.readUTF();
                long version = in.readLong();
                long updatedAtMs = (magic == MAGIC_V2) ? in.readLong() : -1L;
                byte[] emb = readBlock(in);

                if (externalId.isEmpty()) continue;
                INDArray vec = converter.convertToEntityAttribute(emb);
                if (vec == null) continue;

                NodeLevel level = parseLevel(typeName);
                graphService.getNodeByExternalIdInFactSheet(externalId, level, factSheetId)
                        .ifPresent(node -> {
                            KGEmbeddingAlgorithm algo = parseAlgorithm(algoName);
                            Instant updAt = updatedAtMs >= 0 ? Instant.ofEpochMilli(updatedAtMs) : null;
                            long ver = version >= 0 ? version : 0L;
                            graphService.storeNodeKgEmbedding(node.getNodeId(), vec, algo, ver, updAt);
                        });
                applied++;
            }

            // Section 2: edge-type relation embeddings
            int edgeTypeCount = in.readInt();
            for (int i = 0; i < edgeTypeCount; i++) {
                String edgeTypeName = in.readUTF();
                String algoName = in.readUTF();
                long version = in.readLong();
                byte[] emb = readBlock(in);

                INDArray vec = converter.convertToEntityAttribute(emb);
                if (vec == null) continue;

                KGEmbeddingAlgorithm algo = parseAlgorithm(algoName);
                long ver = version >= 0 ? version : 0L;
                graphService.storeEdgeTypeKgEmbedding(edgeTypeName, vec, algo, ver, factSheetId);
                applied++;
            }

            // Section 3 (KGE2 only): live-store text node embeddings
            if (magic == MAGIC_V2) {
                applied += importLiveNodeEmbeddings(factSheetId, in);
            }
        } catch (IOException e) {
            log.warn("Failed to read embedding sidecar for fact sheet {}: {}", factSheetId, e.getMessage());
        }
        return applied;
    }

    /**
     * Read the live-store section and push the vectors back into the @Primary store in one batched
     * call (which also warms the vector index). The section is always fully consumed from the
     * stream even when no live store is wired, so the read stays well-framed.
     */
    private int importLiveNodeEmbeddings(Long factSheetId, DataInputStream in) throws IOException {
        int liveCount = in.readInt();
        Map<String, INDArray> byNodeId = new LinkedHashMap<>();
        for (int i = 0; i < liveCount; i++) {
            String typeName = in.readUTF();
            String externalId = in.readUTF();
            byte[] emb = readBlock(in);
            if (graphService == null) {
                continue;
            }
            INDArray vec = converter.convertToEntityAttribute(emb);
            if (vec == null) {
                continue;
            }
            graphService.getNodeByExternalIdInFactSheet(externalId, parseLevel(typeName), factSheetId)
                    .ifPresent(node -> byNodeId.put(node.getNodeId(), vec));
        }
        if (graphService == null || byNodeId.isEmpty()) {
            return 0;
        }
        try {
            return graphService.applyNodeEmbeddings(byNodeId);
        } catch (Exception e) {
            log.warn("Live-store embedding apply failed for fact sheet {}: {}", factSheetId, e.getMessage());
            return 0;
        }
    }

    private static byte[] readBlock(DataInputStream in) throws IOException {
        int len = in.readInt();
        byte[] block = new byte[len];
        in.readFully(block);
        return block;
    }

    private static KGEmbeddingAlgorithm parseAlgorithm(String algoName) {
        if (algoName == null || algoName.isEmpty()) return null;
        try {
            return KGEmbeddingAlgorithm.valueOf(algoName);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static NodeLevel parseLevel(String name) {
        try {
            return NodeLevel.valueOf(name);
        } catch (IllegalArgumentException e) {
            return NodeLevel.ENTITY;
        }
    }
}
