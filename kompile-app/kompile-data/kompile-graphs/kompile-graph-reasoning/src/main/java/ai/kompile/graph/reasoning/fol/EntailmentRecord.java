/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Captures the inference audit trail for one inferred conclusion.
 *
 * <p>An EntailmentRecord links a posterior probability (from MEBN) or soft-truth value
 * (from PSL) back to the findings/facts that supported it and the rules that fired.</p>
 *
 * @param groundedRvOrAtomKey    the grounded variable or atom key (e.g. "isActive(alice)")
 * @param posterior              posterior probability (MEBN) or soft-truth value (PSL), in [0,1]
 * @param supportingFindingKeys  grounded keys of findings / atom keys of facts that influenced this
 * @param activatedRules         rule names or display strings that fired (distance below
 *                               the activation threshold)
 * @param computedAt             when this record was computed
 * @param inferenceRunId         identifier for the inference run that produced this record
 * @param nearMissRules          rule display strings for rules that almost fired — distance in
 *                               {@code [activationThreshold, nearMissThreshold)}, prefixed with
 *                               {@code "d=<value>: "} so callers can sort/filter by distance;
 *                               empty for non-PSL paths
 */
public record EntailmentRecord(
        String groundedRvOrAtomKey,
        double posterior,
        List<String> supportingFindingKeys,
        List<String> activatedRules,
        Instant computedAt,
        String inferenceRunId,
        List<String> nearMissRules
) {

    /**
     * Back-compat 6-arg constructor (no nearMissRules). All existing call sites that do not
     * supply near-miss rules continue to compile unchanged.
     */
    public EntailmentRecord(String groundedRvOrAtomKey, double posterior,
                            List<String> supportingFindingKeys, List<String> activatedRules,
                            Instant computedAt, String inferenceRunId) {
        this(groundedRvOrAtomKey, posterior, supportingFindingKeys, activatedRules,
                computedAt, inferenceRunId, List.of());
    }

    public EntailmentRecord {
        Objects.requireNonNull(groundedRvOrAtomKey, "groundedRvOrAtomKey must not be null");
        Objects.requireNonNull(computedAt, "computedAt must not be null");
        Objects.requireNonNull(inferenceRunId, "inferenceRunId must not be null");
        supportingFindingKeys = (supportingFindingKeys == null) ? List.of() : List.copyOf(supportingFindingKeys);
        activatedRules = (activatedRules == null) ? List.of() : List.copyOf(activatedRules);
        nearMissRules = (nearMissRules == null) ? List.of() : List.copyOf(nearMissRules);
    }
}
