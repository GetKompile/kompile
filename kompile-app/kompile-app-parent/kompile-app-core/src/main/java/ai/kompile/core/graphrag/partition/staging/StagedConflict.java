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

package ai.kompile.core.graphrag.partition.staging;

/**
 * Two chunks said different things about the same staged item, and staging kept one of them.
 *
 * <p>Merging is the point of a staged graph, but merging is lossy: the moment one chunk's answer
 * wins, the other answer is gone unless something records it. That loss is where a partition
 * quietly turns disagreement into fact. A conflict is the receipt — it names the field, what was
 * kept, what was dropped, and which chunk proposed the dropped value, so an audit can go back to
 * the source rather than take the merge's word for it.</p>
 *
 * <p>Conflicts are not errors. Two chunks calling the same company an {@code ORGANIZATION} and a
 * {@code COMPANY} is ordinary; the commit still happens. They are the signal that a reconciler,
 * a human, or a later ontology pass has something to look at.</p>
 *
 * @param key      staged key of the item the disagreement is about
 * @param field    which field disagreed, e.g. {@code type} or {@code description}
 * @param kept     value staging is keeping
 * @param rejected value staging is dropping
 * @param chunkId  chunk that proposed the rejected value
 */
public record StagedConflict(
        String key,
        String field,
        String kept,
        String rejected,
        String chunkId) {

    public StagedConflict {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a conflict has to name the item it is about");
        }
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("a conflict has to name the field that disagreed");
        }
    }

    public String describe() {
        return key + "." + field + ": kept '" + kept + "', " + chunkId + " said '" + rejected + "'";
    }
}
