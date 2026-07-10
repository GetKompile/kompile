/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.process.discovery.mining.entail;

import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import ai.kompile.process.ontology.OntologyRelationSchemaCompiler;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Extracts reusable process semantic atoms from graph-derived event attributes. */
public final class ProcessSemanticAtomExtractor {

    private ProcessSemanticAtomExtractor() {
    }

    public record SemanticAtom(String atomKey,
                               String type,
                               String activity,
                               String object,
                               String value,
                               String sourceEventId,
                               double confidence,
                               Map<String, Object> attributes) implements Serializable {
        private static final long serialVersionUID = 1L;

        public SemanticAtom {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    public static List<SemanticAtom> extract(EventLog log) {
        return extract(log, List.of());
    }

    public static List<SemanticAtom> extract(EventLog log,
                                             Collection<OntologyRelationSchemaCompiler.ProcessSemanticProfile> profiles) {
        if (log == null || log.traces().isEmpty()) {
            return List.of();
        }
        List<OntologyRelationSchemaCompiler.ProcessSemanticProfile> profileList = profiles == null
                ? List.of()
                : profiles.stream().filter(Objects::nonNull).toList();
        List<SemanticAtom> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Trace trace : log.traces()) {
            for (Event event : trace.events()) {
                extract(event, out, seen, profileList);
            }
        }
        return List.copyOf(out);
    }

    private static void extract(Event event, List<SemanticAtom> out, Set<String> seen,
                                List<OntologyRelationSchemaCompiler.ProcessSemanticProfile> profiles) {
        Map<String, Object> attrs = event.attributes();
        String activity = event.activity();
        double confidence = number(attrs.get("confidence"), 1.0);
        String relationType = normalize(text(firstValue(attrs, "relationType", "canonicalRelationType", "originalRelationType")));

        for (String control : strings(attrs,
                "controlId", "controlIds", "goldControlId", "expectedControlId",
                "relation.controlId", "relation.controlIds", "relation.goldControlId",
                "source.controlId", "source.controlIds", "source.goldControlId",
                "target.controlId", "target.controlIds", "target.goldControlId")) {
            add(out, seen, "CONTROL", ProcessAtoms.controlsAtom(control, activity), activity, control, null, event, confidence, attrs);
            if ("VALIDATES".equals(relationType)) {
                add(out, seen, "VALIDATES", ProcessAtoms.validatesAtom(control, activity), activity, control, null,
                        event, confidence, attrs);
            }
        }

        if ("VALIDATES".equals(relationType)) {
            String control = firstText(attrs, "sourceNodeId", "relation.sourceNodeId");
            if (hasText(control)) {
                add(out, seen, "VALIDATES", ProcessAtoms.validatesAtom(control, activity), activity, control, null,
                        event, confidence, attrs);
            }
        }

        for (String role : strings(attrs,
                "requiredRole", "requiredRoles", "role", "roles", "owner", "gate",
                "source.requiredRole", "source.requiredRoles", "source.role", "source.roles", "source.owner",
                "target.requiredRole", "target.requiredRoles", "target.role", "target.roles", "target.owner",
                "relation.requiredRole", "relation.requiredRoles", "relation.role", "relation.roles", "relation.owner")) {
            add(out, seen, "ROLE", ProcessAtoms.hasRoleAtom(activity, role), activity, role, null,
                    event, confidence, attrs);
        }

        for (String approver : approvalTargets(attrs, relationType)) {
            add(out, seen, "APPROVED_BY", ProcessAtoms.approvedByAtom(activity, approver), activity, approver, null,
                    event, confidence, attrs);
        }
        for (String policy : strings(attrs, "approvalPolicy", "relation.approvalPolicy")) {
            add(out, seen, "REQUIRES_APPROVAL", ProcessAtoms.requiresApprovalAtom(activity, policy), activity, policy, null,
                    event, confidence, attrs);
        }
        for (String target : escalationTargets(attrs, relationType)) {
            add(out, seen, "ESCALATES_TO", ProcessAtoms.escalatesToAtom(activity, target), activity, target, null,
                    event, confidence, attrs);
        }
        for (String policy : strings(attrs, "routingPolicy", "route", "relation.routingPolicy")) {
            add(out, seen, "ROUTED_BY", ProcessAtoms.routedByAtom(activity, policy), activity, policy, null,
                    event, confidence, attrs);
        }
        for (String action : strings(attrs, "actionType", "action", "actionCategory",
                "relation.actionType", "relation.action", "relation.actionCategory")) {
            add(out, seen, "ACTION", ProcessAtoms.actionAtom(activity, action), activity, action, null,
                    event, confidence, attrs);
        }
        addKeyedValues(out, seen, event, attrs, activity, "SLA", confidence, ProcessAtoms::slaAtom,
                "sla", "slaSeconds", "slaDeadline", "deadline", "dueDate",
                "relation.sla", "relation.slaSeconds", "relation.slaDeadline");
        addKeyedValues(out, seen, event, attrs, activity, "SLA_BREACH", confidence, ProcessAtoms::slaBreachAtom,
                "slaBreached", "slaBreach", "deadlineMissed", "overdue", "late",
                "relation.slaBreached", "relation.slaBreach", "relation.deadlineMissed", "relation.overdue");
        for (String status : strings(attrs,
                "status", "state", "outcome", "result",
                "relation.status", "relation.state", "relation.outcome", "relation.result")) {
            add(out, seen, "STATUS", ProcessAtoms.statusAtom(activity, status), activity, status, null,
                    event, confidence, attrs);
        }
        for (String remediation : strings(attrs,
                "remediation", "remediationAction", "remediationInstructions",
                "relation.remediation", "relation.remediationAction")) {
            add(out, seen, "REMEDIATION", ProcessAtoms.remediationAtom(activity, remediation), activity,
                    remediation, null, event, confidence, attrs);
        }
        addProfileAtoms(out, seen, event, attrs, activity, relationType, profiles, confidence);
        addThreshold(out, seen, event, attrs, activity, "threshold", "value", confidence);
        addThreshold(out, seen, event, attrs, activity, "confidenceThreshold", "confidence", confidence);
        addThreshold(out, seen, event, attrs, activity, "amountThreshold", "amount", confidence);
        addThreshold(out, seen, event, attrs, activity, "varianceThreshold", "variance", confidence);
        addThreshold(out, seen, event, attrs, activity, "relation.threshold", "value", confidence);
        addThreshold(out, seen, event, attrs, activity, "relation.confidenceThreshold", "confidence", confidence);
        addThreshold(out, seen, event, attrs, activity, "relation.amountThreshold", "amount", confidence);
        addThreshold(out, seen, event, attrs, activity, "relation.varianceThreshold", "variance", confidence);
    }

    private static void addProfileAtoms(List<SemanticAtom> out, Set<String> seen, Event event,
                                        Map<String, Object> attrs, String activity, String relationType,
                                        List<OntologyRelationSchemaCompiler.ProcessSemanticProfile> profiles,
                                        double confidence) {
        if (profiles == null || profiles.isEmpty()) {
            return;
        }
        for (OntologyRelationSchemaCompiler.ProcessSemanticProfile profile : profiles) {
            if (!profile.matchesRelationType(relationType)) {
                continue;
            }
            for (String action : profile.actionCategories()) {
                add(out, seen, "ACTION", ProcessAtoms.actionAtom(activity, action), activity, action, null,
                        event, confidence, attrs);
            }
            for (String control : profile.controlSignatures()) {
                add(out, seen, "CONTROL", ProcessAtoms.controlsAtom(control, activity), activity, control, null,
                        event, confidence, attrs);
                if ("VALIDATES".equals(relationType) || normalize(relationType).contains("VALIDAT")) {
                    add(out, seen, "VALIDATES", ProcessAtoms.validatesAtom(control, activity), activity, control, null,
                            event, confidence, attrs);
                }
            }
            for (Map.Entry<String, Object> entry : profile.policyMetadata().entrySet()) {
                addPolicyValue(out, seen, event, attrs, activity, entry.getKey(), entry.getValue(), confidence);
            }
        }
    }

    private static void addPolicyValue(List<SemanticAtom> out, Set<String> seen, Event event,
                                       Map<String, Object> attrs, String activity, String key, Object value,
                                       double confidence) {
        for (String text : values(value)) {
            String policyKey = simpleKey(key);
            add(out, seen, "POLICY", ProcessAtoms.policyAtom(activity, policyKey, text), activity,
                    policyKey, text, event, confidence, attrs);
            String normalizedKey = normalize(policyKey);
            if (normalizedKey.contains("APPROVAL")) {
                add(out, seen, "REQUIRES_APPROVAL", ProcessAtoms.requiresApprovalAtom(activity, text), activity,
                        text, null, event, confidence, attrs);
            } else if (normalizedKey.contains("ROUT")) {
                add(out, seen, "ROUTED_BY", ProcessAtoms.routedByAtom(activity, text), activity,
                        text, null, event, confidence, attrs);
            } else if (normalizedKey.contains("ESCALAT")) {
                add(out, seen, "ESCALATES_TO", ProcessAtoms.escalatesToAtom(activity, text), activity,
                        text, null, event, confidence, attrs);
            } else if (normalizedKey.contains("SLA") || normalizedKey.contains("DEADLINE")) {
                add(out, seen, "SLA", ProcessAtoms.slaAtom(activity, policyKey, text), activity,
                        policyKey, text, event, confidence, attrs);
            } else if (normalizedKey.contains("REMEDIAT")) {
                add(out, seen, "REMEDIATION", ProcessAtoms.remediationAtom(activity, text), activity,
                        text, null, event, confidence, attrs);
            }
        }
    }

    private static void addKeyedValues(List<SemanticAtom> out, Set<String> seen, Event event,
                                       Map<String, Object> attrs, String activity, String type,
                                       double confidence, KeyedAtomFactory atomFactory, String... keys) {
        for (String key : keys) {
            for (String value : values(attrs.get(key))) {
                String policyKey = simpleKey(key);
                add(out, seen, type, atomFactory.atom(activity, policyKey, value), activity, policyKey, value,
                        event, confidence, attrs);
            }
        }
    }

    private interface KeyedAtomFactory {
        String atom(String activity, String key, String value);
    }

    private static List<String> approvalTargets(Map<String, Object> attrs, String relationType) {
        List<String> targets = new ArrayList<>();
        addStrings(targets, attrs.get("approver"));
        addStrings(targets, attrs.get("approvers"));
        addStrings(targets, attrs.get("relation.approver"));
        addStrings(targets, attrs.get("relation.approvers"));
        if ("APPROVED_BY".equals(relationType)) {
            addIfText(targets, firstText(attrs, "sourceNodeId", "targetNodeId"));
            addIfText(targets, firstText(attrs, "source.role", "target.role"));
            addIfText(targets, firstText(attrs, "source.owner", "target.owner"));
        }
        return targets;
    }

    private static List<String> escalationTargets(Map<String, Object> attrs, String relationType) {
        List<String> targets = new ArrayList<>();
        addStrings(targets, attrs.get("escalationTarget"));
        addStrings(targets, attrs.get("escalationTargets"));
        addStrings(targets, attrs.get("relation.escalationTarget"));
        if ("ESCALATED_TO".equals(relationType)) {
            addIfText(targets, firstText(attrs, "targetNodeId", "target.role", "target.owner"));
        }
        return targets;
    }

    private static void addThreshold(List<SemanticAtom> out, Set<String> seen, Event event,
                                     Map<String, Object> attrs, String activity, String key,
                                     String defaultField, double confidence) {
        Object value = attrs.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return;
        }
        String field = firstText(attrs, "thresholdField", "policyField", "relation.thresholdField");
        if (!hasText(field)) {
            field = defaultField;
        }
        String operator = firstText(attrs, "thresholdOperator", "policyOperator", "relation.thresholdOperator");
        if (!hasText(operator)) {
            operator = ">=";
        }
        String threshold = String.valueOf(value).trim();
        add(out, seen, "THRESHOLD", ProcessAtoms.thresholdAtom(activity, field, operator, threshold),
                activity, field, threshold, event, confidence, attrs);
    }

    private static void add(List<SemanticAtom> out, Set<String> seen, String type, String atomKey,
                            String activity, String object, String value, Event event,
                            double confidence, Map<String, Object> attrs) {
        if (atomKey == null || atomKey.isBlank() || !seen.add(atomKey)) {
            return;
        }
        Map<String, Object> source = new LinkedHashMap<>();
        put(source, "caseId", event.caseId());
        put(source, "eventId", event.graphNodeId());
        put(source, "relationType", attrs.get("relationType"));
        put(source, "sourceNodeId", attrs.get("sourceNodeId"));
        put(source, "targetNodeId", attrs.get("targetNodeId"));
        out.add(new SemanticAtom(atomKey, type, activity, object, value, event.graphNodeId(),
                confidence, source));
    }

    private static List<String> strings(Map<String, Object> attrs, String... keys) {
        List<String> out = new ArrayList<>();
        for (String key : keys) {
            addStrings(out, attrs.get(key));
        }
        return out;
    }

    private static List<String> values(Object value) {
        List<String> out = new ArrayList<>();
        addStrings(out, value);
        return out;
    }

    private static String simpleKey(String key) {
        if (key == null) {
            return "value";
        }
        int dot = key.lastIndexOf('.');
        return dot >= 0 && dot < key.length() - 1 ? key.substring(dot + 1) : key;
    }

    private static void addStrings(List<String> out, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addStrings(out, item);
            }
            return;
        }
        for (String part : String.valueOf(value).split(",")) {
            addIfText(out, part);
        }
    }

    private static void addIfText(List<String> out, String value) {
        if (hasText(value)) {
            String trimmed = value.trim();
            if (!out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
    }

    private static Object firstValue(Map<String, Object> attrs, String... keys) {
        for (String key : keys) {
            Object value = attrs.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstText(Map<String, Object> attrs, String... keys) {
        Object value = firstValue(attrs, keys);
        return value == null ? null : String.valueOf(value).trim();
    }

    private static void put(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    private static double number(Object raw, double defaultValue) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
