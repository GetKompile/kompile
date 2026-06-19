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
import java.util.List;

/**
 * Serializes per-fact-sheet node KG embeddings to a compact binary sidecar so they
 * travel with a cloned project (versioned via git-xet) rather than being recomputed.
 *
 * <p>Structure (nodes/edges + metadata/scoping) is handled by {@link GraphIOService}
 * as diffable JSON; this covers only the dense embedding vectors, which are BLOBs and
 * don't belong in a text-diffable file. The sidecar is keyed by {@code (nodeType,
 * externalId)} so embeddings can be reattached to nodes after structure rehydration.</p>
 *
 * <p>Binary layout: {@code [int magic][int count]} then per entry
 * {@code [UTF nodeType][UTF externalId][UTF algorithm][long version][int len][len bytes]}.</p>
 */
@Service
public class GraphEmbeddingSidecar {

    private static final Logger log = LoggerFactory.getLogger(GraphEmbeddingSidecar.class);

    /** Magic header ("KGE1") guarding against truncated/foreign files. */
    private static final int MAGIC = 0x4B474531;

    private final GraphNodeRepository nodeRepository;
    private final INDArrayConverter converter;

    public GraphEmbeddingSidecar(GraphNodeRepository nodeRepository) {
        this(nodeRepository, new INDArrayConverter());
    }

    /** Test seam: inject a converter so framing can be exercised without a live ND4J backend. */
    GraphEmbeddingSidecar(GraphNodeRepository nodeRepository, INDArrayConverter converter) {
        this.nodeRepository = nodeRepository;
        this.converter = converter;
    }

    /**
     * Serialize all node KG embeddings in a fact sheet.
     *
     * @return the sidecar bytes, or {@code null} when the fact sheet has no embeddings
     *         (so the caller can skip writing / delete a stale file).
     */
    public byte[] export(Long factSheetId) {
        if (factSheetId == null) {
            return null;
        }
        List<GraphNode> nodes = nodeRepository.findByFactSheetIdAndKgEmbeddingNotNull(factSheetId);
        if (nodes.isEmpty()) {
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
        } catch (IOException e) {
            log.warn("Failed to serialize embeddings for fact sheet {}: {}", factSheetId, e.getMessage());
            return null;
        }
        return bos.toByteArray();
    }

    /**
     * Reattach embeddings from a sidecar onto already-rehydrated nodes of a fact sheet.
     *
     * @return the number of nodes an embedding was applied to
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
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String typeName = in.readUTF();
                String externalId = in.readUTF();
                String algoName = in.readUTF();
                long version = in.readLong();
                int len = in.readInt();
                byte[] emb = new byte[len];
                in.readFully(emb);

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
                if (!algoName.isEmpty()) {
                    try {
                        node.setKgEmbeddingAlgorithm(KGEmbeddingAlgorithm.valueOf(algoName));
                    } catch (IllegalArgumentException ignored) {
                        // Unknown/renamed algorithm — keep the vector, drop the label.
                    }
                }
                if (version >= 0) {
                    node.setKgEmbeddingVersion(version);
                }
                nodeRepository.save(node);
                applied++;
            }
        } catch (IOException e) {
            log.warn("Failed to read embedding sidecar for fact sheet {}: {}", factSheetId, e.getMessage());
        }
        return applied;
    }

    private static NodeLevel parseLevel(String name) {
        try {
            return NodeLevel.valueOf(name);
        } catch (IllegalArgumentException e) {
            return NodeLevel.ENTITY;
        }
    }
}
