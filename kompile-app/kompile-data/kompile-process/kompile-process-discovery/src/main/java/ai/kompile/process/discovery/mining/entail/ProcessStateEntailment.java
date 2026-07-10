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

import ai.kompile.process.discovery.mining.log.EventLog;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Derives process-state facts from graph-derived process semantic atoms. */
public final class ProcessStateEntailment {

    private ProcessStateEntailment() {
    }

    public enum StateType {
        CONTROLLED,
        VALIDATED,
        ROLE_REQUIRED,
        APPROVAL_REQUIRED,
        APPROVED,
        ESCALATION_REQUIRED,
        ROUTED,
        THRESHOLD_GUARDED,
        SLA_GOVERNED,
        REMEDIATION_REQUIRED,
        POLICY_ATTACHED,
        BLOCKED,
        READY,
        SLA_BREACH,
        CONTROL_EFFECT,
        REMEDIATION_SEVERITY
    }

    public record EntailedState(String activity,
                                StateType stateType,
                                String object,
                                String value,
                                double confidence,
                                List<String> supportingAtomKeys,
                                Map<String, Object> attributes) implements Serializable {
        private static final long serialVersionUID = 1L;

        public EntailedState {
            supportingAtomKeys = supportingAtomKeys == null ? List.of() : List.copyOf(supportingAtomKeys);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    public record Result(List<EntailedState> states) implements Serializable {
        private static final long serialVersionUID = 1L;

        public Result {
            states = states == null ? List.of() : List.copyOf(states);
        }

        public boolean isEmpty() {
            return states.isEmpty();
        }

        public List<EntailedState> byType(StateType type) {
            return states.stream().filter(s -> s.stateType() == type).toList();
        }

        public boolean hasState(String activity, StateType type) {
            return states.stream().anyMatch(s -> s.stateType() == type && s.activity().equals(activity));
        }
    }

    public static Result entail(EventLog log) {
        return entail(ProcessSemanticAtomExtractor.extract(log));
    }

    public static Result entail(Collection<ProcessSemanticAtomExtractor.SemanticAtom> atoms) {
        if (atoms == null || atoms.isEmpty()) {
            return new Result(List.of());
        }
        Map<String, EntailedState> states = new LinkedHashMap<>();
        for (ProcessSemanticAtomExtractor.SemanticAtom atom : atoms) {
            if (atom == null || atom.activity() == null || atom.activity().isBlank()) {
                continue;
            }
            switch (normalize(atom.type())) {
                case "CONTROL" -> addState(states, atom, StateType.CONTROLLED, atom.object(), null);
                case "VALIDATES" -> addState(states, atom, StateType.VALIDATED, atom.object(), null);
                case "ROLE" -> addState(states, atom, StateType.ROLE_REQUIRED, atom.object(), null);
                case "REQUIRES_APPROVAL" -> addState(states, atom, StateType.APPROVAL_REQUIRED, atom.object(), null);
                case "APPROVED_BY" -> addState(states, atom, StateType.APPROVED, atom.object(), null);
                case "ESCALATES_TO" -> addState(states, atom, StateType.ESCALATION_REQUIRED, atom.object(), null);
                case "ROUTED_BY" -> addState(states, atom, StateType.ROUTED, atom.object(), null);
                case "THRESHOLD" -> addState(states, atom, StateType.THRESHOLD_GUARDED, atom.object(), atom.value());
                case "SLA" -> addState(states, atom, StateType.SLA_GOVERNED, atom.object(), atom.value());
                case "SLA_BREACH" -> addState(states, atom, StateType.SLA_BREACH, atom.object(), atom.value());
                case "REMEDIATION" -> addState(states, atom, StateType.REMEDIATION_REQUIRED, atom.object(), atom.value());
                case "POLICY" -> addPolicyState(states, atom);
                case "ACTION" -> addActionState(states, atom);
                case "STATUS" -> addStatusState(states, atom);
                default -> {
                }
            }
        }
        deriveCompositeStates(states);
        return new Result(states.values().stream()
                .sorted(Comparator.comparing(EntailedState::activity)
                        .thenComparing(s -> s.stateType().name())
                        .thenComparing(s -> nullToEmpty(s.object()))
                        .thenComparing(s -> nullToEmpty(s.value())))
                .toList());
    }

    private static void addPolicyState(Map<String, EntailedState> states,
                                       ProcessSemanticAtomExtractor.SemanticAtom atom) {
        addState(states, atom, StateType.POLICY_ATTACHED, atom.object(), atom.value());
        String key = normalize(atom.object());
        if (key.contains("APPROVAL")) {
            addState(states, atom, StateType.APPROVAL_REQUIRED, atom.value(), null);
        } else if (key.contains("ESCALAT")) {
            addState(states, atom, StateType.ESCALATION_REQUIRED, atom.value(), null);
        } else if (key.contains("ROUT")) {
            addState(states, atom, StateType.ROUTED, atom.value(), null);
        } else if (key.contains("SLA") || key.contains("DEADLINE")) {
            addState(states, atom, StateType.SLA_GOVERNED, atom.object(), atom.value());
        } else if (key.contains("REMEDIAT")) {
            addState(states, atom, StateType.REMEDIATION_REQUIRED, atom.value(), null);
        } else if (key.contains("BREACH") || key.contains("OVERDUE") || key.contains("LATE")) {
            addState(states, atom, StateType.SLA_BREACH, atom.object(), atom.value());
        }
    }

    private static void addActionState(Map<String, EntailedState> states,
                                       ProcessSemanticAtomExtractor.SemanticAtom atom) {
        String action = normalize(atom.object());
        if (action.equals("VALIDATE") || action.contains("VALIDAT") || action.contains("VERIFY")
                || action.contains("CHECK") || action.contains("RECONCIL")) {
            addState(states, atom, StateType.VALIDATED, atom.object(), null);
        }
        if (action.equals("APPROVE") || action.equals("APPROVED")) {
            addState(states, atom, StateType.APPROVED, atom.object(), null);
        } else if (action.contains("APPROVAL")) {
            addState(states, atom, StateType.APPROVAL_REQUIRED, atom.object(), null);
        }
        if (action.contains("ESCALAT")) {
            addState(states, atom, StateType.ESCALATION_REQUIRED, atom.object(), null);
        }
        if (action.contains("ROUT")) {
            addState(states, atom, StateType.ROUTED, atom.object(), null);
        }
        if (action.contains("REMEDIAT") || action.contains("REWORK") || action.contains("CORRECT")
                || action.contains("REJECT") || action.contains("ROLLBACK") || action.contains("QUARANTINE")) {
            addState(states, atom, StateType.REMEDIATION_REQUIRED, atom.object(), null);
        }
        if (action.contains("BLOCK") || action.contains("HOLD") || action.contains("WAIT")
                || action.contains("FAIL") || action.contains("REJECT")) {
            addState(states, atom, StateType.BLOCKED, atom.object(), null);
        }
    }

    private static void addStatusState(Map<String, EntailedState> states,
                                       ProcessSemanticAtomExtractor.SemanticAtom atom) {
        String status = normalize(atom.object());
        if (status.contains("BREACH") || status.contains("OVERDUE") || status.contains("LATE")) {
            addState(states, atom, StateType.SLA_BREACH, atom.object(), null);
        }
        if (status.contains("BLOCK") || status.contains("HOLD") || status.contains("WAIT")
                || status.contains("FAIL") || status.contains("REJECT") || status.contains("ERROR")) {
            addState(states, atom, StateType.BLOCKED, atom.object(), null);
        }
        if (status.contains("READY") || status.contains("PASS") || status.contains("SUCCESS")
                || status.contains("COMPLETE") || status.contains("DONE") || status.contains("APPROV")) {
            addState(states, atom, StateType.READY, atom.object(), null);
        }
    }

    private static void deriveCompositeStates(Map<String, EntailedState> states) {
        Map<String, List<EntailedState>> byActivity = new LinkedHashMap<>();
        for (EntailedState state : states.values()) {
            byActivity.computeIfAbsent(state.activity(), ignored -> new ArrayList<>()).add(state);
        }
        for (Map.Entry<String, List<EntailedState>> entry : byActivity.entrySet()) {
            String activity = entry.getKey();
            List<EntailedState> activityStates = entry.getValue();
            for (EntailedState state : activityStates) {
                if (state.stateType() == StateType.CONTROLLED || state.stateType() == StateType.VALIDATED) {
                    addCompositeState(states, activity, StateType.CONTROL_EFFECT, state.object(),
                            state.stateType() == StateType.VALIDATED ? "validates" : "controls",
                            state.confidence(), state.supportingAtomKeys(), state.attributes());
                }
                if (state.stateType() == StateType.REMEDIATION_REQUIRED) {
                    addCompositeState(states, activity, StateType.REMEDIATION_SEVERITY,
                            inferRemediationSeverity(state.object(), state.value()), null,
                            state.confidence(), state.supportingAtomKeys(), state.attributes());
                }
            }
            if (hasAny(activityStates, StateType.BLOCKED, StateType.ESCALATION_REQUIRED,
                    StateType.REMEDIATION_REQUIRED, StateType.SLA_BREACH)) {
                EntailedState support = first(activityStates, StateType.BLOCKED, StateType.ESCALATION_REQUIRED,
                        StateType.REMEDIATION_REQUIRED, StateType.SLA_BREACH, StateType.THRESHOLD_GUARDED);
                addCompositeState(states, activity, StateType.BLOCKED, "policy", "attention_required",
                        support.confidence(), support.supportingAtomKeys(), support.attributes());
            }
            if ((has(activityStates, StateType.VALIDATED) || has(activityStates, StateType.APPROVED))
                    && !hasAny(activityStates, StateType.BLOCKED, StateType.ESCALATION_REQUIRED,
                    StateType.REMEDIATION_REQUIRED, StateType.SLA_BREACH)) {
                EntailedState support = first(activityStates, StateType.VALIDATED, StateType.APPROVED);
                addCompositeState(states, activity, StateType.READY, "process-state", "ready_to_continue",
                        support.confidence(), support.supportingAtomKeys(), support.attributes());
            }
        }
    }

    private static boolean has(List<EntailedState> states, StateType type) {
        return states.stream().anyMatch(s -> s.stateType() == type);
    }

    private static boolean hasAny(List<EntailedState> states, StateType... types) {
        for (StateType type : types) {
            if (has(states, type)) {
                return true;
            }
        }
        return false;
    }

    private static EntailedState first(List<EntailedState> states, StateType... types) {
        for (StateType type : types) {
            for (EntailedState state : states) {
                if (state.stateType() == type) {
                    return state;
                }
            }
        }
        return states.get(0);
    }

    private static String inferRemediationSeverity(String object, String value) {
        String text = normalize(nullToEmpty(object) + " " + nullToEmpty(value));
        if (text.contains("QUARANTINE") || text.contains("ROLLBACK") || text.contains("REJECT")
                || text.contains("ESCALAT") || text.contains("BLOCK")) {
            return "HIGH";
        }
        if (text.contains("REWORK") || text.contains("CORRECT") || text.contains("RESUBMIT")
                || text.contains("REMEDIAT")) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static void addCompositeState(Map<String, EntailedState> states,
                                          String activity,
                                          StateType type,
                                          String object,
                                          String value,
                                          double confidence,
                                          List<String> support,
                                          Map<String, Object> attributes) {
        String key = type + "\u0000" + activity + "\u0000" + nullToEmpty(object) + "\u0000" + nullToEmpty(value);
        states.putIfAbsent(key, new EntailedState(activity, type, object, value, confidence, support, attributes));
    }

    private static void addState(Map<String, EntailedState> states,
                                 ProcessSemanticAtomExtractor.SemanticAtom atom,
                                 StateType type,
                                 String object,
                                 String value) {
        String key = type + "\u0000" + atom.activity() + "\u0000" + nullToEmpty(object) + "\u0000" + nullToEmpty(value);
        EntailedState existing = states.get(key);
        if (existing == null) {
            states.put(key, new EntailedState(atom.activity(), type, object, value, atom.confidence(),
                    List.of(atom.atomKey()), atom.attributes()));
            return;
        }
        Set<String> support = new LinkedHashSet<>(existing.supportingAtomKeys());
        support.add(atom.atomKey());
        Map<String, Object> attributes = new LinkedHashMap<>(existing.attributes());
        attributes.putAll(atom.attributes());
        states.put(key, new EntailedState(existing.activity(), existing.stateType(), existing.object(),
                existing.value(), Math.max(existing.confidence(), atom.confidence()),
                new ArrayList<>(support), attributes));
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
