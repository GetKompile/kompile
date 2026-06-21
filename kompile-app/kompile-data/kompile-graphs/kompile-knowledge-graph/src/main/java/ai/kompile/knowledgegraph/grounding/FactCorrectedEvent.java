/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.grounding;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Spring event published when a human correction is applied to a derived fact.
 *
 * <p>Parallel to {@link AgentFactAssertedEvent}: triggers the same cascade hook
 * ({@code GroundingCascadeHook}) so that atoms derived FROM the corrected atom
 * get re-derived from the corrected value, while the PinGuard protects the corrected
 * atom itself from being overwritten.</p>
 */
@Getter
public class FactCorrectedEvent extends ApplicationEvent {

    private final long factSheetId;
    private final String atomKey;
    private final double correctedValue;
    private final String actor;
    private final String auditEventId;

    public FactCorrectedEvent(Object source, long factSheetId, String atomKey,
                               double correctedValue, String actor, String auditEventId) {
        super(source);
        this.factSheetId = factSheetId;
        this.atomKey = atomKey;
        this.correctedValue = correctedValue;
        this.actor = actor;
        this.auditEventId = auditEventId;
    }
}
