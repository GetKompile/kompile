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
package ai.kompile.core.language.translation;

import java.util.List;

/** Immutable outcome of one translation request, including segment diagnostics. */
public record TranslationResult(
        Status status,
        String outputText,
        String sourceId,
        String sourceLanguage,
        String targetLanguage,
        String provider,
        String modelId,
        String reason,
        List<SegmentResult> segments,
        String fingerprint) {

    public TranslationResult {
        if (status == null) {
            throw new IllegalArgumentException("translation result status is required");
        }
        reason = bounded(reason);
        segments = segments == null ? List.of() : List.copyOf(segments);
        fingerprint = bounded(fingerprint);
    }

    /** Overall task status. */
    public enum Status {
        TRANSLATED,
        SKIPPED,
        PARTIAL,
        FAILED
    }

    /** Status and source offsets for one contiguous source segment. */
    public record SegmentResult(int index, int sourceStart, int sourceEnd,
                                SegmentStatus status, String diagnostic) {
        public SegmentResult {
            if (index < 0) {
                throw new IllegalArgumentException("segment index must be nonnegative");
            }
            if (sourceStart < 0 || sourceEnd < sourceStart) {
                throw new IllegalArgumentException("segment source offsets are invalid");
            }
            if (status == null) {
                throw new IllegalArgumentException("segment status is required");
            }
            diagnostic = bounded(diagnostic);
        }
    }

    /** Per-segment outcome; FAILED may have its source retained by the failure policy. */
    public enum SegmentStatus {
        TRANSLATED,
        SKIPPED,
        FAILED
    }

    private static String bounded(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 240) {
            return trimmed;
        }
        return trimmed.substring(0, 237) + "...";
    }
}
