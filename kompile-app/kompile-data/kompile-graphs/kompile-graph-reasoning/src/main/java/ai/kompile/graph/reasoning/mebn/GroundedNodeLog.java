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

import java.util.List;
import java.util.Map;

/**
 * Construction log entry for a single grounded BN node in the SSBN.
 *
 * <p>This record captures exactly why a grounded node received the CPT it did,
 * answering the "why this CPT" question that the {@link SSBNGenerator} previously
 * answered nowhere a consumer could read.</p>
 *
 * @param nodeKey           grounded variable name (e.g. {@code "isActive(alice)"})
 * @param mfragName         name of the {@link MFrag} that instantiated this node
 * @param ovSubstitution    OV (object variable) substitution used for this grounding
 *                          (maps arg-var name → concrete entity ID)
 * @param contextResults    one {@link ConstraintOutcome} per context constraint defined
 *                          on the MFrag, recording whether each constraint passed for
 *                          this grounding
 * @param distributionMode  the {@link SSBNGenerator.DistributionMode} assigned to this node
 *                          (CONTEXTUAL, DEFAULT, FINDING, or RECURSIVE)
 */
public record GroundedNodeLog(
        String nodeKey,
        String mfragName,
        Map<String, String> ovSubstitution,
        List<ConstraintOutcome> contextResults,
        SSBNGenerator.DistributionMode distributionMode) {}
