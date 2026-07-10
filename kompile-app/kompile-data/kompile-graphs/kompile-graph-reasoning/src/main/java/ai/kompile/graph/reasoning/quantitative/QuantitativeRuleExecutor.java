/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.quantitative;

import java.util.Map;
import java.util.Set;

/**
 * Distribution-provided executor for a quantitative rule engine id.
 *
 * <p>The reasoning library ships a strict expression executor, while applications may assemble
 * spreadsheet, solver, tensor, or remote execution implementations without hardcoded backends.</p>
 */
public interface QuantitativeRuleExecutor {

    Set<String> engineIds();

    double execute(QuantitativeRule rule, Map<String, Double> variables);
}
