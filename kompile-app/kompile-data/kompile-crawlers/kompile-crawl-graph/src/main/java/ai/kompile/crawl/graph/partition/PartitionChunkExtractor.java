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

import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.partition.EntityPartition;
import ai.kompile.core.graphrag.partition.PartitionMember;
import ai.kompile.crawl.graph.EntityPartitionCrawlService.StagedExtractor;
import org.springframework.ai.document.Document;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Runs the crawl's real extraction for one partition member.
 *
 * <p>A partition admits chunk <em>ids</em>; extraction needs chunk <em>text</em>. This is the join,
 * and it is deliberately the only place that decides what a missing text means.</p>
 *
 * <h3>The three outcomes</h3>
 * <ul>
 *   <li><b>Text this run does not have</b> — throws, so the lifecycle defers the member with the
 *       reason attached and a later round can try again. Returning null instead would record the
 *       chunk as read and empty, which is a claim about the corpus made on the basis of a lookup
 *       miss.</li>
 *   <li><b>Text with nothing in it</b> — returns null. A blank chunk really was read and really
 *       taught the partition nothing; that is a true statement about the corpus and the coverage
 *       claim should carry it.</li>
 *   <li><b>Text</b> — hands it to the extraction the crawl already uses. Whatever that produces is
 *       returned unpersisted: the staged transaction merges it and the sink writes it once, so
 *       nothing here writes to the graph.</li>
 * </ul>
 */
public final class PartitionChunkExtractor implements StagedExtractor {

    private final Map<String, Document> chunks;
    private final ContextualExtraction extractOne;

    /** Extraction that can see why the shard was scheduled and the partition state so far. */
    @FunctionalInterface
    public interface ContextualExtraction {
        Graph extract(Document document, PartitionMember member, EntityPartition partition);
    }

    /**
     * @param chunks     chunk id to text, from {@link PartitionChunkTexts#index}
     * @param extractOne extraction for a single chunk, returning what it learned and persisting
     *                   nothing; null means the chunk was read and taught nothing, and throwing
     *                   means the chunk could not be read
     */
    public static PartitionChunkExtractor over(Map<String, Document> chunks,
                                               Function<Document, Graph> extractOne) {
        Objects.requireNonNull(extractOne, "an extraction to run per chunk");
        return new PartitionChunkExtractor(chunks,
                (document, member, partition) -> extractOne.apply(document));
    }

    /** Context-aware form used by production partition extraction. */
    public static PartitionChunkExtractor contextual(Map<String, Document> chunks,
                                                      ContextualExtraction extractOne) {
        return new PartitionChunkExtractor(chunks, extractOne);
    }

    private PartitionChunkExtractor(Map<String, Document> chunks,
                                    ContextualExtraction extractOne) {
        this.chunks = chunks == null ? Map.of() : Map.copyOf(chunks);
        this.extractOne = Objects.requireNonNull(extractOne, "an extraction to run per chunk");
    }

    @Override
    public Graph extract(PartitionMember member, EntityPartition partition) {
        Objects.requireNonNull(member, "a member to extract from");
        Document chunk = chunks.get(member.chunkId());
        if (chunk == null) {
            throw new IllegalStateException("chunk " + member.chunkId()
                    + " was admitted to partition "
                    + (partition == null ? "?" : partition.id())
                    + " but this run holds no text for it");
        }
        String text = chunk.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        return extractOne.extract(chunk, member, partition);
    }

    /** How many chunk ids this run can answer for. */
    public int size() {
        return chunks.size();
    }
}
