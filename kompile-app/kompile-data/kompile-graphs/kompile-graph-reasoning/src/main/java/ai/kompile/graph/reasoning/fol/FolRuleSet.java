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
package ai.kompile.graph.reasoning.fol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An ordered, named collection of {@link FolRule}s that forms a complete logical program
 * over a {@link ai.kompile.graph.reasoning.model.ReasoningGraph}.
 *
 * <p>A {@code FolRuleSet} is the top-level input to {@link FolInferenceService}. It can hold
 * rules scoped to any entity type (or the whole graph) and is typically constructed once and
 * reused across inference calls.</p>
 *
 * <pre>
 *   FolRuleSet rules = FolRuleSet.named("my-program")
 *       .add(FolRule.of("propagate", 2.0, antecedent, consequent))
 *       .add(FolRule.of("prior",     1.0, null,        priorConstraint))
 *       .build();
 * </pre>
 */
public final class FolRuleSet {

    private final String name;
    private final List<FolRule> rules;

    private FolRuleSet(String name, List<FolRule> rules) {
        this.name = Objects.requireNonNull(name, "name");
        this.rules = List.copyOf(rules);
    }

    public String name() { return name; }

    /** All rules in insertion order. */
    public List<FolRule> rules() { return rules; }

    /** Rules scoped to a specific entity type (including rules with no scope). */
    public List<FolRule> rulesForType(String entityType) {
        List<FolRule> out = new ArrayList<>();
        for (FolRule r : rules) {
            String scope = r.entityTypeScope();
            if (scope == null || scope.equalsIgnoreCase(entityType)) {
                out.add(r);
            }
        }
        return out;
    }

    public int size() { return rules.size(); }
    public boolean isEmpty() { return rules.isEmpty(); }

    // ─── Builder ────────────────────────────────────────────────────────────────

    public static Builder named(String name) { return new Builder(name); }

    /** Convenience: wrap a fixed list of rules without a builder. */
    public static FolRuleSet of(String name, Collection<FolRule> rules) {
        return new FolRuleSet(name, new ArrayList<>(rules));
    }

    public static final class Builder {
        private final String name;
        private final List<FolRule> rules = new ArrayList<>();

        private Builder(String name) { this.name = name; }

        public Builder add(FolRule rule) {
            rules.add(Objects.requireNonNull(rule, "rule"));
            return this;
        }

        public Builder addAll(Collection<? extends FolRule> c) {
            for (FolRule r : c) add(r);
            return this;
        }

        public FolRuleSet build() { return new FolRuleSet(name, rules); }
    }

    @Override
    public String toString() {
        return "FolRuleSet{" + name + ", " + rules.size() + " rules}";
    }
}
