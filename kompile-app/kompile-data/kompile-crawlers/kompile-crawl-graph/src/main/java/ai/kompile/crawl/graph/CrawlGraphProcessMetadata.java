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

import ai.kompile.core.graphrag.GraphConstants;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Normalizes crawler graph metadata into the generic process-mining vocabulary. */
final class CrawlGraphProcessMetadata {

    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "\\b([0-9]+(?:\\.[0-9]+)?)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)\\b",
            Pattern.CASE_INSENSITIVE);

    private CrawlGraphProcessMetadata() {
    }

    static void normalizeEntityMetadata(Map<String, Object> metadata,
                                        String entityType,
                                        String sourceSystem,
                                        String sourcePath,
                                        String sourceEventId) {
        if (metadata == null) {
            return;
        }
        String canonicalType = firstText(entityType,
                text(firstValue(metadata, "entityType", "entity_type", "type", "semanticType")));
        if (hasText(canonicalType)) {
            putIfAbsent(metadata, "entityType", canonicalType);
            putIfAbsent(metadata, "entity_type", canonicalType);
        }
        normalizeSource(metadata, sourceSystem, sourcePath);
        normalizeEventId(metadata, sourceSystem, sourceEventId, null, null, canonicalType, sourcePath);
        normalizeArtifactVersion(metadata);
    }

    static void normalizeRelationMetadata(Map<String, Object> metadata,
                                          String jobId,
                                          String sourcePath,
                                          String extractionMethod,
                                          String sourceEntityId,
                                          String targetEntityId,
                                          String relationLabel) {
        if (metadata == null) {
            return;
        }
        String canonicalRelation = firstText(relationLabel, text(firstValue(metadata,
                "canonicalRelationType", "canonical_relation_type",
                "normalizedRelationType", "normalized_relation_type",
                "relationType", "relation_type", "relationshipType", "semanticType")));
        if (hasText(canonicalRelation)) {
            putIfAbsent(metadata, "relationType", canonicalRelation);
            putIfAbsent(metadata, "relation_type", canonicalRelation);
            putIfAbsent(metadata, "canonicalRelationType", canonicalRelation);
            putIfAbsent(metadata, "canonical_relation_type", canonicalRelation);
            putIfAbsent(metadata, "normalizedRelationType", canonicalRelation);
            putIfAbsent(metadata, "normalized_relation_type", canonicalRelation);
            putIfAbsent(metadata, "relationshipType", canonicalRelation);
            putIfAbsent(metadata, "semanticType", canonicalRelation);
        }

        normalizeSource(metadata, firstText(
                text(firstValue(metadata, "sourceSystem", "source_system", GraphConstants.META_SOURCE, "system")),
                extractionMethod,
                jobId), sourcePath);
        normalizeProcessInstance(metadata, sourcePath);
        normalizeEventId(metadata, extractionMethod, null, sourceEntityId, targetEntityId, canonicalRelation, sourcePath);
        normalizeActor(metadata, canonicalRelation, sourceEntityId, targetEntityId);
        inferProcessHints(metadata, canonicalRelation, sourceEntityId, targetEntityId);
        normalizeArtifactVersion(metadata);
    }

    private static void normalizeSource(Map<String, Object> metadata, String sourceSystem, String sourcePath) {
        String source = firstText(
                text(firstValue(metadata, "sourceSystem", "source_system", GraphConstants.META_SOURCE, "system")),
                sourceSystem);
        if (hasText(source)) {
            putIfAbsent(metadata, "sourceSystem", source);
            putIfAbsent(metadata, "source_system", source);
        }
        String path = firstText(text(firstValue(metadata, GraphConstants.META_SOURCE_PATH, "sourcePath", "source_path")),
                sourcePath);
        if (hasText(path)) {
            putIfAbsent(metadata, GraphConstants.META_SOURCE_PATH, path);
            putIfAbsent(metadata, "sourcePath", path);
            putIfAbsent(metadata, "source_path", path);
        }
    }

    private static void normalizeProcessInstance(Map<String, Object> metadata, String sourcePath) {
        String caseId = firstText(text(firstValue(metadata,
                "processInstanceId", "process_instance_id",
                "businessProcessInstanceId", "business_process_instance_id",
                "caseId", "case_id", "traceId", "trace_id",
                "threadId", "thread_id", "messageThreadId", "message_thread_id",
                "conversationId", "conversation_id", "workflowInstanceId", "workflow_instance_id",
                "email.threadId", "gmail.threadId", "email.conversationId", "gmail.conversationId")));
        if (!hasText(caseId)) {
            caseId = firstText(text(firstValue(metadata, "messageId", "email.messageId", "gmail.messageId")),
                    sourcePath == null ? null : "source:" + sourcePath);
        }
        if (hasText(caseId)) {
            putIfAbsent(metadata, "caseId", caseId);
            putIfAbsent(metadata, "case_id", caseId);
            putIfAbsent(metadata, "processInstanceId", caseId);
            putIfAbsent(metadata, "process_instance_id", caseId);
            putIfAbsent(metadata, "businessProcessInstanceId", caseId);
            putIfAbsent(metadata, "business_process_instance_id", caseId);
        }
    }

    private static void normalizeEventId(Map<String, Object> metadata,
                                         String sourceSystem,
                                         String explicitEventId,
                                         String sourceEntityId,
                                         String targetEntityId,
                                         String relationOrEntityType,
                                         String sourcePath) {
        String eventId = firstText(explicitEventId, text(firstValue(metadata,
                "sourceSystemEventId", "source_system_event_id",
                "eventId", "event_id", "activityInstanceId", "activity_instance_id",
                "messageId", "email.messageId", "gmail.messageId",
                "sourceDocumentId", "documentId", "docId")));
        if (!hasText(eventId)) {
            eventId = deterministicId(sourceSystem, sourcePath, sourceEntityId, relationOrEntityType, targetEntityId);
        }
        if (hasText(eventId)) {
            putIfAbsent(metadata, "sourceSystemEventId", eventId);
            putIfAbsent(metadata, "source_system_event_id", eventId);
            putIfAbsent(metadata, "eventId", eventId);
            putIfAbsent(metadata, "event_id", eventId);
            putIfAbsent(metadata, "activityInstanceId", eventId);
            putIfAbsent(metadata, "activity_instance_id", eventId);
        }
    }

    private static void normalizeActor(Map<String, Object> metadata,
                                       String canonicalRelation,
                                       String sourceEntityId,
                                       String targetEntityId) {
        String actor = firstText(text(firstValue(metadata,
                "actorId", "actor_id", "actor", "userId", "user_id", "user",
                "sender", "from", "personName", "email", "approver", "approvedBy",
                "source.actorId", "source.userId", "target.actorId", "target.userId")));
        if (!hasText(actor) && "SENT_BY".equalsIgnoreCase(canonicalRelation)) {
            actor = sourceEntityId;
        }
        if (!hasText(actor) && canonicalRelation != null && canonicalRelation.toUpperCase(Locale.ROOT).contains("APPROV")) {
            actor = firstText(targetEntityId, sourceEntityId);
        }
        if (hasText(actor)) {
            putIfAbsent(metadata, "actorId", actor);
            putIfAbsent(metadata, "actor_id", actor);
            putIfAbsent(metadata, "userId", actor);
            putIfAbsent(metadata, "user_id", actor);
        }
    }

    private static void normalizeArtifactVersion(Map<String, Object> metadata) {
        String version = firstText(text(firstValue(metadata,
                "artifactVersion", "artifact_version", "version", "documentVersion",
                "document_version", "docVersion", "doc_version", "revision", "etag", "sourceVersion")));
        if (hasText(version)) {
            putIfAbsent(metadata, "artifactVersion", version);
            putIfAbsent(metadata, "artifact_version", version);
        }
    }

    private static void inferProcessHints(Map<String, Object> metadata,
                                          String canonicalRelation,
                                          String sourceEntityId,
                                          String targetEntityId) {
        String hintText = processHintText(metadata, canonicalRelation);
        String normalized = normalizeHintText(hintText);
        if (!hasText(normalized)) {
            return;
        }

        if (containsAny(normalized, "CONTROL", "VALIDAT", "VERIFY", "CHECK", "RECONCIL")) {
            putIfAbsent(metadata, "actionType", "VALIDATE");
            putIfAbsent(metadata, "actionCategory", "VALIDATION");
            if (containsAny(normalized, "CONTROL")) {
                String controlId = firstText(text(firstValue(metadata, "controlId", "control", "controls")),
                        sourceEntityId, targetEntityId);
                if (hasText(controlId)) {
                    putIfAbsent(metadata, "controlId", controlId);
                }
            }
        }
        if (containsAny(normalized, "ESCALAT")) {
            putIfAbsent(metadata, "actionType", "ESCALATE");
            putIfAbsent(metadata, "actionCategory", "ESCALATION");
            putIfAbsent(metadata, "escalationTarget", firstText(targetEntityId, sourceEntityId));
        }
        if (containsAny(normalized, "APPROV", "SIGN OFF", "SIGNOFF")) {
            putIfAbsent(metadata, "actionType", "APPROVE");
            putIfAbsent(metadata, "actionCategory", "APPROVAL");
            putIfAbsent(metadata, "approvalPolicy", firstText(canonicalRelation, "approval"));
            putIfAbsent(metadata, "approver", firstText(targetEntityId, sourceEntityId));
        }
        if (containsAny(normalized, "ROUT", "ASSIGN", "DISPATCH")) {
            putIfAbsent(metadata, "actionType", "ROUTE");
            putIfAbsent(metadata, "actionCategory", "ROUTING");
            putIfAbsent(metadata, "routingPolicy", firstText(canonicalRelation, "routing"));
        }

        if (containsAnyWord(normalized, "SLA", "DEADLINE", "DUE", "WITHIN")
                || containsAny(normalized, "SERVICE LEVEL")) {
            putIfAbsent(metadata, "sla", firstText(text(firstValue(metadata,
                    "sla", "slaPolicy", "deadline", "dueDate", "dueBy")), canonicalRelation, "deadline"));
            Long seconds = inferDurationSeconds(hintText);
            if (seconds != null) {
                putIfAbsent(metadata, "slaSeconds", seconds);
            }
        }
        if (containsAny(normalized, "BREACH", "OVERDUE", "MISSED DEADLINE", "PAST DUE")
                || containsAnyWord(normalized, "LATE", "DELAYED")) {
            putIfAbsent(metadata, "slaBreached", true);
            putIfAbsent(metadata, "status", "OVERDUE");
        } else if (containsAny(normalized, "BLOCK", "ON HOLD", "WAIT", "FAIL", "REJECT")) {
            putIfAbsent(metadata, "status", "BLOCKED");
        } else if (containsAnyWord(normalized, "READY", "PASSED", "SUCCESS", "COMPLETE", "COMPLETED", "DONE")
                || containsAny(normalized, "APPROVED")) {
            putIfAbsent(metadata, "status", "READY");
        }

        String remediationAction = remediationAction(normalized);
        if (remediationAction != null) {
            putIfAbsent(metadata, "remediationAction", remediationAction);
            putIfAbsent(metadata, "actionType", remediationAction);
            putIfAbsent(metadata, "actionCategory", "REMEDIATION");
        }
    }

    private static String processHintText(Map<String, Object> metadata, String canonicalRelation) {
        StringBuilder out = new StringBuilder();
        appendHintText(out, canonicalRelation);
        for (String key : new String[]{
                "relationType", "relationshipType", "canonicalRelationType", "normalizedRelationType",
                "semanticType", "label", "name", "title", "description", "summary",
                "semanticContext", "context", "relationshipDescription", "relationDescription",
                "edgeDescription", "relation.label", "relation.name", "relation.description",
                "relation.semanticContext", "source.description", "target.description"}) {
            appendHintText(out, metadata.get(key));
        }
        return out.toString();
    }

    private static void appendHintText(StringBuilder out, Object raw) {
        String value = text(raw);
        if (hasText(value)) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(value);
        }
    }

    private static String normalizeHintText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim().replaceAll(" +", " ");
    }

    private static boolean containsAny(String text, String... fragments) {
        for (String fragment : fragments) {
            if (hasText(fragment) && text.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAnyWord(String text, String... words) {
        String padded = " " + text + " ";
        for (String word : words) {
            if (hasText(word) && padded.contains(" " + word + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String remediationAction(String normalized) {
        if (containsAny(normalized, "QUARANTINE")) {
            return "QUARANTINE";
        }
        if (containsAny(normalized, "ROLLBACK", "ROLL BACK")) {
            return "ROLLBACK";
        }
        if (containsAny(normalized, "REJECT")) {
            return "REJECT";
        }
        if (containsAny(normalized, "REWORK", "RESUBMIT")) {
            return "REWORK";
        }
        if (containsAny(normalized, "REMEDIAT", "CORRECT", "FIX")) {
            return "REMEDIATE";
        }
        return null;
    }

    private static Long inferDurationSeconds(String raw) {
        if (!hasText(raw)) {
            return null;
        }
        Matcher matcher = DURATION_PATTERN.matcher(raw);
        if (!matcher.find()) {
            return null;
        }
        double amount;
        try {
            amount = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        long multiplier = durationMultiplier(matcher.group(2));
        return Math.max(1L, Math.round(amount * multiplier));
    }

    private static long durationMultiplier(String unit) {
        String normalized = unit == null ? "" : unit.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("sec")) {
            return 1L;
        }
        if (normalized.startsWith("min")) {
            return 60L;
        }
        if (normalized.startsWith("hour") || normalized.startsWith("hr")) {
            return 60L * 60L;
        }
        if (normalized.startsWith("day")) {
            return 24L * 60L * 60L;
        }
        return 1L;
    }

    private static String deterministicId(String sourceSystem,
                                          String sourcePath,
                                          String sourceEntityId,
                                          String relationOrEntityType,
                                          String targetEntityId) {
        String seed = String.join("|",
                nullToEmpty(sourceSystem),
                nullToEmpty(sourcePath),
                nullToEmpty(sourceEntityId),
                nullToEmpty(relationOrEntityType),
                nullToEmpty(targetEntityId));
        if (seed.replace("|", "").isBlank()) {
            return null;
        }
        return "crawl-event:" + Integer.toUnsignedString(Objects.hash(seed), 36);
    }

    private static Object firstValue(Map<String, Object> metadata, String... keys) {
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null && hasText(text(value))) {
                return value;
            }
        }
        return null;
    }

    private static void putIfAbsent(Map<String, Object> metadata, String key, Object value) {
        if (value == null) {
            return;
        }
        if (!metadata.containsKey(key) || !hasText(text(metadata.get(key)))) {
            metadata.put(key, value);
        }
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String text = text(item);
                if (hasText(text)) {
                    return text;
                }
            }
            return null;
        }
        return String.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
