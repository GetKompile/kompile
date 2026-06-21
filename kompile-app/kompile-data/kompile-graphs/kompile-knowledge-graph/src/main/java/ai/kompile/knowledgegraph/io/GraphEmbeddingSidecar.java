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
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.embedding.util.INDArrayConverter;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
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
 * cloned project (versioned via git-xet) rather than being recomputed. Structure (nodes/edges
 * + metadata/scoping) is handled by {@link GraphIOService} as diffable JSON; this covers only
 * the dense embedding vectors, which are BLOBs and don't belong in a text-diffable file.
 *
 * <p>Two embedding families are preserved, because the project keeps embeddings in two places:</p>
 * <ol>
 *   <li><b>Structural KGE</b> ({@code kgEmbedding}: TransE/RotatE) lives in the JPA
 *       {@code GraphNode}/{@code GraphEdge} tables, written by {@code KGEmbeddingStorageService}.
 *       Read here straight from the JPA repositories. Expensive and not recomputable from a single
 *       node's text, so it must travel.</li>
 *   <li><b>Live-store node vectors</b> — the text embeddings the @Primary matrix/vector store holds
 *       (computed by {@code MatrixGraphConstructor} as {@code embed(title + " " + description)}) and
 *       indexes for similarity search. Recomputable, but carrying them lets a freshly cloned project
 *       warm its vector index without re-embedding. Read/written through the store-agnostic
 *       {@link KnowledgeGraphService#exportNodeEmbeddings}/{@link KnowledgeGraphService#applyNodeEmbeddings}
 *       seam, so the matrix store re-indexes them on import.</li>
 * </ol>
 *
 * <p>Node embeddings are keyed by {@code (nodeType, externalId)} (node UUIDs regenerate on import);
 * relation embeddings are type-shared and keyed by {@code edgeType}.</p>
 *
 * <p>Binary layout ("KGE2"): {@code [int magic]} then three sections —
 * {@code [int jpaNodeCount]}×{@code [UTF nodeType][UTF externalId][UTF algorithm][long version][long updatedAtEpochMilli][int len][bytes]},
 * {@code [int edgeTypeCount]}×{@code [UTF edgeType][UTF algorithm][long version][int len][bytes]},
 * {@code [int liveNodeCount]}×{@code [UTF nodeType][UTF externalId][int len][bytes]}.
 * Legacy "KGE1" files (first two sections, node entries without the {@code updatedAtEpochMilli} slot) are still read. See
 * {@code docs/architecture/graph-serialization-storage-audit.md} (H-4).</p>
 */
@Service
public class GraphEmbeddingSidecar {

    private static final Logger log = LoggerFactory.getLogger(GraphEmbeddingSidecar.class);

    /** Legacy magic ("KGE1"): JPA node + edge sections only. */
    private static final int MAGIC_V1 = 0x4B474531;
    /** Current magic ("KGE2"): adds a live-store (matrix/vector) node-embedding section. */
    private static final int MAGIC_V2 = 0x4B474532;

    private final GraphNodeRepository nodeRepository;
    private final GraphEdgeRepository edgeRepository;
    private final INDArrayConverter converter;

    /**
     * The @Primary live store, used to also carry the matrix/vector store's per-node vectors so a
     * cloned project's similarity index is warm without recomputation. Optional — the JPA
     * structural-KGE path works without it.
     */
    @Autowired(required = false)
    private KnowledgeGraphService graphService;

    @Autowired
    public GraphEmbeddingSidecar(GraphNodeRepository nodeRepository, GraphEdgeRepository edgeRepository) {
        this(nodeRepository, edgeRepository, new INDArrayConverter());
    }

    /** Test seam: inject a converter so framing can be exercised without a live ND4J backend. */
    GraphEmbeddingSidecar(GraphNodeRepository nodeRepository, GraphEdgeRepository edgeRepository,
                          INDArrayConverter converter) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.converter = converter;
    }

    /** Test seam: also wire the live store so the matrix/vector node-embedding path can be exercised. */
    GraphEmbeddingSidecar(GraphNodeRepository nodeRepository, GraphEdgeRepository edgeRepository,
                          INDArrayConverter converter, KnowledgeGraphService graphService) {
        this(nodeRepository, edgeRepository, converter);
        this.graphService = graphService;
    }

    /**
     * Serialize a fact sheet's node + relation embeddings (JPA structural KGE) plus the live
     * store's per-node vectors.
     *
     * @return the sidecar bytes, or {@code null} when the fact sheet has no embeddings at all
     *         (so the caller can skip writing / delete a stale file).
     */
    public byte[] export(Long factSheetId) {
        if (factSheetId == null) {
            return null;
        }
        List<GraphNode> nodes = nodeRepository.findByFactSheetIdAndKgEmbeddingNotNull(factSheetId);
        // Relation embeddings are type-shared — keep one representative edge per type.
        Map<EdgeType, GraphEdge> byType = new LinkedHashMap<>();
        for (GraphEdge e : edgeRepository.findByFactSheetIdAndKgRelationEmbeddingNotNull(factSheetId)) {
            if (e.getEdgeType() != null) {
                byType.putIfAbsent(e.getEdgeType(), e);
            }
        }
        // Live-store (matrix/vector) node embeddings, keyed by (nodeType, externalId) so they
        // survive nodeId regeneration on import.
        Map<GraphNode, INDArray> liveNodes = collectLiveNodeEmbeddings(factSheetId);

        if (nodes.isEmpty() && byType.isEmpty() && liveNodes.isEmpty()) {
            return null;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(MAGIC_V2);

            // Section 1: JPA structural-KGE node embeddings.
            out.writeInt(nodes.size());
            for (GraphNode n : nodes) {
                byte[] emb = safeBytes(n.getKgEmbedding());
                out.writeUTF(n.getNodeType() == null ? NodeLevel.ENTITY.name() : n.getNodeType().name());
                out.writeUTF(n.getExternalId() == null ? "" : n.getExternalId());
                out.writeUTF(n.getKgEmbeddingAlgorithm() == null ? "" : n.getKgEmbeddingAlgorithm().name());
                out.writeLong(n.getKgEmbeddingVersion() == null ? -1L : n.getKgEmbeddingVersion());
                // [H-5] kgEmbeddingUpdatedAt — epoch milli, -1 when absent (KGE2 only).
                out.writeLong(n.getKgEmbeddingUpdatedAt() == null ? -1L : n.getKgEmbeddingUpdatedAt().toEpochMilli());
                out.writeInt(emb.length);
                out.write(emb);
            }

            // Section 2: JPA relation embeddings (type-shared).
            out.writeInt(byType.size());
            for (Map.Entry<EdgeType, GraphEdge> entry : byType.entrySet()) {
                GraphEdge edge = entry.getValue();
                byte[] emb = safeBytes(edge.getKgRelationEmbedding());
                out.writeUTF(entry.getKey().name());
                out.writeUTF(edge.getKgEmbeddingAlgorithm() == null ? "" : edge.getKgEmbeddingAlgorithm().name());
                out.writeLong(edge.getKgEmbeddingVersion() == null ? -1L : edge.getKgEmbeddingVersion());
                out.writeInt(emb.length);
                out.write(emb);
            }

            // Section 3 (KGE2): live-store node embeddings (matrix/vector text vectors).
            out.writeInt(liveNodes.size());
            for (Map.Entry<GraphNode, INDArray> entry : liveNodes.entrySet()) {
                GraphNode n = entry.getKey();
                byte[] emb = safeBytes(entry.getValue());
                out.writeUTF(n.getNodeType() == null ? NodeLevel.ENTITY.name() : n.getNodeType().name());
                out.writeUTF(n.getExternalId() == null ? "" : n.getExternalId());
                out.writeInt(emb.length);
                out.write(emb);
            }
        } catch (IOException e) {
            log.warn("Failed to serialize embeddings for fact sheet {}: {}", factSheetId, e.getMessage());
            return null;
        }
        return bos.toByteArray();
    }

    /** Map each fact-sheet node that the live store has an embedding for to its vector. */
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
     * JPA structural KGE onto the JPA rows, and live-store node vectors back into the matrix/vector
     * store (which re-indexes them for similarity search).
     *
     * @return the number of node + edge embeddings applied
     */
    public int importInto(Long factSheetId, byte[] data) {
        if (factSheetId == null || data == null || data.length == 0) {
            return 0;
        }
        int applied = 0;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int magic = in.readInt();
            if (magic != MAGIC_V1 && magic != MAGIC_V2) {
                log.warn("Embedding sidecar for fact sheet {} has an unexpected header; skipping", factSheetId);
                return 0;
            }

            // Section 1: JPA structural-KGE node embeddings.
            int nodeCount = in.readInt();
            for (int i = 0; i < nodeCount; i++) {
                String typeName = in.readUTF();
                String externalId = in.readUTF();
                String algoName = in.readUTF();
                long version = in.readLong();
                // [H-5] kgEmbeddingUpdatedAt slot exists only in KGE2; KGE1 files have none.
                long updatedAtMs = (magic == MAGIC_V2) ? in.readLong() : -1L;
                byte[] emb = readBlock(in);

                GraphNode node = nodeRepository
                        .findByExternalIdAndNodeTypeAndFactSheetId(externalId, parseLevel(typeName), factSheetId)
                        .orElse(null);
                if (node == null) {
                    continue;
                }
                INDArray vec = converter.convertToEntityAttribute(emb);
                if (vec == null) {
                    continue;
                }
                node.setKgEmbedding(vec);
                applyAlgorithm(algoName, node::setKgEmbeddingAlgorithm);
                if (version >= 0) {
                    node.setKgEmbeddingVersion(version);
                }
                if (updatedAtMs >= 0) {
                    node.setKgEmbeddingUpdatedAt(Instant.ofEpochMilli(updatedAtMs));
                }
                nodeRepository.save(node);
                applied++;
            }

            // Section 2: JPA relation embeddings.
            int edgeTypeCount = in.readInt();
            for (int i = 0; i < edgeTypeCount; i++) {
                String typeName = in.readUTF();
                String algoName = in.readUTF();
                long version = in.readLong();
                byte[] emb = readBlock(in);

                EdgeType type;
                try {
                    type = EdgeType.valueOf(typeName);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                INDArray vec = converter.convertToEntityAttribute(emb);
                if (vec == null) {
                    continue;
                }
                for (GraphEdge edge : edgeRepository.findByFactSheetIdAndEdgeType(factSheetId, type)) {
                    edge.setKgRelationEmbedding(vec);
                    applyAlgorithm(algoName, edge::setKgEmbeddingAlgorithm);
                    if (version >= 0) {
                        edge.setKgEmbeddingVersion(version);
                    }
                    edgeRepository.save(edge);
                    applied++;
                }
            }

            // Section 3 (KGE2 only): live-store node embeddings → reattach + re-index.
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

    private static void applyAlgorithm(String algoName, Consumer<KGEmbeddingAlgorithm> setter) {
        if (algoName == null || algoName.isEmpty()) {
            return;
        }
        try {
            setter.accept(KGEmbeddingAlgorithm.valueOf(algoName));
        } catch (IllegalArgumentException ignored) {
            // Unknown/renamed algorithm — keep the vector, drop the label.
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
