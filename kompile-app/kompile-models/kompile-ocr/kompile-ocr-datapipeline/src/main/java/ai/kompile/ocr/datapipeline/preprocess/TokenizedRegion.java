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

package ai.kompile.ocr.datapipeline.preprocess;

import java.util.List;

/**
 * Represents a tokenized text region with aligned bounding boxes.
 * Used for LayoutLM-style models that require token-level spatial information.
 *
 * @param tokens List of token strings
 * @param tokenIds List of token IDs (vocabulary indices)
 * @param bboxes List of bounding boxes, one per token [x1, y1, x2, y2]
 * @param originalText Original text before tokenization
 * @param confidence OCR confidence score (0-1)
 */
public record TokenizedRegion(
        List<String> tokens,
        List<Integer> tokenIds,
        List<int[]> bboxes,
        String originalText,
        double confidence
) {
    /**
     * Creates a region from text and bounding boxes (tokens added later).
     */
    public static TokenizedRegion fromText(String text, List<int[]> bboxes, double confidence) {
        return new TokenizedRegion(null, null, bboxes, text, confidence);
    }

    /**
     * Creates a fully tokenized region.
     */
    public static TokenizedRegion tokenized(List<String> tokens, List<Integer> tokenIds,
                                             List<int[]> bboxes, String originalText) {
        return new TokenizedRegion(tokens, tokenIds, bboxes, originalText, 1.0);
    }

    /**
     * Gets the number of tokens.
     */
    public int tokenCount() {
        return tokens != null ? tokens.size() : 0;
    }

    /**
     * Checks if this region has been tokenized.
     */
    public boolean isTokenized() {
        return tokenIds != null && !tokenIds.isEmpty();
    }
}
