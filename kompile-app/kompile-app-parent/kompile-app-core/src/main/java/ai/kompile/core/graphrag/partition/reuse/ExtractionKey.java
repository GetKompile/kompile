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

package ai.kompile.core.graphrag.partition.reuse;

import ai.kompile.core.graphrag.partition.PartitionMember;
import ai.kompile.utils.HashUtils;

import java.util.Optional;

/**
 * Names a unit of extraction work: this text, read by this extractor.
 *
 * <p>Chunks are read more than once by design. Hierarchical chunking emits overlapping windows,
 * a document's boilerplate repeats verbatim across its sections, and a bridge chunk is admitted
 * into every partition whose subject it mentions — grouping reduces that last one but cannot
 * remove it. Each of those is the same question asked twice, and an SLM asked the same question
 * twice bills twice for the same answer.</p>
 *
 * <p>The key is derived from the content rather than from the chunk's name, which is what makes
 * it safe: when a source changes, its text changes, so the new work gets a new key and the old
 * answer is never served for it. Nothing has to remember to invalidate anything.</p>
 *
 * <p>The profile is part of the identity and is required. Two extractors reading the same text
 * are not doing the same work — a different model, pass, or prompt is a different question, and
 * answering it from another one's cache is how a reuse layer starts quietly serving wrong
 * answers. A key with no profile is refused rather than defaulted.</p>
 *
 * @param basis       what the fingerprint was taken from
 * @param fingerprint digest of the content, or the versioned chunk identity
 * @param profile     who would do the extracting: model, pass and prompt version
 */
public record ExtractionKey(Basis basis, String fingerprint, String profile) {

    /** What a key was derived from, kept so an audit can tell a strong claim from a weaker one. */
    public enum Basis {

        /** Digest of the chunk's text. Two chunks with identical text share this key. */
        CONTENT,

        /**
         * Chunk id plus its source version. Weaker than {@link #CONTENT}: it only ever matches
         * the same chunk again, so it saves the bridge-chunk re-read but not the duplicate-text
         * one. Used when the text itself is not on hand.
         */
        VERSIONED_ID
    }

    public ExtractionKey {
        if (basis == null) {
            throw new IllegalArgumentException("an extraction key needs a basis");
        }
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("an extraction key needs a fingerprint");
        }
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException(
                    "an extraction key needs a profile: reusing one extractor's answer for "
                            + "another's question is how a cache starts lying");
        }
        fingerprint = fingerprint.trim();
        profile = profile.trim();
    }

    /**
     * Keys on the chunk's text.
     *
     * <p>Only whitespace is normalised. It is the one difference that cannot change what an
     * extractor finds — a paragraph rewrapped by a chunker is the same paragraph — whereas
     * folding case or punctuation would merge texts a reader would answer differently.</p>
     *
     * @return empty when there is no text to key on; blank text identifies nothing
     */
    public static Optional<ExtractionKey> ofText(String text, String profile) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ExtractionKey(Basis.CONTENT,
                HashUtils.sha256Hex(normalise(text)), profile));
    }

    /**
     * Keys on the chunk's identity and source version.
     *
     * @return empty when either half is missing; a bare chunk id is not an identity, because the
     *         same id names different text after the source changes
     */
    public static Optional<ExtractionKey> ofVersionedId(String chunkId, String chunkVersion,
                                                        String profile) {
        if (chunkId == null || chunkId.isBlank() || chunkVersion == null
                || chunkVersion.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ExtractionKey(Basis.VERSIONED_ID,
                chunkId.trim() + "@" + chunkVersion.trim(), profile));
    }

    /**
     * Keys a member on its text when the caller has it, and on its versioned identity otherwise.
     *
     * <p>Returning empty is a real outcome, not a failure: a member with neither text nor a source
     * version cannot be shown identical to anything, and the only safe thing to do with work that
     * cannot be identified is to do it.</p>
     */
    public static Optional<ExtractionKey> forMember(PartitionMember member, String text,
                                                    String profile) {
        if (member == null) {
            return Optional.empty();
        }
        Optional<ExtractionKey> byContent = ofText(text, profile);
        return byContent.isPresent() ? byContent
                : ofVersionedId(member.chunkId(), member.chunkVersion(), profile);
    }

    /** Stable identity, safe to use as a map key or to persist. */
    public String id() {
        return profile + "/" + basis.name().toLowerCase() + ":" + fingerprint;
    }

    /** True when this key was taken from the content itself rather than from a chunk's name. */
    public boolean isContentDerived() {
        return basis == Basis.CONTENT;
    }

    /** Short rendering for notes and audits. */
    public String describe() {
        String shortPrint = fingerprint.length() <= 12 ? fingerprint
                : fingerprint.substring(0, 12);
        return profile + "/" + basis.name().toLowerCase() + ":" + shortPrint;
    }

    private static String normalise(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }
}
