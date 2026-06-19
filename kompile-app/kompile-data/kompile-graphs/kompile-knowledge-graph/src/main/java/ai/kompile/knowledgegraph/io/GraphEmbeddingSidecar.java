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
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes per-fact-sheet KG embeddings to a compact binary sidecar so they travel with a
 * cloned project (versioned via git-xet) rather than being recomputed. Structure (nodes/edges
 * + metadata/scoping) is handled by {@link GraphIOService} as diffable JSON; this covers only
 * the dense embedding vectors, which are BLOBs and don't belong in a text-diffable file.
 *
 * <ul>
 *   <li><b>Node embeddings</b> are keyed by {@code (nodeType, externalId)} and reattached to
 *       the matching rehydrated node.</li>
 *   <li><b>Relation embeddings</b> are type-shared, so they are keyed by {@code edgeType} (edge
 *       UUIDs are regenerated on import and can't be used as keys) and applied to every edge of
 *       that type in the fact sheet.</li>
 * </ul>
 *
 * <p>Binary layout: {@code [int magic][int nodeCount]} then per node
 * {@code [UTF nodeType][UTF externalId][UTF algorithm][long version][int len][bytes]},
 * followed by {@code [int edgeTypeCount]} then per type
 * {@code [UTF edgeType][UTF algorithm][long version][int len][bytes]}.</p>
 */
@Service
public class GraphEmbeddingSidecar {

    private static final Logger log = LoggerFactory.getLogger(GraphEmbeddingSidecar.class);

    /** Magic header ("KGE1") guarding against truncated/foreign files. */
    private static final int MAGIC = 0x4B474531;

    private final GraphNodeRepository nodeRepository;
    private final GraphEdgeRepository edgeRepository;
    private final INDArrayConverter converter;

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

    /**
     * Serialize a fact sheet's node + relation embeddings.
     *
     * @return the sidecar bytes, or {@code null} when the fact sheet has no embeddings
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
        if (nodes.isEmpty() && byType.isEmpty()) {
            return null;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(MAGIC);
            out.writeInt(nodes.size());
            for (GraphNode n : nodes) {
                byte[] emb = converter.convertToDatabaseColumn(n.getKgEmbedding());
                if (emb == null) {
                    emb = new byte[0];
                }
                out.writeUTF(n.getNodeType() == null ? NodeLevel.ENTITY.name() : n.getNodeType().name());
                out.writeUTF(n.getExternalId() == null ? "" : n.getExternalId());
                out.writeUTF(n.getKgEmbeddingAlgorithm() == null ? "" : n.getKgEmbeddingAlgorithm().name());
                out.writeLong(n.getKgEmbeddingVersion() == null ? -1L : n.getKgEmbeddingVersion());
                out.writeInt(emb.length);
                out.write(emb);
            }
            out.writeInt(byType.size());
            for (Map.Entry<EdgeType, GraphEdge> entry : byType.entrySet()) {
                GraphEdge edge = entry.getValue();
                byte[] emb = converter.convertToDatabaseColumn(edge.getKgRelationEmbedding());
                if (emb == null) {
                    emb = new byte[0];
                }
                out.writeUTF(entry.getKey().name());
                out.writeUTF(edge.getKgEmbeddingAlgorithm() == null ? "" : edge.getKgEmbeddingAlgorithm().name());
                out.writeLong(edge.getKgEmbeddingVersion() == null ? -1L : edge.getKgEmbeddingVersion());
                out.writeInt(emb.length);
                out.write(emb);
            }
        } catch (IOException e) {
            log.warn("Failed to serialize embeddings for fact sheet {}: {}", factSheetId, e.getMessage());
            return null;
        }
        return bos.toByteArray();
    }

    /**
     * Reattach embeddings from a sidecar onto already-rehydrated nodes/edges of a fact sheet.
     *
     * @return the number of node + edge embeddings applied
     */
    public int importInto(Long factSheetId, byte[] data) {
        if (factSheetId == null || data == null || data.length == 0) {
            return 0;
        }
        int applied = 0;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != MAGIC) {
                log.warn("Embedding sidecar for fact sheet {} has an unexpected header; skipping", factSheetId);
                return 0;
            }
            int nodeCount = in.readInt();
            for (int i = 0; i < nodeCount; i++) {
                String typeName = in.readUTF();
                String externalId = in.readUTF();
                String algoName = in.readUTF();
                long version = in.readLong();
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
                nodeRepository.save(node);
                applied++;
            }

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
        } catch (IOException e) {
            log.warn("Failed to read embedding sidecar for fact sheet {}: {}", factSheetId, e.getMessage());
        }
        return applied;
    }

    private static byte[] readBlock(DataInputStream in) throws IOException {
        int len = in.readInt();
        byte[] block = new byte[len];
        in.readFully(block);
        return block;
    }

    private static void applyAlgorithm(String algoName, java.util.function.Consumer<KGEmbeddingAlgorithm> setter) {
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
