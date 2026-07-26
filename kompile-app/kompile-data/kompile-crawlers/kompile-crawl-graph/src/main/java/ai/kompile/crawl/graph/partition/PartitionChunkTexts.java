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
 *  limitations under the License.
 */

package ai.kompile.crawl.graph.partition;

import org.springframework.ai.document.Document;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Indexes a crawl's chunks by every id a partition might cite them under.
 *
 * <p>Discovery names chunks by whatever id the evidence carried: a graph walk cites the
 * {@code sourceChunkId} the extractor stamped on a node, while a vector hit cites the document id
 * the index knows. Those are usually the same string and occasionally are not — the tree already
 * tolerates seven spellings of the key, and {@link GraphProvenanceChunks} is where that reading
 * lives. A partition run needs the text behind the id, so the index has to answer for all of
 * them.</p>
 *
 * <p>The alternative — keying only on document id — fails quietly rather than loudly: the member
 * is admitted, the text lookup misses, and the chunk is deferred as unreadable even though the
 * crawl is holding it. Indexing the aliases too is what stops a partition reporting a gap it does
 * not have.</p>
 *
 * <p>Blank chunks are indexed like any other. A chunk with no text is readable and holds nothing,
 * which is a different claim from a chunk whose text this run never had, and only the first of
 * those is honest to record as processed.</p>
 */
public final class PartitionChunkTexts {

    private PartitionChunkTexts() {
    }

    /**
     * Chunk id to document, including provenance aliases.
     *
     * <p>Document ids are claimed first so an alias can never displace a real chunk: where two
     * documents want the same key the earlier one keeps it, because a partition citing that id got
     * it from the graph, and the graph's writer saw the earlier document.</p>
     */
    public static Map<String, Document> index(Collection<Document> documents) {
        Map<String, Document> byChunkId = new LinkedHashMap<>();
        if (documents == null || documents.isEmpty()) {
            return byChunkId;
        }
        for (Document document : documents) {
            if (document != null && document.getId() != null && !document.getId().isBlank()) {
                byChunkId.putIfAbsent(document.getId(), document);
            }
        }
        for (Document document : documents) {
            if (document == null) {
                continue;
            }
            for (String alias : GraphProvenanceChunks.chunkIds(document.getMetadata())) {
                byChunkId.putIfAbsent(alias, document);
            }
        }
        return byChunkId;
    }
}
