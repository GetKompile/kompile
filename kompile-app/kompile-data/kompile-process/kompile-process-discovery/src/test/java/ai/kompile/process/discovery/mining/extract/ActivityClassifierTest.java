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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Activity-label resolution for the default {@link ActivityClassifier#byEntityType()} classifier
 * and its {@link ActivityClassifier#displayLabel(String)} helper. Covers the precedence chain
 * (entity_type → sheetName → NodeLevel), the normalization that keeps real crawl output coherent
 * (case variants of one type merge into one activity; machine labels render Title-Cased with
 * acronyms kept; human-authored labels are NOT mangled), and the projection rules that keep
 * actors and scaffolding out of the mined process.
 */
class ActivityClassifierTest {

    private GraphNode node(String metadataJson, NodeLevel level) {
        return GraphNode.builder()
                .nodeId("n").nodeType(level).externalId("n").title("n")
                .metadataJson(metadataJson)
                .build();
    }

    private GraphNode entity(String entityType) {
        return node("{\"entity_type\":\"" + entityType + "\"}", NodeLevel.ENTITY);
    }

    // ── byEntityType() precedence ─────────────────────────────────────────────────

    @Test
    void cleanEntityTypeRendersTitleCased() {
        assertEquals("Invoice", ActivityClassifier.byEntityType().activityOf(entity("INVOICE")),
                "machine-shaped types render Title-Cased for one consistent suggestion style");
    }

    @Test
    void snakeCaseJunkEntityTypePrettified() {
        // The node-id-shaped junk that used to leak onto activities/edges.
        assertEquals("Entity Number",
                ActivityClassifier.byEntityType().activityOf(entity("entity_entity_number")));
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
                ActivityClassifier.byEntityType()
                        .activityOf(node("{\"sheetName\":\"Channel taxonomy\"}", NodeLevel.ENTITY)));
    }

    @Test
    void entityTypeWinsOverSheetName() {
        String md = "{\"entity_type\":\"METRIC\",\"sheetName\":\"Group P&L\"}";
        assertEquals("Metric", ActivityClassifier.byEntityType().activityOf(node(md, NodeLevel.ENTITY)));
    }

    @Test
    void fallsBackToNodeLevelWhenNoTypeOrSheet() {
        assertEquals("Document", ActivityClassifier.byEntityType().activityOf(node("{}", NodeLevel.DOCUMENT)));
    }

    @Test
    void structuralTableTypeIsDropped() {
        assertNull(ActivityClassifier.byEntityType().activityOf(entity("TABLE")));
    }

    @Test
    void nullNodeYieldsUnknown() {
        assertEquals("UNKNOWN", ActivityClassifier.byEntityType().activityOf(null));
    }

    // ── actor/resource + scaffold projection rules (real crawl shapes) ───────────

    @Test
    void actorsAreNeverSteps() {
        ActivityClassifier classifier = ActivityClassifier.byEntityType();
        assertNull(classifier.activityOf(entity("PERSON")), "actors are resources, not steps");
        assertNull(classifier.activityOf(entity("ORGANIZATION")));
        assertNull(classifier.activityOf(entity("google_person")), "actor exclusion is case-insensitive");
        assertEquals("Email Message", classifier.activityOf(entity("EMAIL_MESSAGE")),
                "communication carriers ARE steps (they show the flow) — only clustering ignores them");
    }

    @Test
    void communicationScaffoldLabelCheck_matchesPostDisplayLabels() {
        assertTrue(ActivityClassifier.isCommunicationScaffoldLabel("Email Message"));
        assertTrue(ActivityClassifier.isCommunicationScaffoldLabel("Attachment"));
        assertTrue(ActivityClassifier.isCommunicationScaffoldLabel("ATTACHMENT"));
        assertFalse(ActivityClassifier.isCommunicationScaffoldLabel("Invoice"));
        assertFalse(ActivityClassifier.isCommunicationScaffoldLabel(null));
    }

    // ── displayLabel() directly ───────────────────────────────────────────────────

    @Test
    void caseVariantsOfOneType_mergeIntoOneActivity() {
        // Different extractors casing the same type must not fragment the mined process.
        assertEquals("Invoice", ActivityClassifier.displayLabel("INVOICE"));
        assertEquals("Invoice", ActivityClassifier.displayLabel("invoice"));
        assertEquals("Invoice", ActivityClassifier.displayLabel("Invoice"));
        assertEquals("Purchase Request", ActivityClassifier.displayLabel("PURCHASE_REQUEST"));
        assertEquals("Purchase Request", ActivityClassifier.displayLabel("purchase_request"));
        assertEquals("Purchase Order", ActivityClassifier.displayLabel("PURCHASE_ORDER"));
    }

    @Test
    void machineLabels_titleCase_withAcronymsPreserved() {
        assertEquals("Approval", ActivityClassifier.displayLabel("APPROVAL"));
        assertEquals("PO", ActivityClassifier.displayLabel("PO"));
        assertEquals("Purchase PO", ActivityClassifier.displayLabel("PURCHASE_PO"));
        assertEquals("Purchase PO", ActivityClassifier.displayLabel("purchase_po"),
                "acronym handling must be case-variant stable so labels merge");
        assertEquals("Job Application", ActivityClassifier.displayLabel("JOB_APPLICATION"),
                "3-char tokens are words, not acronyms");
        assertEquals("Entity Invoice Number", ActivityClassifier.displayLabel("entity_invoice_number"));
    }

    @Test
    void humanAuthoredLabels_passThroughUnchanged() {
        assertEquals("Group P&L", ActivityClassifier.displayLabel("Group P&L")); // spaces + mixed case preserved
        assertEquals("Email Message", ActivityClassifier.displayLabel("Email Message"));
        assertEquals("UNKNOWN", ActivityClassifier.displayLabel(null));
        assertEquals("UNKNOWN", ActivityClassifier.displayLabel(" "));
    }
}
