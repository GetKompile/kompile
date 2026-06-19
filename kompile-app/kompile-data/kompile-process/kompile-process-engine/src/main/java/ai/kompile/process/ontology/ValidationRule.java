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

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * An executable validation rule in the ontology.
 * Can be: arithmetic assertion, budget limit, escalation trigger, or custom expression.
 * The expression field holds a CEL/SpEL-compatible expression.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ValidationRule {

    private String id;
    private String name;
    private String description;
    private RuleType ruleType;
    /**
     * Executable expression, e.g., "|D60 - sum(Detail)| <= 1000".
     * Compatible with CEL or SpEL evaluation.
     */
    private String expression;
    private RuleSeverity severity;
    /** Action on violation: "escalate", "auto_correct", "halt", "log". */
    private String onViolation;
    /** Role or person to escalate to when the rule fires. */
    private String escalateTo;
    /** Runtime parameters such as thresholds, budgets, etc. */
    private Map<String, Object> parameters;
    private List<ProvenanceCitation> provenance;
}
