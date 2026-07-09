/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.mebn;

/**
 * Records the outcome of evaluating one context constraint during SSBN construction.
 *
 * @param constraintDisplay human-readable description of the constraint (from
 *                          {@link ai.kompile.graph.reasoning.mebn.logic.LogicalConstraint#describe()})
 * @param passed            {@code true} if the constraint evaluated to {@code true}
 *                          for this grounding; {@code false} otherwise
 */
public record ConstraintOutcome(String constraintDisplay, boolean passed) {}
