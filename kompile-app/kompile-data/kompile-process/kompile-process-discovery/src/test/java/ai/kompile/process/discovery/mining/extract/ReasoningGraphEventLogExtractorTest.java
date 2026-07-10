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

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.process.discovery.mining.log.EventLog;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningGraphEventLogExtractorTest {

    @Test
    void extractsRelationEventsFromReasoningGraphWithoutMergingOnSharedActor() {
        Instant base = Instant.parse("2026-06-30T09:00:00Z");
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(entity("email:amer", "EMAIL_MESSAGE"));
        graph.addEntity(entity("spreadsheet:amer", "SPREADSHEET"));
        graph.addEntity(entity("email:emea", "EMAIL_MESSAGE"));
        graph.addEntity(entity("spreadsheet:emea", "SPREADSHEET"));
        graph.addEntity(entity("person:mei", "PERSON"));
        graph.addRelation(relation("r1", "email:amer", "person:mei", "SENT_TO", base));
        graph.addRelation(relation("r2", "email:amer", "spreadsheet:amer", "HAS_ATTACHMENT", base.plusSeconds(60)));
        graph.addRelation(relation("r3", "email:emea", "person:mei", "SENT_TO", base.plusSeconds(600)));
        graph.addRelation(relation("r4", "email:emea", "spreadsheet:emea", "HAS_ATTACHMENT", base.plusSeconds(660)));

        EventLog log = ReasoningGraphEventLogExtractor.relationEvents().extract(graph);

        assertEquals(2, log.size(), "shared recipients are resources, not case-correlation edges");
        assertEquals(Set.of("Email Message Sent To Person", "Email Message Has Attachment Spreadsheet"),
                log.activityNames());
        assertTrue(log.traces().stream().allMatch(trace -> trace.activitySequence().equals(List.of(
                "Email Message Sent To Person", "Email Message Has Attachment Spreadsheet"))));
    }

    @Test
    void prefersSpecificOntologyTypeMembershipOverGenericEntityType() {
        Instant base = Instant.parse("2026-06-30T09:00:00Z");
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(GraphEntity.builder("node:email")
                .type("ENTITY")
                .label("email")
                .attribute("ontology.inferredTypes", List.of("EMAIL_MESSAGE"))
                .build());
        graph.addEntity(GraphEntity.builder("node:person")
                .type("ENTITY")
                .label("person")
                .attribute("ontology.inferredTypes", List.of("PERSON"))
                .build());
        graph.addRelation(relation("r1", "node:email", "node:person", "SENT_BY", base));

        EventLog log = ReasoningGraphEventLogExtractor.relationEvents().extract(graph);

        assertEquals(Set.of("Email Message Sent By Person"), log.activityNames());
        assertEquals("EMAIL_MESSAGE", log.traces().get(0).events().get(0).attributes().get("sourceType"));
        assertEquals("PERSON", log.traces().get(0).events().get(0).attributes().get("targetType"));
    }

    @Test
    void usesCrawlerEntityTypeAndCanonicalRelationMetadataForActivityResolution() {
        Instant base = Instant.parse("2026-06-30T09:00:00Z");
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(GraphEntity.builder("node:email")
                .type("ENTITY")
                .label("email")
                .attribute("entity_type", "EMAIL_MESSAGE")
                .build());
        graph.addEntity(GraphEntity.builder("node:person")
                .type("ENTITY")
                .label("person")
                .attribute("entityType", "PERSON")
                .build());
        graph.addRelation(GraphRelation.builder("r1", "node:email", "node:person")
                .type("MENTIONS")
                .timestamp(base)
                .attribute("canonicalRelationType", "SENT_BY")
                .attribute("originalRelationType", "MENTIONS")
                .build());

        EventLog log = ReasoningGraphEventLogExtractor.relationEvents().extract(graph);
        Map<String, Object> attributes = log.traces().get(0).events().get(0).attributes();

        assertEquals(Set.of("Email Message Sent By Person"), log.activityNames());
        assertEquals("SENT_BY", attributes.get("relationType"));
        assertEquals("MENTIONS", attributes.get("rawRelationType"));
        assertEquals("EMAIL_MESSAGE", attributes.get("sourceType"));
        assertEquals("PERSON", attributes.get("targetType"));
        assertEquals("SENT_BY", attributes.get("canonicalRelationType"));
    }

    @Test
    void explicitCaseMetadataOverridesConnectedComponentCorrelation() {
        Instant base = Instant.parse("2026-06-30T09:00:00Z");
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(entity("email:1", "EMAIL_MESSAGE"));
        graph.addEntity(entity("workbook:1", "SPREADSHEET"));
        graph.addEntity(entity("email:2", "EMAIL_MESSAGE"));
        graph.addEntity(entity("workbook:2", "SPREADSHEET"));
        graph.addRelation(GraphRelation.builder("r1", "email:1", "workbook:1")
                .type("HAS_ATTACHMENT")
                .timestamp(base)
                .attribute("caseId", "thread:shared")
                .build());
        graph.addRelation(GraphRelation.builder("r2", "email:2", "workbook:2")
                .type("HAS_ATTACHMENT")
                .timestamp(base.plusSeconds(60))
                .attribute("caseId", "thread:shared")
                .build());

        EventLog log = ReasoningGraphEventLogExtractor.relationEvents().extract(graph);

        assertEquals(1, log.size());
        assertEquals("thread:shared", log.traces().get(0).caseId());
        assertEquals(2, log.traces().get(0).size());
    }

    @Test
    void explicitEventMetadataOverridesRelationIdAndPreservesSourceSystemFields() {
        Instant base = Instant.parse("2026-06-30T09:00:00Z");
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(entity("email:1", "EMAIL_MESSAGE"));
        graph.addEntity(entity("person:1", "PERSON"));
        graph.addRelation(GraphRelation.builder("r-fallback", "email:1", "person:1")
                .type("SENT_TO")
                .timestamp(base)
                .attribute("caseId", "case:mail")
                .attribute("sourceSystemEventId", "outlook:evt-42")
                .attribute("sourceSystem", "outlook")
                .attribute("artifactVersion", "v3")
                .attribute("userId", "u-123")
                .build());

        EventLog log = ReasoningGraphEventLogExtractor.relationEvents().extract(graph);
        Map<String, Object> attributes = log.traces().get(0).events().get(0).attributes();

        assertEquals("outlook:evt-42", log.traces().get(0).events().get(0).graphNodeId());
        assertEquals("outlook:evt-42", attributes.get("sourceSystemEventId"));
        assertEquals("outlook", attributes.get("sourceSystem"));
        assertEquals("v3", attributes.get("artifactVersion"));
        assertEquals("u-123", attributes.get("userId"));
    }

    @Test
    void preservesPolicyAndEndpointAttributesOnRelationEvents() {
        Instant base = Instant.parse("2026-06-30T09:00:00Z");
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(GraphEntity.builder("triage:currency")
                .type("VARIANCE_TRIAGE")
                .label("Currency drift")
                .attribute("goldPattern", "CurrencySymbolDrift")
                .attribute("routingPolicy", "AUTO-CORRECT >= 0.85; J. Park below threshold")
                .build());
        graph.addEntity(GraphEntity.builder("person:j_park")
                .type("PERSON")
                .label("J. Park")
                .attribute("role", "Forecast gate owner")
                .build());
        graph.addRelation(GraphRelation.builder("r-policy", "triage:currency", "person:j_park")
                .type("ESCALATED_TO")
                .timestamp(base)
                .attribute("caseId", "case:triage")
                .attribute("confidenceThreshold", 0.85)
                .attribute("actionType", "ESCALATE")
                .attribute("routingPolicy", "below threshold route")
                .attribute("normalizedRelation", true)
                .build());

        EventLog log = ReasoningGraphEventLogExtractor.relationEvents().extract(graph);
        Map<String, Object> attributes = log.traces().get(0).events().get(0).attributes();

        assertEquals(1, log.size());
        assertEquals("case:triage", log.traces().get(0).caseId());
        assertEquals("below threshold route", attributes.get("routingPolicy"));
        assertEquals("below threshold route", attributes.get("relation.routingPolicy"));
        assertEquals(0.85, attributes.get("confidenceThreshold"));
        assertEquals("ESCALATE", attributes.get("actionType"));
        assertEquals(true, attributes.get("normalizedRelation"));
        assertEquals("CurrencySymbolDrift", attributes.get("source.goldPattern"));
        assertEquals("Forecast gate owner", attributes.get("target.role"));
    }

    private static GraphEntity entity(String id, String type) {
        return GraphEntity.builder(id)
                .type(type)
                .label(id)
                .build();
    }

    private static GraphRelation relation(String id, String sourceId, String targetId, String type, Instant when) {
        return GraphRelation.builder(id, sourceId, targetId)
                .type(type)
                .timestamp(when)
                .attributes(Map.of("source", "test"))
                .build();
    }
}
