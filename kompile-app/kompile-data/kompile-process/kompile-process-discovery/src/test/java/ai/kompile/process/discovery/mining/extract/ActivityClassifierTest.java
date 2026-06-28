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

package ai.kompile.process.discovery.mining.extract;

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Activity-label resolution for the default {@link ActivityClassifier#byEntityType()} classifier and
 * its {@link ActivityClassifier#displayLabel(String)} helper. Covers the precedence chain
 * (entity_type → sheetName → NodeLevel), junk-slug prettification, and — critically — that already
 * human-readable labels (clean UPPERCASE types, spaced sheet names) are NOT mangled.
 */
class ActivityClassifierTest {

    private GraphNode node(String metadataJson, NodeLevel level) {
        return GraphNode.builder()
                .nodeId("n").nodeType(level).externalId("n").title("n")
                .metadataJson(metadataJson)
                .build();
    }

    // ── byEntityType() precedence ─────────────────────────────────────────────────

    @Test
    void cleanEntityTypeKeptVerbatim() {
        assertEquals("INVOICE",
                ActivityClassifier.byEntityType().activityOf(node("{\"entity_type\":\"INVOICE\"}", NodeLevel.ENTITY)));
    }

    @Test
    void snakeCaseJunkEntityTypePrettified() {
        // The node-id-shaped junk that used to leak onto activities/edges.
        assertEquals("Entity Number",
                ActivityClassifier.byEntityType()
                        .activityOf(node("{\"entity_type\":\"entity_entity_number\"}", NodeLevel.ENTITY)));
    }

    @Test
    void fallsBackToSheetNameWhenNoBusinessEntityType() {
        // Spreadsheet cell: structural subtype, no business entity_type, but a meaningful sheet.
        String md = "{\"entity_subtype\":\"cell\",\"sheetName\":\"Group P&L\"}";
        assertEquals("Group P&L", ActivityClassifier.byEntityType().activityOf(node(md, NodeLevel.ENTITY)));
    }

    @Test
    void sheetNameWithSpacesIsNotMangled() {
        // Regression: displayLabel must not lower-case / re-title a human sheet name.
        assertEquals("Channel taxonomy",
                ActivityClassifier.byEntityType().activityOf(node("{\"sheetName\":\"Channel taxonomy\"}", NodeLevel.ENTITY)));
    }

    @Test
    void entityTypeWinsOverSheetName() {
        String md = "{\"entity_type\":\"METRIC\",\"sheetName\":\"Group P&L\"}";
        assertEquals("METRIC", ActivityClassifier.byEntityType().activityOf(node(md, NodeLevel.ENTITY)));
    }

    @Test
    void fallsBackToNodeLevelWhenNoTypeOrSheet() {
        assertEquals("DOCUMENT", ActivityClassifier.byEntityType().activityOf(node("{}", NodeLevel.DOCUMENT)));
    }

    @Test
    void structuralTableTypeIsDropped() {
        assertNull(ActivityClassifier.byEntityType().activityOf(node("{\"entity_type\":\"TABLE\"}", NodeLevel.ENTITY)));
    }

    @Test
    void nullNodeYieldsUnknown() {
        assertEquals("UNKNOWN", ActivityClassifier.byEntityType().activityOf(null));
    }

    // ── displayLabel() directly ───────────────────────────────────────────────────

    @Test
    void displayLabelPrettifiesSnakeCaseButPreservesHumanLabels() {
        assertEquals("Entity Number", ActivityClassifier.displayLabel("entity_entity_number"));
        assertEquals("Purchase Order", ActivityClassifier.displayLabel("PURCHASE_ORDER"));
        assertEquals("Group P&L", ActivityClassifier.displayLabel("Group P&L")); // spaces preserved
        assertEquals("INVOICE", ActivityClassifier.displayLabel("INVOICE"));     // clean type untouched
        assertEquals("UNKNOWN", ActivityClassifier.displayLabel(" "));
    }
}
