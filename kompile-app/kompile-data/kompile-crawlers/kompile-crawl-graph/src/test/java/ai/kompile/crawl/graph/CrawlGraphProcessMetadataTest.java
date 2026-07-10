/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.crawl.graph;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlGraphProcessMetadataTest {

    @Test
    void entityMetadataEmitsCamelAndSnakeTypeAliases() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("revision", "v3");

        CrawlGraphProcessMetadata.normalizeEntityMetadata(metadata, "EMAIL_MESSAGE", "gmail", "mail/1", "msg-1");

        assertEquals("EMAIL_MESSAGE", metadata.get("entity_type"));
        assertEquals("EMAIL_MESSAGE", metadata.get("entityType"));
        assertEquals("gmail", metadata.get("sourceSystem"));
        assertEquals("mail/1", metadata.get("sourcePath"));
        assertEquals("msg-1", metadata.get("sourceSystemEventId"));
        assertEquals("v3", metadata.get("artifactVersion"));
    }

    @Test
    void relationMetadataNormalizesProcessInstanceEventRelationAndActorKeys() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("messageId", "<m-42@example.com>");
        metadata.put("threadId", "thread-42");
        metadata.put("personName", "Alice Analyst");

        CrawlGraphProcessMetadata.normalizeRelationMetadata(metadata, "job-1", "mail/42", "email_graph",
                "person:alice", "email-msg:42", "SENT_BY");

        assertEquals("SENT_BY", metadata.get("relationType"));
        assertEquals("SENT_BY", metadata.get("canonicalRelationType"));
        assertEquals("thread-42", metadata.get("caseId"));
        assertEquals("thread-42", metadata.get("processInstanceId"));
        assertEquals("<m-42@example.com>", metadata.get("sourceSystemEventId"));
        assertEquals("Alice Analyst", metadata.get("actorId"));
        assertEquals("email_graph", metadata.get("sourceSystem"));
    }

    @Test
    void semanticRelationJsonCarriesCurrentMessageAndReferencedMessageSeparately() {
        GraphPersistenceHelper helper = new GraphPersistenceHelper();
        String json = helper.semanticRelationMetadataJson("job-1", "mail/42", "email_graph",
                "email-msg:current", "email-msg:prior", "REPLIED_TO", "reply", 1.0,
                helper.metadataProperties(
                        "messageId", "<current@example.com>",
                        "targetMessageId", "<prior@example.com>",
                        "threadId", "thread-42"));

        assertTrue(json.contains("\"sourceSystemEventId\":\"<current@example.com>\""));
        assertTrue(json.contains("\"targetMessageId\":\"<prior@example.com>\""));
        assertTrue(json.contains("\"processInstanceId\":\"thread-42\""));
        assertTrue(json.contains("\"canonicalRelationType\":\"REPLIED_TO\""));
    }

    @Test
    void relationMetadataInfersGenericProcessHintsFromDescriptions() {
        Map<String, Object> control = new LinkedHashMap<>();
        control.put("description", "SOX control validates the forecast package before close");

        CrawlGraphProcessMetadata.normalizeRelationMetadata(control, "job-1", "controls/4", "graph_crawl",
                "control:C-04", "activity:forecast-review", "CONTROLS");

        assertEquals("VALIDATE", control.get("actionType"));
        assertEquals("VALIDATION", control.get("actionCategory"));
        assertEquals("control:C-04", control.get("controlId"));

        Map<String, Object> escalation = new LinkedHashMap<>();
        escalation.put("relation.description",
                "Escalate overdue approval when the SLA is breached after 2 hours; reject and rework records");

        CrawlGraphProcessMetadata.normalizeRelationMetadata(escalation, "job-1", "mail/42", "email_graph",
                "activity:close-review", "person:controller", "ESCALATED_TO");

        assertEquals("ESCALATE", escalation.get("actionType"));
        assertEquals("ESCALATION", escalation.get("actionCategory"));
        assertEquals("person:controller", escalation.get("escalationTarget"));
        assertEquals("ESCALATED_TO", escalation.get("approvalPolicy"));
        assertEquals(7200L, escalation.get("slaSeconds"));
        assertEquals(true, escalation.get("slaBreached"));
        assertEquals("OVERDUE", escalation.get("status"));
        assertEquals("REJECT", escalation.get("remediationAction"));
    }
}
