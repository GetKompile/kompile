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
 * Records a grounded BN node that was removed from the SSBN during
 * Bayes-Ball ancestral-set pruning.
 *
 * @param nodeKey grounded variable name that was pruned
 * @param reason  one of {@code "barren"}, {@code "d-separated"}, or
 *                {@code "cycle-skipped"}
 */
public record PrunedNodeLog(String nodeKey, String reason) {}
