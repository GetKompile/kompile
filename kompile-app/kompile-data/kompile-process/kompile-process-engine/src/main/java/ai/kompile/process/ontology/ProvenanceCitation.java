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

package ai.kompile.process.ontology;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Links an ontology claim back to its evidence source.
 * The same provenance model used in the FP&amp;A evidence graph.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProvenanceCitation {

    private SourceType sourceType;
    /** Source identifier, e.g., "FP&A_Close_SOP_v3.2.docx". */
    private String sourceId;
    /** Location within the source, e.g., "§3.2", "cell D60", "0:42:13", "turn 23". */
    private String location;
    /** Verbatim quote or summary of the extracted claim. */
    private String extractedText;
    private double confidence;
    private Instant extractedAt;
    /** SHA-256 of the source document at extraction time for immutability verification. */
    private String contentHash;
    /** Knowledge graph node ID of the source document, if indexed in the graph. */
    private String graphNodeId;
}
